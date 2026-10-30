package net.mvexel.intervalswatch.wear

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep

class MainActivity : ComponentActivity() {

    private lateinit var repo: WorkoutRepository

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
        setContent { MaterialTheme { App(repo) } }
    }

    override fun onStart() {
        super.onStart()
        repo.startListening()
    }

    override fun onStop() {
        repo.stopListening()
        super.onStop()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun App(repo: WorkoutRepository) {
    val bundle by repo.bundle.collectAsStateWithLifecycle()
    val session by ExerciseService.session.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Workout?>(null) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { repo.load() } }

    val s = session
    when {
        s != null && s.ended -> Summary(s) { ExerciseService.dismiss(); selected = null }
        s != null -> ActiveWorkout(s)
        selected != null && picking -> PowerMeterPickerScreen(onBack = { picking = false })
        selected != null -> WorkoutDetail(selected!!, onPickPower = { picking = true }, onBack = { selected = null })
        else -> WorkoutList(bundle?.workouts.orEmpty()) { selected = it }
    }
}

@Composable
internal fun WorkoutList(workouts: List<Workout>, onPick: (Workout) -> Unit) {
    WatchList {
        item { Text("Workouts", style = MaterialTheme.typography.title3) }
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
    val target = s.primaryTarget?.let { Format.target(it, 0.0, w.paceUnit) } ?: ""
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
    DisposableEffect(Unit) {
        (ctx as? MainActivity)?.keepScreenOn(true)
        onDispose { (ctx as? MainActivity)?.keepScreenOn(false) }
    }
    val w = s.workout
    val step = s.step
    val p = s.progress

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            if (step == null) {
                Text("Workout done", style = MaterialTheme.typography.title2)
                Text(Format.duration(s.activeSec))
            } else {
                Text(
                    listOfNotNull(step.repeat, step.label).joinToString("  "),
                    color = kindColor(step.kind), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Big number: what's left of this step.
                val left = when {
                    p.remainingSec != null -> Format.duration(p.remainingSec!!)
                    p.remainingM != null -> Format.distance(p.remainingM!!, w.paceUnit)
                    else -> "+" + Format.duration(p.elapsedSec)
                }
                Text(left, fontSize = 34.sp, fontWeight = FontWeight.Bold)

                step.primaryTarget?.let { t ->
                    val live = s.live(t.metric)
                    val cmp = live?.let { t.compare(it, p.fraction) }
                    val color = when (cmp) {
                        null -> Color.Gray
                        0 -> Color(0xFF4CAF50)
                        -1 -> Color(0xFF42A5F5)
                        else -> Color(0xFFEF5350)
                    }
                    Text(
                        live?.let { Format.value(t.metric, it, w.paceUnit) } ?: "--",
                        fontSize = 28.sp, color = color, fontWeight = FontWeight.Bold,
                    )
                    Text(Format.target(t, p.fraction, w.paceUnit), fontSize = 13.sp, color = Color.LightGray)
                }
                Text(secondaryLine(s), fontSize = 12.sp, color = Color.LightGray)
                s.next?.let { n ->
                    Text(
                        "Next: ${n.label}" + (n.primaryTarget?.let { " " + Format.target(it, 0.0, w.paceUnit) } ?: ""),
                        fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            s.error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFEF5350)) }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.paused) {
                    CompactButton(onClick = { ExerciseService.resume(ctx) }) { Text("▶") }
                    CompactButton(
                        onClick = { ExerciseService.end(ctx) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)),
                    ) { Text("■") }
                } else {
                    CompactButton(onClick = { ExerciseService.pause(ctx) }) { Text("❚❚") }
                    if (step != null) {
                        CompactButton(
                            onClick = { ExerciseService.lap(ctx) },
                            colors = ButtonDefaults.secondaryButtonColors(),
                        ) { Text("⏭") }
                    } else {
                        CompactButton(
                            onClick = { ExerciseService.end(ctx) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB71C1C)),
                        ) { Text("■") }
                    }
                }
            }
        }
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
    if (primary != Metric.POWER && s.power != null) parts += "${s.power.toInt()} W"
    if (s.workout.usesPower && s.power == null) {
        parts += when (s.powerStatus) {
            PowerMeter.Status.SCANNING -> "PM…"
            PowerMeter.Status.DISCONNECTED -> "PM lost"
            else -> "no PM"
        }
    }
    parts += Format.duration(s.activeSec)
    return parts.joinToString("  ")
}

@Composable
internal fun Summary(s: Session, onDone: () -> Unit) {
    WatchList {
        item { Text("Finished", style = MaterialTheme.typography.title2) }
        item { Text(s.workout.name, textAlign = TextAlign.Center) }
        item { Text("Time ${Format.duration(s.activeSec)}") }
        if (s.distanceM > 0) item { Text(Format.distance(s.distanceM, s.workout.paceUnit)) }
        item { Text("Steps ${minOf(s.stepIndex, s.workout.steps.size)}/${s.workout.steps.size}") }
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
