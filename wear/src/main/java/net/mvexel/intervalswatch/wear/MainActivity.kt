package net.mvexel.intervalswatch.wear

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.Sport
import kotlin.math.roundToInt
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.withSlot
import net.mvexel.intervalswatch.shared.withSport
import net.mvexel.intervalswatch.shared.withPaceWindow
import net.mvexel.intervalswatch.shared.next
import net.mvexel.intervalswatch.shared.nextPaceWindow
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
    private val burnInX = mutableStateOf(0)
    private val burnInY = mutableStateOf(0)
    private val walk = java.util.Random()
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            burnIn.value = ambientDetails.burnInProtectionRequired
            ambient.value = true
            ExerciseService.ambient = true
            ambientTick.value++
        }
        override fun onExitAmbient() {
            ambient.value = false
            ExerciseService.ambient = false
            logWake("exit")
            lifecycleScope.launch { delay(1000); logWake("exit+1s") }
        }
        override fun onUpdateAmbient() { stepBurnIn(); ambientTick.value++ }
    }

    /** Paced ambient burn-in protection: a new random-walk offset (at most +/-3 dp) at each ambient update. */
    private fun stepBurnIn() {
        burnInX.value = nextBurnInOffset(burnInX.value, walk.nextInt(1000))
        burnInY.value = nextBurnInOffset(burnInY.value, walk.nextInt(1000))
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
        setContent { RunbumTheme { App(repo, screenSettings, ambient.value, ambientTick.value, burnIn.value, burnInX.value, burnInY.value) } }
    }

    // Wake log (FR10 g): the pace the step page shows, at exit from ambient and 1 s later. Checks outdoors
    // that the first frame after wake is already right.
    private fun logWake(point: String) {
        val s = ExerciseService.session.value
        val pace = if (s == null) "none" else s.speed?.let { Format.pace(it, s.workout.paceUnit) } ?: "--:--"
        Log.i("MainActivity", "WAKE $point pace=$pace step=${s?.stepIndex} paused=${s?.paused}")
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
private fun App(repo: WorkoutRepository, screenSettings: ScreenSettingsStore, ambient: Boolean, ambientTick: Int, burnIn: Boolean, burnX: Int = 0, burnY: Int = 0) {
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
                if (isPacedStep(frozen)) PacedAmbient(frozen, burnX, burnY)
                else AmbientWorkout(frozen, shift = if (burnIn) ambientTick % 3 - 1 else 0)
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
                onPaceWindow = { sport -> scope.launch { screenSettings.update { it.withSport(sport) { p -> p.withPaceWindow(nextPaceWindow(p.paceWindowSec)) } } } },
                onCueRow = { scope.launch { screenSettings.update { it.copy(cueRow = it.cueRow.next()) } } },
                onColourBand = { scope.launch { screenSettings.update { it.copy(colourBand = it.colourBand.next()) } } },
                onBuzzStrength = {
                    val next = if (settings.buzzStrength == BuzzStrength.STRONG) BuzzStrength.NORMAL else BuzzStrength.STRONG
                    playBuzzSample(context, next)
                    scope.launch { screenSettings.update { it.copy(buzzStrength = next) } }
                },
            )
            selected != null && picking -> PowerMeterPickerScreen(onBack = { picking = false })
            selected != null && selected!!.isOpen -> OpenStart(selected!!, onBack = { selected = null })
            selected != null -> WorkoutDetail(selected!!, onPickPower = { picking = true }, onScreens = { editSport = selected!!.sport }, onBack = { selected = null })
            else -> WorkoutList(bundle?.workouts.orEmpty().map { it.withUnits(settings) }, save, phoneLoggedIn, syncedAtMillis = bundle?.syncedAtMillis, onSettings = { settingsOpen = true }) { selected = it }
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
internal fun WorkoutList(workouts: List<Workout>, save: SaveStatus? = null, phoneLoggedIn: Boolean = true, syncedAtMillis: Long? = null, nowMillis: Long = System.currentTimeMillis(), onSettings: (() -> Unit)? = null, clock: String = clockNow(), today: java.time.LocalDate = java.time.LocalDate.now(), onPick: (Workout) -> Unit) {
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
                        val day = workoutWhen(w, today)
                        val rest = listOfNotNull(
                            w.sport.name.lowercase().replaceFirstChar { it.uppercase() } + if (w.indoor) " indoor" else "",
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
            if (syncedAtMillis != null) {
                item("synced") {
                    Text(
                        syncedAgo(syncedAtMillis, nowMillis), style = RbType.label, textAlign = TextAlign.Center,
                        color = if (syncStale(syncedAtMillis, nowMillis)) Rb.work else Rb.ink2,
                    )
                }
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
    // Warm up GPS and HR while this screen is open (ux D1); a Start press hands the sensors to the service.
    val probe = remember { ReadinessProbe(ctx.applicationContext) }
    var started by remember { mutableStateOf(false) }
    val ready by probe.state.collectAsStateWithLifecycle()
    LaunchedEffect(w.id) { probe.start(w) }
    DisposableEffect(Unit) { onDispose { kotlinx.coroutines.GlobalScope.launch { probe.stop(started) } } }
    val scope = rememberCoroutineScope()
    DetailBezel(
        w, ready = ready,
        // T24 L3: release the probe's callback before the permission prompt hands over to the service.
        onStart = { started = true; scope.launch { probe.handOff(); launcher.launch(perms) } },
        onSteps = { listing = true },
    )
}

/** The workout ring is the bezel; the crown scrubs its highlight and the line under Start describes that step (ux D1, D3). */
@Composable
internal fun DetailBezel(w: Workout, selected: Int = 0, ready: Readiness = Readiness(), onStart: () -> Unit, onSteps: () -> Unit) {
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
        // Readiness row (ux D1): grey = searching, green = ready. Start stays enabled. GPS only matters outdoors.
        CurvedLayout(Modifier.padding(11.dp), anchor = 90f, angularDirection = CurvedDirection.Angular.CounterClockwise) {
            val dir = CurvedDirection.Angular.CounterClockwise
            if (!w.indoor) {
                curvedText("GPS ", color = Rb.ink3, fontSize = RbType.edge, fontWeight = FontWeight.Medium, angularDirection = dir)
                curvedText("●  ", color = if (ready.gps) Rb.recovery else Rb.ink3, fontSize = RbType.edge, angularDirection = dir)
            }
            curvedText("HR ", color = Rb.ink3, fontSize = RbType.edge, fontWeight = FontWeight.Medium, angularDirection = dir)
            curvedText("●", color = if (ready.hr) Rb.recovery else Rb.ink3, fontSize = RbType.edge, angularDirection = dir)
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
        val rows = collapsedSteps(w)
        items(rows.size) { i -> StepRow(rows[i]) }
        notesPreview(w.description)?.let { notes ->
            item("notes") {
                var more by remember { androidx.compose.runtime.mutableStateOf(false) }
                // T24 L2: "more" follows what the 3 lines actually cut, not only the 120-char cap.
                var clipped by remember { androidx.compose.runtime.mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { more = !more }) {
                    Text("Notes", style = RbType.label, color = Rb.ink2)
                    Text(
                        if (more) w.description!!.trim() else notes, fontSize = 12.sp, color = Rb.ink,
                        maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { clipped = it.hasVisualOverflow },
                    )
                    if (!more && (clipped || notes.endsWith("…"))) Text("more", style = RbType.label, color = Rb.work)
                }
            }
        }
        item {
            CompactButton(onClick = onClose, colors = ButtonDefaults.secondaryButtonColors()) { Text("←") }
        }
    }
}

@Composable
private fun StepRow(row: StepRowData) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.label, color = kindColor(row.kind), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.detail, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
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
 * The line between the target and the minutes left (FR10 e): the step's average pace, labelled "avg" because the
 * ambient screen is only redrawn about once a minute; PAUSED while paused; null when there is no average yet.
 */
internal fun ambientAvgLine(s: Session): String? = when {
    s.paused -> "PAUSED"
    s.workout.sport == Sport.RIDE -> s.stepAvgSpeed?.let { if (s.workout.paceUnit == PaceUnit.KM) "avg %.1f km/h".format(it * 3.6) else "avg %.1f mph".format(it * 3600 / 1609.344) }
    else -> s.stepAvgSpeed?.let { "avg ${Format.pace(it, s.workout.paceUnit)} ${Format.paceSuffix(s.workout.paceUnit)}" }
}

/** Splits "4:39–4:25 /km" before the unit so the ambient target never wraps inside a number. */
internal fun ambientTargetParts(text: String): Pair<String, String?> {
    val i = text.lastIndexOf(' ')
    return if (i < 0) text to null else text.substring(0, i) to text.substring(i + 1)
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
                    val (range, unit) = ambientTargetParts(Format.target(it, p.fraction, w.paceUnit, w.sport))
                    Text(range, color = Rb.ink, fontSize = if (range.length > 7) 26.sp else 32.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                    unit?.let { u -> Text(u, color = Rb.ink2, fontSize = 16.sp, fontWeight = FontWeight.Light) }
                }
                ambientAvgLine(s)?.let { Text(it, color = Rb.ink2, fontSize = 18.sp, fontWeight = FontWeight.Light) }
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
                SaveStatus.Saving -> Text("⟳ Saving…", style = RbType.label, color = Rb.ink2)
                SaveStatus.Saved -> Text("✓ Saved", style = RbType.body, color = Rb.ink, fontWeight = FontWeight.Bold)
                is SaveStatus.Failed -> Text(
                    "! ${save.reason}".takeUnless { save.reason == "Nothing recorded" } ?: save.reason, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3,
                    color = if (save.reason == "Nothing recorded") Rb.ink3 else Rb.signal,
                )
                null -> Unit
            }
            if (work.isNotEmpty()) Text("$hits of ${work.size} reps on target", style = RbType.label, color = Rb.work, maxLines = 1)
            s.workInTargetPct?.let { Text("Work in target $it%", style = RbType.label, color = Rb.ink2, maxLines = 1) }
            Text(
                listOfNotNull(Format.duration(s.activeSec), s.distanceM.takeIf { it > 0 }?.let { Format.distance(it, w.paceUnit) }).joinToString(" · "),
                style = RbType.partner, color = Rb.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
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

/** Average heart rate and the whole-session average pace (run, walk), or speed and power (ride); null parts when there's no data. */
internal fun summaryDetail(s: Session): Pair<String?, String?>? {
    val hr = s.avgHeartRate?.let { "%.0f".format(it) }
    val v = if (s.distanceM > 0 && s.activeSec > 0) s.distanceM / s.activeSec else null
    val second = if (s.workout.sport == Sport.RIDE) {
        val speed = v?.let { if (s.workout.paceUnit == PaceUnit.KM) "%.1f km/h".format(it * 3.6) else "%.1f mph".format(it * 3600 / 1609.344) }
        listOfNotNull(s.avgPower?.let { "${it.roundToInt()} W" }, speed).joinToString(" · ").ifEmpty { null }
    } else v?.let { "${Format.pace(it, s.workout.paceUnit)} ${Format.paceSuffix(s.workout.paceUnit)}" }
    return if (hr == null && second == null) null else hr to second
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
