package net.mvexel.intervalswatch.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import net.mvexel.intervalswatch.shared.Availability
import net.mvexel.intervalswatch.shared.Field
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.resolveSlots
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.HEART
import net.mvexel.intervalswatch.wear.ui.HeartText
import net.mvexel.intervalswatch.wear.ui.RangeGauge
import net.mvexel.intervalswatch.wear.ui.RbType
import net.mvexel.intervalswatch.wear.ui.kindColor
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("H:mm")

internal fun clockNow(): String = LocalTime.now().format(clockFormat)

/** Which data this session has: no GPS indoors, no power without a power target, no cadence on a ride (RF01). */
internal fun Session.availability() = Availability(
    gps = !workout.indoor,
    power = workout.usesPower,
    heartRate = true,
    cadence = workout.sport != Sport.RIDE,
)

/** What is left of the whole workout, in seconds; null when no step is timed. Distance and lap steps add nothing. */
internal fun workoutLeftSec(s: Session): Double? {
    val steps = s.workout.steps
    if (s.step == null) return null
    val current = s.progress.remainingSec ?: 0.0
    val later = steps.drop(s.stepIndex + 1).sumOf { (it.durationSec ?: 0).toDouble() }
    return (current + later).takeIf { it > 0 }
}

/** Whole-session average speed in m/s (distance over active time); null until it means something. */
internal fun averageSpeed(s: Session): Double? =
    if (s.activeSec < 10.0 || s.distanceM <= 0.0) null else s.distanceM / s.activeSec

/** m:ss up to 59:59, then h:mm: at most 5 characters, so it fits a flank (settings.md §2). */
internal fun totalTimeText(sec: Double): String {
    val t = sec.toInt().coerceAtLeast(0)
    return if (t < 3600) "%d:%02d".format(t / 60, t % 60) else "%d:%02d".format(t / 3600, (t % 3600) / 60)
}

private fun speedText(mps: Double, unit: PaceUnit): String =
    if (unit == PaceUnit.MILE) "%.1f".format(mps * 2.23694) else "%.1f".format(mps * 3.6)

/** What this step has left: time, distance, or "+m:ss" counting up on a lap step. */
internal fun timeLeftText(s: Session): String {
    val p = s.progress
    return when {
        p.remainingSec != null -> Format.duration(p.remainingSec!!)
        p.remainingM != null -> Format.distance(p.remainingM!!, s.workout.paceUnit)
        else -> "+" + Format.duration(p.elapsedSec)
    }
}

/** The number for [field], or null when it has none (yet). */
internal fun fieldValue(s: Session, field: Field, clock: String): String? {
    val unit = s.workout.paceUnit
    return when (field) {
        Field.HEART_RATE -> s.heartRate?.let { "${it.toInt()}" }
        Field.PACE -> s.speed?.let { Format.pace(it, unit) }
        Field.STEP_AVG_PACE -> s.stepAvgSpeed?.let { Format.pace(it, unit) }
        Field.SPEED -> s.speed?.let { speedText(it, unit) }
        Field.POWER -> s.power?.let { "${it.toInt()}" }
        Field.CADENCE -> s.cadence?.let { "${it.toInt()}" }
        Field.DISTANCE -> if (unit == PaceUnit.MILE) "%.2f".format(s.distanceM / 1609.344) else "%.2f".format(s.distanceM / 1000)
        Field.TOTAL_TIME -> totalTimeText(s.activeSec)
        Field.CLOCK -> clock
        Field.STEP_TIME -> totalTimeText(s.progress.elapsedSec)
        Field.WORKOUT_LEFT -> workoutLeftSec(s)?.let { (if (s.workout.steps.any { st -> st.durationSec == null }) "~" else "") + totalTimeText(it) }
        Field.AVG_HR -> s.avgHeartRate?.let { "${it.toInt()}" }
        Field.AVG_PACE -> averageSpeed(s)?.let { Format.pace(it, unit) }
        Field.AVG_POWER -> s.avgPower?.let { "${it.toInt()}" }
        Field.TIME_LEFT -> timeLeftText(s)
    }
}

