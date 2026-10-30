package net.mvexel.intervalswatch.wear

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.withSlot
import net.mvexel.intervalswatch.shared.withSport
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.wear.compose.material.LocalContentColor
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.shared.SaveStatus
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import net.mvexel.intervalswatch.shared.keepScreenOn
import net.mvexel.intervalswatch.shared.withUnits
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import net.mvexel.intervalswatch.wear.ui.HeartText
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.RbType
import net.mvexel.intervalswatch.wear.ui.RingVariant
import net.mvexel.intervalswatch.wear.ui.WorkoutRing
import net.mvexel.intervalswatch.wear.ui.isRep
import net.mvexel.intervalswatch.shared.CrownPager
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.withStyle
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.material.curvedText
import net.mvexel.intervalswatch.wear.ui.RunbumTheme
import net.mvexel.intervalswatch.wear.ui.kindColor
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
    private lateinit var screenSettings: ScreenSettingsStore

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
        screenSettings = ScreenSettingsStore(this)
        // adb shell am start -n .../.wear.MainActivity --es debug_sample ride|run|walk
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            when (intent.getStringExtra("debug_sample")) {
                "ride" -> repo.inject(Samples.ride)
                "run" -> repo.inject(Samples.run)
                "walk" -> repo.inject(Samples.walk)
            }
        }
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        setContent { RunbumTheme { App(repo, screenSettings, ambient.value, ambientTick.value, burnIn.value) } }
    }

    override fun onStart() {
        super.onStart()
        repo.startListening()
        screenSettings.startListening()
        lifecycleScope.launch { screenSettings.load() }
    }

    override fun onStop() {
        repo.stopListening()
        screenSettings.stopListening()
        super.onStop()
    }
}

@Composable
private fun App(repo: WorkoutRepository, screenSettings: ScreenSettingsStore, ambient: Boolean, ambientTick: Int, burnIn: Boolean) {
    val bundle by repo.bundle.collectAsStateWithLifecycle()
    val phoneLoggedIn by repo.phoneLoggedIn.collectAsStateWithLifecycle()
    val settings by screenSettings.settings.collectAsStateWithLifecycle()
    val session by ExerciseService.session.collectAsStateWithLifecycle()
    val save by RecordingStore.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Workout?>(null) }
    var picking by remember { mutableStateOf(false) }
    // Settings editor (B7): the list, and the slot map for one sport. Neither is reachable during a session.
    var settingsOpen by remember { mutableStateOf(false) }
    var editSport by remember { mutableStateOf<Sport?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        RecordingStore.restore(context)
        runCatching { repo.load() }
        runCatching { RecordingStore.sendPending(context) }
    }

    val s = session
    // "Keep on during work steps" (B8): hold the screen on while a work step runs.
    val keepOn = s != null && !s.ended && settings.keepScreenOn(s.step?.kind)
    DisposableEffect(keepOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepOn) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
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

            s != null -> {
                PagedWorkout(s, settings)
            }
            editSport != null -> SlotMapScreen(editSport!!, settings.forSport(editSport!!), { slot, field ->
                scope.launch { screenSettings.update { it.withSport(editSport!!) { p -> p.withSlot(slot, field) } } }
            })
            settingsOpen -> SettingsList(
                settings,
                onEditSport = { editSport = it },
                onToggleOffTarget = { sport -> scope.launch { screenSettings.update { it.withSport(sport) { p -> p.copy(offTargetBuzz = !p.offTargetBuzz) } } } },
                onBuzzStrength = {
                    val next = if (settings.buzzStrength == BuzzStrength.STRONG) BuzzStrength.NORMAL else BuzzStrength.STRONG
                    playBuzzSample(context, next)
                    scope.launch { screenSettings.update { it.copy(buzzStrength = next) } }
                },
            )
            selected != null && picking -> PowerMeterPickerScreen(onBack = { picking = false })
            selected != null && selected!!.isOpen -> OpenStart(selected!!, onBack = { selected = null })
            selected != null -> WorkoutDetail(selected!!, onPickPower = { picking = true }, onScreens = { editSport = selected!!.sport }, onBack = { selected = null })
            else -> WorkoutList(bundle?.workouts.orEmpty().map { it.withUnits(settings) }, save, phoneLoggedIn, onSettings = { settingsOpen = true }) { selected = it }
        }
    }
    if (s != null) content()
    else androidx.compose.runtime.key(selected, picking, settingsOpen, editSport) {
        androidx.wear.compose.material.SwipeToDismissBox(
            onDismissed = {
                when {
                    editSport != null -> editSport = null
                    settingsOpen -> settingsOpen = false
                    picking -> picking = false
                    selected != null -> selected = null
                    else -> (context as? android.app.Activity)?.finish()
                }
            },
            backgroundScrimColor = Rb.ground,
        ) { isBackground -> if (!isBackground) content() }
    }
}

