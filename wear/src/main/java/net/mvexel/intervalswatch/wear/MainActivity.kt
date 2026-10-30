package net.mvexel.intervalswatch.wear

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.shared.SaveStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep

class MainActivity : ComponentActivity() {

    private lateinit var repo: WorkoutRepository

    // Ambient mode: the screen dims instead of staying on. The system calls onUpdateAmbient about
    // once a minute; each call bumps ambientTick so the ambient screen recomposes only then.
    private val ambient = mutableStateOf(false)
    private val ambientTick = mutableStateOf(0)
    private val burnIn = mutableStateOf(false)
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            burnIn.value = ambientDetails.burnInProtectionRequired
            ambient.value = true
            ambientTick.value++
        }
        override fun onExitAmbient() { ambient.value = false }
        override fun onUpdateAmbient() { ambientTick.value++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WorkoutRepository(this)
        // adb shell am start -n .../.wear.MainActivity --es debug_sample ride|run
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            when (intent.getStringExtra("debug_sample")) {
                "ride" -> repo.inject(Samples.ride)
                "run" -> repo.inject(Samples.run)
            }
        }
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        setContent { MaterialTheme { App(repo, ambient.value, ambientTick.value, burnIn.value) } }
    }

    override fun onStart() {
        super.onStart()
        repo.startListening()
    }

    override fun onStop() {
        repo.stopListening()
        super.onStop()
    }
}

@Composable
private fun App(repo: WorkoutRepository, ambient: Boolean, ambientTick: Int, burnIn: Boolean) {
    val bundle by repo.bundle.collectAsStateWithLifecycle()
    val session by ExerciseService.session.collectAsStateWithLifecycle()
    val save by RecordingStore.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Workout?>(null) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RecordingStore.restore(context)
        runCatching { repo.load() }
        runCatching { RecordingStore.sendPending(context) }
    }

    val s = session
    // Swipe right goes back one screen (list: leaves the app), but never during a workout.
    val content: @Composable () -> Unit = {
        when {
            ambient && (s == null || s.ended) -> AmbientIdle()
            s != null && s.ended -> Summary(s, save) { ExerciseService.dismiss(); selected = null }
            s != null && ambient -> {
                // Freeze the session between ambient updates so the dimmed screen isn't redrawn twice a second.
                // A step change or pause is shown at once; that is a single redraw.
                val frozen = remember(ambientTick, s.stepIndex, s.paused) { s }
                AmbientWorkout(frozen, shift = if (burnIn) ambientTick % 3 - 1 else 0)
            }

            s != null -> ActiveWorkout(s)
            selected != null && picking -> PowerMeterPickerScreen(onBack = { picking = false })
            selected != null -> WorkoutDetail(selected!!, onPickPower = { picking = true }, onBack = { selected = null })
            else -> WorkoutList(bundle?.workouts.orEmpty(), save) { selected = it }
        }
    }
    if (s != null) content()
    else androidx.compose.runtime.key(selected, picking) {
        androidx.wear.compose.material.SwipeToDismissBox(
            onDismissed = {
                when {
                    picking -> picking = false
                    selected != null -> selected = null
                    else -> (context as? android.app.Activity)?.finish()
                }
            },
            backgroundScrimColor = Color.Black,
        ) { isBackground -> if (!isBackground) content() }
    }
}

