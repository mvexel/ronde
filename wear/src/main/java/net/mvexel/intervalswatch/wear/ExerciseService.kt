package net.mvexel.intervalswatch.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
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
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
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
        private const val STALE_LOCATION_SEC = 10.0
        private const val STALE_SENSOR_SEC = 15.0

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
    private var distanceAnchor = 0.0
    private var heartRate: Double? = null
    private var speed: Double? = null
    private var gpsReady = false
    private var ended = false
    private var finishing = false
    private var recorder: RecordingBuilder? = null
    private var recordStartMs = 0L
    private var recordStartElapsed = 0L
    // Health Services delivers data in batches when the screen is off, each point stamped with its own
    // time. Keep the latest values with those times so a sample gets the value from its own moment.
    private val hrSeries = TimedValues<Double>()
    private val speedSeries = TimedValues<Double>()
    private val distanceSeries = TimedValues<Double>()
    private val powerSeries = TimedValues<Double>()
    private val locationSeries = TimedValues<LocationData>()
    private var ongoing: OngoingActivity? = null
    private var ongoingStep = -2
    private var ongoingPaused = false

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
        finishing = false
        RecordingStore.reset(applicationContext)
        val type = when (workout.sport) {
            Sport.RUN -> if (workout.indoor) ExerciseType.RUNNING_TREADMILL else ExerciseType.RUNNING
            Sport.RIDE -> if (workout.indoor) ExerciseType.BIKING_STATIONARY else ExerciseType.BIKING
            Sport.OTHER -> ExerciseType.WORKOUT
        }
        val e = WorkoutEngine(workout).also { it.start() }
        engine = e
        recordStartMs = System.currentTimeMillis()
        recordStartElapsed = SystemClock.elapsedRealtime()
        recorder = RecordingBuilder(workout.name, workout.sport, workout.indoor, recordStartMs)
        pauseSpans.clear(); pausedSince = null; distanceAnchor = 0.0
        listOf(hrSeries, speedSeries, distanceSeries, powerSeries, locationSeries).forEach { it.clear() }
        publish()

        try {
            val caps = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(type)
            val useGps = !workout.indoor && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            // LOCATION may only be requested together with setIsGpsEnabled(true).
            val wanted = setOf(DataType.HEART_RATE_BPM, DataType.SPEED, DataType.DISTANCE_TOTAL, DataType.DISTANCE) +
                if (useGps) setOf(DataType.LOCATION) else emptySet()
            val dataTypes = wanted.filter { it in caps.supportedDataTypes }.toSet()
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
            powerMeter = PowerMeter(this).also {
                // From the BLE callback, so power keeps its own time when the ticker stalls.
                it.onWatts = { w -> powerSeries.add(wallSec(), w) }
                it.start()
            }
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
            val times = sortedSetOf<Double>()
            m.getData(DataType.HEART_RATE_BPM).forEach { p ->
                if (p.value > 0) { hrSeries.add(wallOf(p.timeDurationFromBoot), p.value); heartRate = p.value; times += wallOf(p.timeDurationFromBoot) }
            }
            m.getData(DataType.SPEED).forEach { p ->
                // Light smoothing; GPS speed is jumpy.
                speed = speed?.let { it * 0.7 + p.value * 0.3 } ?: p.value
                speedSeries.add(wallOf(p.timeDurationFromBoot), speed!!)
                times += wallOf(p.timeDurationFromBoot)
            }
            // Distance: cumulative sum of the interval points (each with its own end time), anchored
            // to DISTANCE_TOTAL, which carries no time of its own.
            m.getData(DataType.DISTANCE).forEach {
                distanceDeltaSum += it.value
                distanceSeries.add(wallOf(it.endDurationFromBoot), distanceDeltaSum + distanceAnchor)
                times += wallOf(it.endDurationFromBoot)
            }
            m.getData(DataType.DISTANCE_TOTAL)?.let {
                hasDistanceTotal = true
                distanceTotal = it.total
                distanceAnchor = it.total - distanceDeltaSum
            }
            m.getData(DataType.LOCATION).forEach { p ->
                locationSeries.add(wallOf(p.timeDurationFromBoot), p.value)
                times += wallOf(p.timeDurationFromBoot)
            }
            // One recording sample per data point time, so a batch doesn't become a staircase.
            trackPause()
            times.forEach { if (!inPause(it)) recordAt(it) }
            if (state?.isEnded == true) {
                ended = true
                // Health Services ended it (another app, system): still save what we have.
                if (!finishing) lifecycleScope.launch { finish() }
            }
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

    /** Pause intervals in wall seconds, so a late batch is judged by point time, not delivery state. */
    private val pauseSpans = ArrayList<ClosedFloatingPointRange<Double>>()
    private var pausedSince: Double? = null

    private fun trackPause() {
        val paused = state?.isPaused == true
        val since = pausedSince
        if (paused && since == null) pausedSince = wallSec()
        else if (!paused && since != null) { pauseSpans += since..wallSec(); pausedSince = null }
    }

    private fun inPause(wall: Double) =
        pausedSince?.let { wall >= it } == true || pauseSpans.any { wall in it }

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
        // With a live power meter there may be no HS point for this second (indoor, HR 0), and the
        // ticker is the only clock: sample here too. The builder merges samples of the same second.
        if (powerMeter?.watts?.value != null && state == ExerciseState.ACTIVE) recordAt(wallSec())
        updateOngoing(e, e.progress(t, d).remainingSec)
        record(e, t, d)
        countdown(e, t, d)
        rangeAlert(e, t, d)
        publish()
    }

    private fun wallSec() = (SystemClock.elapsedRealtime() - recordStartElapsed) / 1000.0

    private fun record(e: WorkoutEngine, t: Double, d: Double) {
        val r = recorder ?: return
        val wall = wallSec()
        if (state?.isPaused == true) r.pause(wall) else r.resume(wall)
        e.current?.let { r.onStep(e.index, it, wall, d) }
    }

    /** Sample made from the series values at wall-clock second [wall]. */
    private fun recordAt(wall: Double) {
        val r = recorder ?: return
        if (wall < 0) return
        val loc = locationSeries.at(wall, STALE_LOCATION_SEC)
        r.add(
            RecSample(
                t = wall,
                distanceM = distanceSeries.at(wall, Double.MAX_VALUE) ?: distance(),
                lat = loc?.latitude, lon = loc?.longitude,
                altM = loc?.altitude?.takeIf { !it.isNaN() },
                speed = speedSeries.at(wall, STALE_SENSOR_SEC),
                hr = hrSeries.at(wall, STALE_SENSOR_SEC)?.toInt(),
                power = powerSeries.at(wall, STALE_SENSOR_SEC)?.toInt(),
            ),
        )
    }

    /** Health Services point times are since boot, like elapsedRealtime. */
    private fun wallOf(sinceBoot: Duration) = (sinceBoot.toMillis() - recordStartElapsed) / 1000.0

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
        if (finishing) return
        finishing = true
        ticker?.cancel()
        runCatching { client.endExerciseAsync().await() }
        runCatching { client.clearUpdateCallbackAsync(callback).await() }
        powerMeter?.stop()
        ended = true
        publish()
        val rec = recorder?.build(wallSec(), distance())
        recorder = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // After stopSelf and outside this service's scope, so a new workout isn't held up or cut off.
        if (rec != null) RecordingStore.saveAsync(applicationContext, rec)
    }

    private fun goForeground(workout: Workout) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val nb = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(workout.name)
            .setContentText("Workout in progress")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // Shows the workout on the watch face; tapping it returns to the workout screen.
        ongoing = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, nb)
            .setStaticIcon(android.R.drawable.ic_media_play)
            .setTouchIntent(open)
            .setStatus(ongoingStatus(workout.name, null, false))
            .build()
            .also { it.apply(applicationContext) }
        val n = nb.build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
    }

    /** Watch-face chip text: "label m:ss", counting down by itself for timed steps. */
    private fun ongoingStatus(label: String, remainingSec: Double?, paused: Boolean): Status {
        val b = Status.Builder().addTemplate("#label# #time#").addPart("label", Status.TextPart(label))
        // Status parts are rendered against SystemClock.elapsedRealtime().
        val now = SystemClock.elapsedRealtime()
        val time = if (remainingSec != null) {
            val ms = (remainingSec * 1000).toLong()
            if (paused) Status.TimerPart(now + ms, now, ms) else Status.TimerPart(now + ms)
        } else {
            Status.StopwatchPart(SystemClock.elapsedRealtime())
        }
        return b.addPart("time", time).build()
    }

    /** Keep the watch-face status on the current step and the pause state. */
    private fun updateOngoing(e: WorkoutEngine, remainingSec: Double?) {
        val paused = state?.isPaused == true
        if (e.index == ongoingStep && paused == ongoingPaused) return
        ongoingStep = e.index
        ongoingPaused = paused
        val label = e.current?.let { listOfNotNull(it.repeat, it.label).joinToString(" ") } ?: e.workout.name
        ongoing?.update(applicationContext, ongoingStatus(label, remainingSec, paused))
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

/** Values with their wall-clock times; [at] gives the latest one no newer than the time asked for. */
internal class TimedValues<T> {
    private val times = ArrayList<Double>()
    private val values = ArrayList<T>()

    @Synchronized fun add(t: Double, v: T) {
        if (times.isNotEmpty() && t < times.last()) return
        times += t; values += v
        // A batch can span minutes; keep ten minutes of history.
        var n = 0
        while (n < times.size && times[n] < t - KEEP_SEC) n++
        if (n > 0) { times.subList(0, n).clear(); values.subList(0, n).clear() }
    }

    @Synchronized fun clear() { times.clear(); values.clear() }

    @Synchronized fun at(t: Double, maxAgeSec: Double): T? {
        var i = times.size - 1
        while (i >= 0 && times[i] > t) i--
        return if (i >= 0 && t - times[i] <= maxAgeSec) values[i] else null
    }

    private companion object { const val KEEP_SEC = 600.0 }
}
