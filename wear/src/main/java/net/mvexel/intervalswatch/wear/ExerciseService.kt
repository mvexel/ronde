package net.mvexel.intervalswatch.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.LocationData
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.RecSample
import net.mvexel.intervalswatch.shared.RecordingBuilder
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Sync
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutEngine
import net.mvexel.intervalswatch.shared.WorkoutStep
import java.time.Duration
import java.time.Instant

data class Session(
    val workout: Workout,
    val stepIndex: Int,
    val step: WorkoutStep?,
    val next: WorkoutStep?,
    val progress: StepProgress,
    val activeSec: Double,
    val distanceM: Double,
    val heartRate: Double?,
    val speed: Double?,
    val power: Double?,
    val powerStatus: PowerMeter.Status?,
    val gpsReady: Boolean,
    val paused: Boolean,
    val finished: Boolean,
    val ended: Boolean,
    val error: String? = null,
) {
    fun live(metric: Metric): Double? = when (metric) {
        Metric.POWER -> power
        Metric.HEART_RATE -> heartRate
        Metric.PACE -> speed
        Metric.CADENCE -> null
    }
}

/**
 * Runs one workout: a Health Services exercise (HR, speed, distance, GPS) plus the step engine,
 * in a foreground service so it survives the screen going off.
 */
class ExerciseService : LifecycleService() {