/** The small label or unit that goes with [field]'s number. */
internal fun fieldLabel(s: Session, field: Field): String {
    val unit = s.workout.paceUnit
    return when (field) {
        Field.HEART_RATE -> "♥"
        Field.PACE -> Format.paceSuffix(unit)
        Field.STEP_AVG_PACE -> "avg"
        Field.SPEED -> if (unit == PaceUnit.MILE) "mph" else "km/h"
        Field.POWER -> "W"
        Field.CADENCE -> "cad"
        Field.DISTANCE -> if (unit == PaceUnit.MILE) "mi" else "km"
        Field.STEP_TIME -> "step"
        Field.WORKOUT_LEFT -> "to go"
        Field.AVG_HR -> "avg ♥"
        Field.AVG_PACE, Field.AVG_POWER -> "avg"
        Field.TOTAL_TIME, Field.CLOCK, Field.TIME_LEFT -> ""
    }
}

/** Why [field] has no number yet ("GPS…", "No power"), instead of "--". */
internal fun fieldWaiting(s: Session, field: Field): String = when (field) {
    Field.PACE, Field.STEP_AVG_PACE, Field.AVG_PACE, Field.SPEED -> if (!s.workout.indoor && !s.gpsReady) "GPS…" else "--"
    Field.POWER -> powerStatusText(s.powerStatus)
    else -> "--"
}

private fun fieldText(s: Session, field: Field, clock: String): String = fieldValue(s, field, clock) ?: fieldWaiting(s, field)

/** The Step page: target-led dial (settings.md §2, visual-design.md §5.3). Data only: the controls are on the page to its left (ControlsPage.kt), except Lap on lap-ended steps. */
@Composable
internal fun ActiveWorkout(
    s: Session,
    settings: ScreenSettings = ScreenSettings(),
    clock: String = clockNow(),
) {
    // Paced steps (a pace range on a run, walk or other step) have their own page: PacedStepPage.kt.
    if (usesPacedPage(s)) { PacedStepPage(s, settings, clock); return }
    val ctx = LocalContext.current
    val w = s.workout
    val step = s.step
    val p = s.progress

    Box(
        Modifier.fillMaxSize().background(Rb.ground),
        contentAlignment = Alignment.Center,
    ) {
        if (step == null) {
            ProgressArc(p.fraction.toFloat(), Rb.ink3)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Workout done", style = MaterialTheme.typography.title2)
                Text(Format.duration(s.activeSec))
                Text("Hold ■ to end", fontSize = 11.sp, color = Rb.ink3)
                HoldToEnd { ExerciseService.end(ctx) }
            }
            return@Box
        }

        ProgressArc(p.fraction.toFloat(), if (s.paused) Rb.ink3 else kindColor(step.kind), dashed = s.paused)

        val slots = resolveSlots(step, w.sport, s.availability(), settings)
        val nextNear = !s.paused && isNearEnd(p) && s.next != null
        val lapAlways = step.endsOnLap && s.next != null && !s.paused
        val showControls = lapAlways
        val numbers = if (s.paused) 0.5f else 1f
        val window = settings.forSport(w.sport).paceWindowSec

        // Top edge: "2/4 TEMPO" in the kind colour, or the PAUSED pill.
        CurvedLayout(anchor = 270f) {
            if (s.paused) {
                curvedText("PAUSED", color = Rb.ground, fontSize = RbType.edge, fontWeight = FontWeight.Bold, background = Rb.ink)
            } else {
                curvedText(
                    listOfNotNull(step.repeat, step.label.uppercase()).joinToString(" · "),
                    color = kindColor(step.kind), fontSize = RbType.edge, fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            Modifier.fillMaxSize().padding(top = 26.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.alpha(numbers), horizontalAlignment = Alignment.CenterHorizontally) {
                // Partner: hidden (not removed) while the Lap button is up, so nothing shifts.
                Box(Modifier.alpha(if (showControls) 0f else 1f)) {
                    Text(fieldText(s, slots.partner, clock), style = RbType.partner, color = Rb.ink, maxLines = 1)
                }
                Hero(s, slots.hero, clock)
                Gauge(s, slots.hero)
            }
            // Lower half: target line and flanks, or the controls over them.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.alpha(if (showControls) 0f else if (s.paused) 0.5f else 1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TargetLine(s, nextNear)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Flank(s, slots.flankLeft, clock, window)
                        Flank(s, slots.flankRight, clock, window)
                    }
                }
                if (showControls) LapButton()
            }
        }

        // Bottom edge: one user field, ink2.
        val bottomValue = fieldText(s, slots.bottomEdge, clock)
        val bottomLabel = fieldLabel(s, slots.bottomEdge).let { if (slots.bottomEdge == Field.TOTAL_TIME) "total" else it }
        CurvedLayout(anchor = 90f, angularDirection = CurvedDirection.Angular.CounterClockwise) {
            curvedText(
                listOf(bottomValue, bottomLabel.replace(HEART, "HR")).filter { it.isNotEmpty() }.joinToString(" "),
                color = Rb.ink2, fontSize = RbType.edge, fontWeight = FontWeight.Medium,
                angularDirection = CurvedDirection.Angular.CounterClockwise,
            )
        }

        s.error?.let {
            Text(it, fontSize = 11.sp, color = Rb.signal, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp))
        }
    }
}