@Composable
internal fun WorkoutList(workouts: List<Workout>, save: SaveStatus? = null, phoneLoggedIn: Boolean = true, onSettings: (() -> Unit)? = null, clock: String = clockNow(), today: java.time.LocalDate = java.time.LocalDate.now(), onPick: (Workout) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        WatchList {
            item("open") {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Sport.RUN to "Run", Sport.RIDE to "Ride", Sport.WALK to "Walk").forEach { (sport, label) ->
                        Box(
                            Modifier.size(44.dp).border(1.5.dp, Rb.ink2, CircleShape).clip(CircleShape)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = { onPick(Workout.open(sport, java.time.LocalDateTime.now().withNano(0).toString())) }),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = RbType.label, maxLines = 1) }
                    }
                }
            }
            // Last upload still unresolved after a restart.
            if (save is SaveStatus.Failed) {
                item { Text(save.reason, fontSize = 11.sp, color = Rb.signal, textAlign = TextAlign.Center) }
            }
            if (!phoneLoggedIn) {
                item {
                    Text(
                        "Phone not logged in. Open Ronde on the phone and log in.",
                        fontSize = 11.sp, color = Rb.signal, textAlign = TextAlign.Center,
                    )
                }
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
                val isToday = workoutDay(w, today) == "Today"
                Chip(
                    onClick = { onPick(w) },
                    icon = { WorkoutRing(w.steps, RingVariant.Plain, 4.dp, Modifier.size(28.dp)) },
                    label = { Text(w.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        val day = workoutDay(w, today)
                        val rest = listOfNotNull(
                            w.sport.name.lowercase().replaceFirstChar { it.uppercase() } + if (w.indoor) " in" else "",
                            w.totalDurationSec?.let { Format.duration(it.toDouble()) },
                        ).joinToString(" · ")
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                if (isToday) withStyle(androidx.compose.ui.text.SpanStyle(color = Rb.work, fontWeight = FontWeight.SemiBold)) { append(day) } else append(day)
                                append(" · $rest")
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    border = if (isToday) ChipDefaults.outlinedChipBorder(Rb.work) else ChipDefaults.chipBorder(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onSettings != null) {
                item("settings") {
                    Chip(onClick = onSettings, label = { Text("Settings") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        // Clock on the top edge.
        CurvedLayout(anchor = 270f) {
            curvedText(clock, color = Rb.ink2, fontSize = RbType.edge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Open Run/Ride/Walk: no detail screen, straight into permissions and the recording. */
@Composable
private fun OpenStart(w: Workout, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ExerciseService.start(ctx, w)
    }
    LaunchedEffect(w) { launcher.launch(workoutPermissions(w)) }
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        Text(w.name, style = MaterialTheme.typography.title3)
    }
}

private fun workoutPermissions(w: Workout): Array<String> = buildList {
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

@Composable
private fun WorkoutDetail(w: Workout, onPickPower: () -> Unit, onScreens: (() -> Unit)? = null, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perms = remember(w.indoor, w.usesPower) { workoutPermissions(w) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ExerciseService.start(ctx, w)
    }
    var listing by remember { mutableStateOf(false) }
    if (listing) {
        WorkoutSteps(w, onPickPower, onScreens) { listing = false }
        return
    }
    DetailBezel(w, onStart = { launcher.launch(perms) }, onSteps = { listing = true })
}

/** The workout ring is the bezel; the crown scrubs its highlight and the line under Start describes that step (ux D1, D3). */
@Composable
internal fun DetailBezel(w: Workout, selected: Int = 0, onStart: () -> Unit, onSteps: () -> Unit) {
    var index by remember { mutableStateOf(selected.coerceIn(0, maxOf(0, w.steps.size - 1))) }
    val pager = remember { CrownPager(thresholdDetents = 1f) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Rb.ground)
            .onRotaryScrollEvent { e ->
                val dir = pager.turn(e.verticalScrollPixels / 40f, System.currentTimeMillis())
                if (dir != 0) index = (index + dir).coerceIn(0, maxOf(0, w.steps.size - 1))
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        WorkoutRing(w.steps, RingVariant.Highlight(index), 9.dp)
        CurvedLayout(Modifier.padding(11.dp), anchor = 270f) {
            curvedText(w.name.uppercase(), color = Rb.ink, fontSize = RbType.edge, fontWeight = FontWeight.SemiBold)
        }
        // GPS only matters outdoors; the heart rate sensor is always wanted.
        CurvedLayout(Modifier.padding(11.dp), anchor = 90f, angularDirection = CurvedDirection.Angular.CounterClockwise) {
            curvedText(
                (if (w.indoor) "indoor" else "GPS") + " · HR",
                color = Rb.ink3, fontSize = RbType.edge, fontWeight = FontWeight.Medium,
                angularDirection = CurvedDirection.Angular.CounterClockwise,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(52.dp).background(Rb.work, CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onStart),
                contentAlignment = Alignment.Center,
            ) { Text("▶", color = Rb.ground) }
            Text(
                stepDescription(w, index),
                style = RbType.label, color = Rb.ink2, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 30.dp).clickable(onClick = onSteps),
            )
        }
    }
}

/** The full step list, the coach's notes' neighbours (power meter, screens) and back. */
@Composable
private fun WorkoutSteps(w: Workout, onPickPower: () -> Unit, onScreens: (() -> Unit)?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    WatchList {
        item { Text(w.name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
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
        if (onScreens != null) {
            item("screens") {
                Chip(
                    onClick = onScreens,
                    label = { Text("Screens") },
                    secondaryLabel = { Text(if (w.sport == Sport.RIDE) "Ride fields" else "Run fields") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(w.steps.size) { i -> StepRow(w, w.steps[i]) }
        item {
            CompactButton(onClick = onClose, colors = ButtonDefaults.secondaryButtonColors()) { Text("←") }
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

/** Full ring on the bezel: dim track plus a sweep for step progress, in the step's kind colour. */
@Composable
internal fun ProgressArc(fraction: Float, color: Color, dashed: Boolean = false) {
    Canvas(Modifier.fillMaxSize()) {
        val w = 5.dp.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        if (dashed) {
            // Paused: the ring becomes a dashed ink3 line.
            drawArc(color, 0f, 360f, false, topLeft, arcSize, style = Stroke(w / 2, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            return@Canvas
        }
        drawArc(Rb.track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}



private const val NEXT_NEAR_SEC = 10.0
private const val NEXT_NEAR_M = 100.0

/** The last 10 s (or 100 m) of a step: the runner needs to know what comes next. */
internal fun isNearEnd(p: StepProgress): Boolean =
    (p.remainingSec?.let { it <= NEXT_NEAR_SEC } ?: false) || (p.remainingM?.let { it <= NEXT_NEAR_M } ?: false)

/** Colour-independent cue: what to do, never what the number did (-1 below target -> push, 1 above -> ease). */
internal fun cue(cmp: Int?): String? = when {
    cmp == null || cmp == 0 -> null
    cmp < 0 -> "▲ push"
    else -> "▼ ease"
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

/** 52 dp round button, the same size as [HoldToEnd]'s, with no ring; [caption] goes under it in 11 sp. */
@Composable
internal fun RoundButton(onClick: () -> Unit, secondary: Boolean = false, caption: String? = null, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize()
                    .background(if (secondary) Rb.surface else Rb.ink, CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides (if (secondary) Rb.ink else Rb.ground)) { content() }
            }
        }
        caption?.let { Text(it, style = net.mvexel.intervalswatch.wear.ui.RbType.label, color = Rb.ink2, maxLines = 1) }
    }
}

/** End button that must be held for a second: a `signal` outline ring that fills as you hold; letting go cancels. */
@Composable
internal fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(52.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnd()
                }
                tryAwaitRelease()
                job.cancel()
                scope.launch { progress.snapTo(0f) }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        HoldRing(progress.value)
        Box(Modifier.size(14.dp).background(Rb.signal, RoundedCornerShape(2.dp)))
    }
}

/** The outline of [HoldToEnd]: a dim full ring with the held fraction drawn over it clockwise from 12. */
@Composable
internal fun HoldRing(fraction: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = 4.dp.toPx()
        val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
        val sz = Size(size.width - w, size.height - w)
        drawArc(Rb.signal.copy(alpha = 0.4f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(Rb.signal, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w))
    }
}

private val AmbientGray = Rb.ink2

/** Ambient for the list, detail and summary screens: nothing bright to burn in. */
@Composable
internal fun AmbientIdle() {
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        Text("Ronde", color = Rb.ink3, fontSize = 16.sp)
    }
}

/**
 * Dimmed, low-power view (ux X2): hairline workout ring, the target range as the biggest thing in Light weight,
 * minutes left. No fills, no live value, no buttons.
 */
@Composable
internal fun AmbientWorkout(s: Session, shift: Int = 0) {
    val w = s.workout
    val step = s.step
    val p = s.progress
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        WorkoutRing(w.steps, RingVariant.Hairline(s.stepIndex), 1.5.dp, Modifier.fillMaxSize().padding(2.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp).offset(x = (shift * 4).dp, y = (shift * 4).dp)) {
            if (step == null) {
                Text("Workout done", color = AmbientGray, fontSize = 18.sp, fontWeight = FontWeight.Light)
            } else {
                Text(
                    listOfNotNull(step.repeat, step.label).joinToString("  "),
                    color = AmbientGray, fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Time left is whole minutes because the screen is only redrawn about once a minute
                // (a frozen m:ss would look like a hang).
                step.primaryTarget?.let {
                    Text(Format.target(it, p.fraction, w.paceUnit, w.sport), color = Rb.ink, fontSize = 32.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                }
                val left = when {
                    p.remainingSec != null -> "${kotlin.math.ceil(p.remainingSec!! / 60).toInt()} min left"
                    p.remainingM != null -> Format.distance(p.remainingM!!, w.paceUnit) + " left"
                    else -> Format.duration(p.elapsedSec)
                }
                Text(left, color = Rb.ink2, fontSize = 16.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

/** The ring becomes the result (ux S1, S2): "✓ Saved" first, reps on target in amber, then the numbers, then OK. */
@Composable
internal fun Summary(s: Session, save: SaveStatus?, onDone: () -> Unit) {
    val w = s.workout
    val work = w.steps.indices.filter { w.steps[it].isRep() }
    val hits = work.count { s.onTarget[it] == true }
    Box(Modifier.fillMaxSize().background(Rb.ground), contentAlignment = Alignment.Center) {
        WorkoutRing(w.steps, RingVariant.Result(s.stepIndex, s.onTarget), 9.dp)
        Column(Modifier.padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            when (save) {
                SaveStatus.Saving -> Text("Saving…", style = RbType.label, color = Rb.ink2)
                SaveStatus.Saved -> Text("✓ Saved", style = RbType.body, color = Rb.ink, fontWeight = FontWeight.Bold)
                is SaveStatus.Failed -> Text(
                    save.reason, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3,
                    color = if (save.reason == "Nothing recorded") Rb.ink3 else Rb.signal,
                )
                null -> Unit
            }
            if (work.isNotEmpty()) Text("$hits of ${work.size} reps on target", style = RbType.label, color = Rb.work, maxLines = 1)
            Text(
                listOfNotNull(Format.duration(s.activeSec), s.distanceM.takeIf { it > 0 }?.let { Format.distance(it, w.paceUnit) }).joinToString(" · "),
                style = RbType.partner, color = Rb.ink, maxLines = 1,
            )
            summaryDetail(s)?.let { (hr, pace) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    hr?.let { HeartText("♥ $it", RbType.label, Rb.ink2) }
                    pace?.let { Text(it, style = RbType.label, color = Rb.ink2, maxLines = 1) }
                }
            }
            Box(
                Modifier.padding(top = 4.dp).size(width = 64.dp, height = 32.dp).background(Rb.ink, CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = Rb.ground, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Average heart rate and average pace (or speed on a ride) of the whole session; null parts when there's no data. */
private fun summaryDetail(s: Session): Pair<String?, String?>? {
    val hr = s.avgHeartRate?.let { "%.0f".format(it) }
    val pace = if (s.distanceM > 0 && s.activeSec > 0) {
        val v = s.distanceM / s.activeSec
        "${Format.pace(v, s.workout.paceUnit)} ${Format.paceSuffix(s.workout.paceUnit)}"
    } else null
    return if (hr == null && pace == null) null else hr to pace
}

/** Minimal list DSL so the same screen code renders as a ScalingLazyColumn on the watch and as a
 *  plain Column in inspection mode (Paparazzi can't render ScalingLazyColumn). */
internal class WatchListScope {
    val entries = mutableListOf<@Composable () -> Unit>()
    // Stable per-entry keys (explicit key, else position) so scrolling doesn't re-create rows.
    val keys = mutableListOf<Any>()
    fun item(key: Any? = null, content: @Composable () -> Unit) { keys += key ?: "i${entries.size}"; entries += content }
    fun <T> items(list: List<T>, key: ((T) -> Any)? = null, content: @Composable (T) -> Unit) {
        list.forEach { t -> keys += key?.invoke(t) ?: "i${entries.size}"; entries += { content(t) } }
    }
    fun items(count: Int, content: @Composable (Int) -> Unit) {
        repeat(count) { i -> keys += "i${entries.size}"; entries += { content(i) } }
    }
}

@Composable
internal fun WatchList(build: WatchListScope.() -> Unit) {
    val scope = WatchListScope().apply(build)
    val entries = scope.entries
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Column(
            Modifier.fillMaxSize().padding(top = 28.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { entries.forEach { it() } }
    } else {
        // Hoisted state; rotary (crown/bezel) scrolling is built into ScalingLazyColumn since wear-compose 1.4.
        val state = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state) {
            items(entries.size, key = { scope.keys[it] }) { i -> entries[i]() }
        }
    }
}