    companion object {
        private const val TAG = "ExerciseService"
        private const val EXTRA_WORKOUT = "workout"
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_LAP = "lap"
        private const val ACTION_END = "end"
        private const val CHANNEL = "workout"
        private const val NOTIFICATION_ID = 1

        private val _session = MutableStateFlow<Session?>(null)
        val session: StateFlow<Session?> = _session.asStateFlow()

        fun start(ctx: Context, workout: Workout) {
            val i = Intent(ctx, ExerciseService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_WORKOUT, Sync.json.encodeToString(Workout.serializer(), workout))
            ContextCompat.startForegroundService(ctx, i)
        }

        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun lap(ctx: Context) = send(ctx, ACTION_LAP)
        fun end(ctx: Context) = send(ctx, ACTION_END)
        fun dismiss() { _session.value = null }

        private fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, ExerciseService::class.java).setAction(action))
        }
    }

    private lateinit var client: ExerciseClient
    private var engine: WorkoutEngine? = null
    private var powerMeter: PowerMeter? = null
    private var ticker: Job? = null

    private var checkpoint: ExerciseUpdate.ActiveDurationCheckpoint? = null
    private var state: ExerciseState? = null
    private var distanceTotal = 0.0
    private var distanceDeltaSum = 0.0
    private var hasDistanceTotal = false
    private var heartRate: Double? = null
    private var speed: Double? = null
    private var gpsReady = false
    private var ended = false
    private var recorder: RecordingBuilder? = null
    private var recordStartMs = 0L
    private var lastSampleSec = -1
    private var location: LocationData? = null

    // Alert bookkeeping
    private var outOfRangeSinceSec: Double? = null
    private var lastRangeAlertSec = -1000.0
    private var countdownStep = -1

    override fun onCreate() {
        super.onCreate()
        client = HealthServices.getClient(this).exerciseClient
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_WORKOUT) ?: return START_NOT_STICKY
                val workout = Sync.json.decodeFromString(Workout.serializer(), json)
                goForeground(workout)
                lifecycleScope.launch { begin(workout) }
            }
            ACTION_PAUSE -> lifecycleScope.launch { runCatching { client.pauseExerciseAsync().await() } }
            ACTION_RESUME -> lifecycleScope.launch { runCatching { client.resumeExerciseAsync().await() } }
            ACTION_LAP -> {
                engine?.lap(activeSec(), distance())
                lifecycleScope.launch { runCatching { client.markLapAsync().await() } }
                buzzStepChange()
                publish()
            }
            ACTION_END -> lifecycleScope.launch { finish() }
        }
        return START_NOT_STICKY
    }

    private suspend fun begin(workout: Workout) {
        ended = false
        val type = when (workout.sport) {
            Sport.RUN -> if (workout.indoor) ExerciseType.RUNNING_TREADMILL else ExerciseType.RUNNING
            Sport.RIDE -> if (workout.indoor) ExerciseType.BIKING_STATIONARY else ExerciseType.BIKING
            Sport.OTHER -> ExerciseType.WORKOUT
        }
        val e = WorkoutEngine(workout).also { it.start() }
        engine = e
        recordStartMs = System.currentTimeMillis()
        recorder = RecordingBuilder(workout.name, workout.sport, workout.indoor, recordStartMs)
        lastSampleSec = -1
        location = null
        publish()

        try {
            val caps = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(type)
            val wanted = setOf(DataType.HEART_RATE_BPM, DataType.SPEED, DataType.DISTANCE_TOTAL, DataType.DISTANCE, DataType.LOCATION)
            val dataTypes = wanted.filter { it in caps.supportedDataTypes }.toSet()
            val useGps = !workout.indoor && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val config = ExerciseConfig.builder(type)
                .setDataTypes(dataTypes)
                .setIsGpsEnabled(useGps)
                .setIsAutoPauseAndResumeEnabled(false)
                .build()
            client.setUpdateCallback(callback)
            client.startExerciseAsync(config).await()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start exercise", t)
            _session.value = _session.value?.copy(error = "Couldn't start: ${t.message}")
        }

        if (workout.usesPower && hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        ) {
            powerMeter = PowerMeter(this).also { it.start() }
        }

        ticker = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            state = update.exerciseStateInfo.state
            update.activeDurationCheckpoint?.let { checkpoint = it }
            val m = update.latestMetrics
            m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { if (it > 0) heartRate = it }
            m.getData(DataType.SPEED).lastOrNull()?.value?.let { s ->
                // Light smoothing; GPS speed is jumpy.
                speed = speed?.let { it * 0.7 + s * 0.3 } ?: s
            }
            m.getData(DataType.DISTANCE_TOTAL)?.let {
                hasDistanceTotal = true
                distanceTotal = it.total
            }
            m.getData(DataType.LOCATION).lastOrNull()?.value?.let { location = it }
            m.getData(DataType.DISTANCE).forEach { distanceDeltaSum += it.value }
            if (state?.isEnded == true) ended = true
            tick()
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Update callback registration failed", throwable)
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (availability is LocationAvailability) {
                gpsReady = availability == LocationAvailability.ACQUIRED_TETHERED ||
                    availability == LocationAvailability.ACQUIRED_UNTETHERED
            }
        }
    }

    private fun distance(): Double = if (hasDistanceTotal) distanceTotal else distanceDeltaSum

    private fun activeSec(): Double {
        val cp = checkpoint ?: return 0.0
        val base = cp.activeDuration.toMillis() / 1000.0
        return if (state == ExerciseState.ACTIVE) {
            base + Duration.between(cp.time, Instant.now()).toMillis() / 1000.0
        } else base
    }

    private fun tick() {
        val e = engine ?: return
        val t = activeSec()
        val d = distance()
        val before = e.index
        if (e.update(t, d) > 0) {
            outOfRangeSinceSec = null
            if (e.finished && before < e.workout.steps.size) buzzDone() else buzzStepChange()
        }
        record(e, t, d)
        countdown(e, t, d)
        rangeAlert(e, t, d)
        publish()
    }

    private fun wallSec() = (System.currentTimeMillis() - recordStartMs) / 1000.0

    private fun record(e: WorkoutEngine, t: Double, d: Double) {
        val r = recorder ?: return
        val wall = wallSec()
        if (state?.isPaused == true) r.pause(wall) else r.resume(wall)
        e.current?.let { r.onStep(e.index, it, wall, d) }
        val sec = t.toInt()
        if (state != ExerciseState.ACTIVE || sec <= lastSampleSec) return
        lastSampleSec = sec
        val loc = location
        r.add(
            RecSample(
                t = wall, distanceM = d, lat = loc?.latitude, lon = loc?.longitude,
                altM = loc?.altitude?.takeIf { !it.isNaN() },
                speed = speed, hr = heartRate?.toInt(), power = powerMeter?.watts?.value?.toInt(),
            ),
        )
    }

    private fun countdown(e: WorkoutEngine, t: Double, d: Double) {
        val p = e.progress(t, d)
        val rem = p.remainingSec ?: return
        if (rem in 0.1..3.0 && countdownStep != e.index) {
            countdownStep = e.index
            vibrate(longArrayOf(0, 60, 940, 60, 940, 60))
        }
    }

    private fun rangeAlert(e: WorkoutEngine, t: Double, d: Double) {
        val step = e.current ?: return
        val target = step.primaryTarget ?: return
        val p = e.progress(t, d)
        if (p.elapsedSec < 15 || state != ExerciseState.ACTIVE) { outOfRangeSinceSec = null; return }
        val value = session.value?.live(target.metric) ?: return
        val cmp = target.compare(value, p.fraction)
        if (cmp == 0) { outOfRangeSinceSec = null; return }
        val since = outOfRangeSinceSec ?: t.also { outOfRangeSinceSec = it }
        if (t - since >= 10 && t - lastRangeAlertSec >= 30) {
            lastRangeAlertSec = t
            // Too low: two short. Too high: one long.
            vibrate(if (cmp < 0) longArrayOf(0, 120, 120, 120) else longArrayOf(0, 450))
        }
    }

    private fun publish() {
        val e = engine ?: return
        val t = activeSec()
        val d = distance()
        _session.value = Session(
            workout = e.workout,
            stepIndex = e.index,
            step = e.current,
            next = e.next,
            progress = e.progress(t, d),
            activeSec = t,
            distanceM = d,
            heartRate = heartRate,
            speed = speed,
            power = powerMeter?.watts?.value,
            powerStatus = powerMeter?.status?.value,
            gpsReady = gpsReady,
            paused = state?.isPaused == true,
            finished = e.finished,
            ended = ended,
            error = _session.value?.error,
        )
    }

    private suspend fun finish() {
        ticker?.cancel()
        runCatching { client.endExerciseAsync().await() }
        runCatching { client.clearUpdateCallbackAsync(callback).await() }
        powerMeter?.stop()
        ended = true
        publish()
        val rec = recorder?.build(wallSec(), distance())
        recorder = null
        if (rec != null) RecordingStore.save(applicationContext, rec)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(workout: Workout) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(workout.name)
            .setContentText("Workout in progress")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun buzzStepChange() = vibrate(longArrayOf(0, 400, 150, 150))
    private fun buzzDone() = vibrate(longArrayOf(0, 300, 150, 300, 150, 300))

    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onDestroy() {
        ticker?.cancel()
        powerMeter?.stop()
        super.onDestroy()
    }
}