/** The live value of the step's target metric (or time left), with unit and the ease/push cue beside it. */
@Composable
private fun Hero(s: Session, field: Field, clock: String) {
    val target = s.step?.primaryTarget
    val live = field != Field.TIME_LEFT && target != null
    val cmp = if (live) s.live(target!!.metric)?.let { target.compare(it, s.progress.fraction) } else null
    // On target is plain ink; off target is a black-on-signal pill around the value plus the cue word.
    val offTarget = cmp != null && cmp != 0 && !s.paused
    val value = fieldValue(s, field, clock)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            value ?: fieldWaiting(s, field), style = RbType.hero.copy(fontWeight = FontWeight.Bold), maxLines = 1,
            color = when {
                value == null -> Rb.ink3
                offTarget -> Rb.ground
                else -> Rb.ink
            },
            modifier = if (offTarget) Modifier.background(Rb.signal, RoundedCornerShape(50)).padding(horizontal = 8.dp) else Modifier,
        )
        if (live && value != null) {
            Column {
                HeartText(fieldLabel(s, field), RbType.label, Rb.ink2)
                cue(cmp)?.takeIf { !s.paused }?.let { Text(it, style = RbType.label, color = Rb.signal, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Range gauge under a live hero, when the primary target is a range with a live value. */
@Composable
private fun Gauge(s: Session, hero: Field) {
    val target = s.step?.primaryTarget ?: return
    if (hero == Field.TIME_LEFT || s.paused) return
    val value = s.live(target.metric)
    val cmp = value?.let { target.compare(it, s.progress.fraction) }
    val avg = if (target.metric == Metric.PACE) s.stepAvgSpeed else null
    RangeGauge(
        range = target.rangeAt(s.progress.fraction), value = value, stepAvg = avg,
        color = if (cmp == null || cmp == 0) s.step?.let { kindColor(it.kind) } ?: Rb.ink else Rb.signal,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Target range, or "Next: …" in the last 10 s / 100 m. */
@Composable
private fun TargetLine(s: Session, nextNear: Boolean) {
    val step = s.step ?: return
    val next = s.next
    if (nextNear && next != null) {
        Text("Next: ${next.label}", style = RbType.target, color = kindColor(next.kind), maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else step.primaryTarget?.let {
        Text(Format.target(it, s.progress.fraction, s.workout.paceUnit, s.workout.sport), style = RbType.target, color = Rb.ink2, maxLines = 1)
    }
}

/** One user slot on a side of the dial: 11 sp label above an 18 sp value. */
@Composable
private fun Flank(s: Session, field: Field, clock: String, windowSec: Int) {
    // The step average is the number to compare against the gauge: bigger, tinted by range once the step is a window old.
    val promoted = field == Field.STEP_AVG_PACE
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HeartText(fieldLabel(s, field).ifEmpty { " " }, RbType.label, if (promoted) Rb.ink else Rb.ink2)
        val value = fieldValue(s, field, clock)
        val target = s.step?.primaryTarget
        val avgCmp = if (promoted && target?.metric == Metric.PACE && s.progress.elapsedSec >= windowSec) s.stepAvgSpeed?.let { target.compare(it, s.progress.fraction) } else null
        Text(
            value ?: fieldWaiting(s, field), style = if (promoted) RbType.field.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold) else RbType.field,
            color = when { value == null -> Rb.ink3; avgCmp != null && avgCmp != 0 -> Rb.signal; else -> Rb.ink },
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