@Composable
internal fun WorkoutList(workouts: List<Workout>, save: SaveStatus? = null, onPick: (Workout) -> Unit) {
    WatchList {
        item { Text("Workouts", style = MaterialTheme.typography.title3) }
        // Last upload still unresolved after a restart.
        if (save is SaveStatus.Failed) {
            item { Text(save.reason, fontSize = 11.sp, color = Color(0xFFEF5350), textAlign = TextAlign.Center) }
        }
        if (workouts.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Open the phone app and sync.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(workouts, key = { it.id }) { w ->
            Chip(
                onClick = { onPick(w) },
                label = { Text(w.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Text(
                        listOfNotNull(
                            w.startLocal.drop(5).take(11).replace('T', ' '),
                            w.totalDurationSec?.let { Format.duration(it.toDouble()) },
                        ).joinToString(" · "),
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkoutDetail(w: Workout, onPickPower: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perms = buildList {
        // Wear OS 6 (API 36) replaces BODY_SENSORS with the Health permission for heart rate.
        // compileSdk is 35, so the new name is a string literal.
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
        else add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (!w.indoor) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (w.usesPower) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ExerciseService.start(ctx, w)
    }
    WatchList {
        item { Text(w.name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
        item {
            Button(onClick = { launcher.launch(perms) }) { Text("Start") }
        }
        if (w.usesPower) {
            item {
                val store = remember { PowerMeterStore(ctx) }
                Chip(
                    onClick = onPickPower,
                    label = { Text("Power meter") },
                    secondaryLabel = { Text(store.name ?: "None chosen") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(w.steps.size) { i -> StepRow(w, w.steps[i]) }
        item {
            CompactButton(onClick = onBack, colors = ButtonDefaults.secondaryButtonColors()) { Text("←") }
        }
    }
}

@Composable
private fun StepRow(w: Workout, s: WorkoutStep) {
    val length = when {
        s.durationSec != null -> Format.duration(s.durationSec!!.toDouble())
        s.distanceM != null -> Format.distance(s.distanceM!!, w.paceUnit)
        else -> "lap"
    }
    val target = s.primaryTarget?.let { Format.target(it, 0.0, w.paceUnit, w.sport) } ?: ""
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(
            listOfNotNull(s.repeat, s.label).joinToString(" "),
            color = kindColor(s.kind), fontSize = 12.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("$length  $target", fontSize = 12.sp)
    }
}

@Composable
internal fun ActiveWorkout(s: Session) {
    val ctx = LocalContext.current
    val w = s.workout
    val step = s.step
    val p = s.progress

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (step != null) {
            ProgressArc(p.fraction.toFloat(), if (s.paused) Color.Gray else kindColor(step.kind))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp),
        ) {
            if (step == null) {
                Text("Workout done", style = MaterialTheme.typography.title2)
                Text(Format.duration(s.activeSec))
            } else {
                Text(
                    if (s.paused) "PAUSED" else listOfNotNull(step.repeat, step.label).joinToString("  "),
                    color = if (s.paused) PausedAmber else kindColor(step.kind),
                    fontSize = 13.sp, fontWeight = if (s.paused) FontWeight.Bold else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Big number: what's left of this step.
                val left = when {
                    p.remainingSec != null -> Format.duration(p.remainingSec!!)
                    p.remainingM != null -> Format.distance(p.remainingM!!, w.paceUnit)
                    else -> "+" + Format.duration(p.elapsedSec)
                }
                Column(Modifier.alpha(if (s.paused) 0.5f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(left, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                val nextNear = !s.paused && isNearEnd(p)

                step.primaryTarget?.let { t ->
                    val live = s.live(t.metric)
                    val cmp = live?.let { t.compare(it, p.fraction) }
                    val color = when (cmp) {
                        null -> Color.Gray
                        0 -> Color(0xFF4CAF50)
                        -1 -> Color(0xFF42A5F5)
                        else -> Color(0xFFEF5350)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (live != null) {
                            Text(Format.value(t.metric, live, w.paceUnit), fontSize = 28.sp, color = color, fontWeight = FontWeight.Bold)
                            Column {
                                Text(Format.unitLabel(t.metric, w.paceUnit, w.sport), fontSize = 12.sp, color = TargetColor)
                                cue(t.metric, cmp)?.let { Text(it, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold) }
                            }
                        } else {
                            Text(noValueText(s, t.metric), fontSize = 22.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (t.metric == Metric.PACE && !nextNear) s.stepAvgSpeed?.let {
                        Text("avg ${Format.pace(it, w.paceUnit)} ${Format.paceSuffix(w.paceUnit)}", fontSize = 13.sp, color = TargetColor, maxLines = 1)
                    }
                    if (nextNear && s.next != null) NextBig(s.next!!, w)
                    else Text(Format.target(t, p.fraction, w.paceUnit, w.sport), fontSize = 18.sp, color = TargetColor, maxLines = 1)
                }
                if (nextNear && step.primaryTarget == null) s.next?.let { n -> NextBig(n, w) }
                Text(secondaryLine(s), fontSize = 12.sp, color = Color.LightGray)
                }
            }
            s.error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFEF5350)) }

            if (s.paused || step == null) Text("Hold ■ to end", fontSize = 11.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.paused) {
                    CompactButton(onClick = { ExerciseService.resume(ctx) }) { Text("▶") }
                    HoldToEnd { ExerciseService.end(ctx) }
                } else {
                    CompactButton(onClick = { ExerciseService.pause(ctx) }) { Text("❚❚") }
                    if (step != null) {
                        CompactButton(
                            onClick = { ExerciseService.lap(ctx) },
                            colors = ButtonDefaults.secondaryButtonColors(),
                        ) { Text("⏭") }
                    } else {
                        HoldToEnd { ExerciseService.end(ctx) }
                    }
                }
            }

        }
    }
}

/** Full ring on the bezel: dim track plus a sweep for step progress, in the step's kind colour. */
@Composable
internal fun ProgressArc(fraction: Float, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = 5.dp.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

private val PausedAmber = Color(0xFFFFB300)
private val TargetColor = Color(0xFFEEEEEE)

private const val NEXT_NEAR_SEC = 10.0
private const val NEXT_NEAR_M = 100.0

/** The last 10 s (or 100 m) of a step: the runner needs to know what comes next. */
internal fun isNearEnd(p: StepProgress): Boolean =
    (p.remainingSec?.let { it <= NEXT_NEAR_SEC } ?: false) || (p.remainingM?.let { it <= NEXT_NEAR_M } ?: false)

/** Colour-independent cue for the live value: what to do (-1 below target, 1 above, 0/null nothing). */
internal fun cue(metric: Metric, cmp: Int?): String? = when {
    cmp == null || cmp == 0 -> null
    metric == Metric.PACE -> if (cmp < 0) "▲ faster" else "▼ slower"
    else -> if (cmp < 0) "▲ up" else "▼ ease"
}

/** Why there is no live value yet, instead of "--". */
internal fun noValueText(s: Session, metric: Metric): String = when {
    metric == Metric.PACE && !s.workout.indoor && !s.gpsReady -> "GPS…"
    metric == Metric.POWER -> powerStatusText(s.powerStatus)
    else -> "--"
}

internal fun powerStatusText(status: PowerMeter.Status?): String = when (status) {
    PowerMeter.Status.SCANNING, PowerMeter.Status.CONNECTED -> "Power…"
    PowerMeter.Status.DISCONNECTED -> "Power lost"
    else -> "No power"
}

@Composable
private fun NextBig(n: WorkoutStep, w: Workout) {
    Text("Next: ${n.label}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = kindColor(n.kind), maxLines = 1, overflow = TextOverflow.Ellipsis)
    n.primaryTarget?.let { Text(Format.target(it, 0.0, w.paceUnit, w.sport), fontSize = 18.sp, color = TargetColor, maxLines = 1) }
}

/** End button that must be held for a second; a ring fills while held, and letting go cancels. */
@Composable
internal fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(40.dp).background(Color(0xFFB71C1C), CircleShape).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing))
                    onEnd()
                }
                tryAwaitRelease()
                job.cancel()
                scope.launch { progress.snapTo(0f) }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                Color.White, -90f, 360f * progress.value, false,
                style = Stroke(width = 4.dp.toPx()), size = Size(size.width, size.height),
            )
        }
        Text("■")
    }
}

private fun secondaryLine(s: Session): String {
    val primary = s.step?.primaryTarget?.metric
    val parts = mutableListOf<String>()
    if (primary != Metric.HEART_RATE) s.heartRate?.let { parts += "♥ ${it.toInt()}" }
    if (primary != Metric.PACE && s.speed != null) {
        parts += if (s.workout.sport == net.mvexel.intervalswatch.shared.Sport.RIDE) {
            if (s.workout.paceUnit == net.mvexel.intervalswatch.shared.PaceUnit.MILE) "%.1f mph".format(s.speed * 2.23694)
            else "%.1f km/h".format(s.speed * 3.6)
        } else {
            Format.pace(s.speed, s.workout.paceUnit)
        }
    }
    if (primary != Metric.CADENCE && s.cadence != null) parts += "${s.cadence.toInt()} spm"
    if (primary != Metric.POWER && s.power != null) parts += "${s.power.toInt()} W"
    if (s.workout.usesPower && s.power == null) {
        parts += powerStatusText(s.powerStatus)
    }
    if (!s.workout.indoor) parts += if (s.gpsReady) "GPS" else "GPS…"
    parts += Format.duration(s.activeSec)
    return parts.joinToString("  ")
}

private val AmbientGray = Color(0xFFBDBDBD)

/** Ambient for the list, detail and summary screens: nothing bright to burn in. */
@Composable
internal fun AmbientIdle() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Intervals", color = Color.DarkGray, fontSize = 16.sp)
    }
}

/** Dimmed, low-power view: current step and time left, black background, no buttons. */
@Composable
internal fun AmbientWorkout(s: Session, shift: Int = 0) {
    val w = s.workout
    val step = s.step
    val p = s.progress
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 22.dp).offset(x = (shift * 4).dp, y = (shift * 4).dp)) {
            if (step == null) {
                Text("Workout done", color = AmbientGray, fontSize = 18.sp)
            } else {
                Text(
                    listOfNotNull(step.repeat, step.label).joinToString("  "),
                    color = AmbientGray, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // The target is the biggest thing; time left is whole minutes because the screen
                // is only redrawn about once a minute (a frozen m:ss would look like a hang).
                step.primaryTarget?.let {
                    Text(Format.target(it, p.fraction, w.paceUnit, w.sport), color = AmbientGray, fontSize = 30.sp, textAlign = TextAlign.Center)
                }
                val left = when {
                    p.remainingSec != null -> "${kotlin.math.ceil(p.remainingSec!! / 60).toInt()} min left"
                    p.remainingM != null -> Format.distance(p.remainingM!!, w.paceUnit) + " left"
                    else -> "+" + Format.duration(p.elapsedSec)
                }
                Text(left, color = Color.LightGray, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun Summary(s: Session, save: SaveStatus?, onDone: () -> Unit) {
    WatchList {
        item { Text("Finished", style = MaterialTheme.typography.title2) }
        item { Text(s.workout.name, textAlign = TextAlign.Center) }
        item { Text("Time ${Format.duration(s.activeSec)}") }
        if (s.distanceM > 0) item { Text(Format.distance(s.distanceM, s.workout.paceUnit)) }
        item { Text("Steps ${minOf(s.stepIndex, s.workout.steps.size)}/${s.workout.steps.size}") }
        item {
            when (save) {
                SaveStatus.Saving -> Text("Saving…", color = Color(0xFF90CAF9))
                SaveStatus.Saved -> Text("Saved to intervals.icu", color = Color(0xFFA5D6A7), textAlign = TextAlign.Center)
                is SaveStatus.Failed -> Text(
                    save.reason, fontSize = 12.sp, textAlign = TextAlign.Center,
                    color = if (save.reason == "Nothing recorded") Color.Gray else Color(0xFFEF5350),
                )
                null -> Unit
            }
        }
        item { Button(onClick = onDone) { Text("OK") } }
    }
}

private fun kindColor(k: StepKind): Color = when (k) {
    StepKind.WARMUP -> Color(0xFF90CAF9)
    StepKind.ACTIVE -> Color(0xFFFFB74D)
    StepKind.REST -> Color(0xFFA5D6A7)
    StepKind.COOLDOWN -> Color(0xFF90CAF9)
}

/** Minimal list DSL so the same screen code renders as a ScalingLazyColumn on the watch and as a
 *  plain Column in inspection mode (Paparazzi can't render ScalingLazyColumn). */
internal class WatchListScope {
    val entries = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { entries += content }
    fun <T> items(list: List<T>, key: ((T) -> Any)? = null, content: @Composable (T) -> Unit) {
        list.forEach { t -> entries += { content(t) } }
    }
    fun items(count: Int, content: @Composable (Int) -> Unit) {
        repeat(count) { i -> entries += { content(i) } }
    }
}

@Composable
internal fun WatchList(build: WatchListScope.() -> Unit) {
    val entries = WatchListScope().apply(build).entries
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Column(
            Modifier.fillMaxSize().padding(top = 28.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { entries.forEach { it() } }
    } else {
        ScalingLazyColumn(Modifier.fillMaxSize()) {
            items(entries.size) { i -> entries[i]() }
        }
    }
}
