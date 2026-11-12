package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.WorkoutStep
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the rest screen needs from the rep that just ended (v5 section 6, REST): its band and its average. */
data class RepResult(val fromStep: Int, val range: PaceRange, val avgSpeed: Double?)

internal enum class PacedMode { PACED, SHORT, REST }

/** REST result is shown for about this long (seconds into the rest step). */
internal const val REST_RESULT_SEC = 20.0

/**
 * The one predicate for "this step is judged and drawn as paced": a pace target with at least one edge on a run, walk or
 * other step that does not end on a lap press. The service's judge and the page both use it, so they cannot disagree.
 */
internal fun isPacedStep(sport: Sport, step: WorkoutStep?): Boolean =
    step != null && sport != Sport.RIDE && !step.endsOnLap && stepRange(step, 0.0) != null

internal fun isPacedStep(s: Session): Boolean = isPacedStep(s.workout.sport, s.step)

/** Step length the judge window and SHORT mode are chosen by: the timed length, or a distance step's length at the range centre pace. */
internal fun judgeStepLenSec(step: WorkoutStep, range: PaceRange?): Double? {
    step.durationSec?.let { return it.toDouble() }
    val dist = step.distanceM ?: return null
    val centre = range?.let { r -> listOfNotNull(r.fastSKm, r.slowSKm).average() }?.takeIf { !it.isNaN() } ?: return null
    return dist / 1000.0 * centre
}

/** Under 60 s: SHORT mode (no judgement, no buzz). Shared by the judge input and the page. */
internal fun isShortStep(step: WorkoutStep, range: PaceRange?): Boolean =
    judgeStepLenSec(step, range)?.let { it < JudgeEngine.MIN_JUDGED_LEN } ?: false

/** The rest result screen: a rest step right after a rep with a pace band, for its first [REST_RESULT_SEC] seconds. */
internal fun isRestResult(s: Session): Boolean {
    val step = s.step ?: return false
    val rep = s.lastRep ?: return false
    return step.kind == StepKind.REST && rep.fromStep == s.stepIndex - 1 && s.progress.elapsedSec < REST_RESULT_SEC
}

/** Whether the Step page draws the paced layout (a paced step, or the rest result after one). */
internal fun usesPacedPage(s: Session): Boolean = isPacedStep(s) || isRestResult(s)

/** The pace band of [step] at [fraction]; null when it has none, or when neither edge is a real edge (nothing to draw or judge). */
internal fun stepRange(step: WorkoutStep, fraction: Double): PaceRange? =
    step.primaryTarget?.takeIf { it.metric == Metric.PACE }?.rangeAt(fraction)
        ?.let { PaceRange.fromSpeeds(it.start, it.endInclusive) }?.takeIf { it.fastSKm != null || it.slowSKm != null }

internal fun pacedMode(s: Session): PacedMode {
    val step = s.step ?: return PacedMode.PACED
    if (isRestResult(s)) return PacedMode.REST
    if (isShortStep(step, stepRange(step, s.progress.fraction))) return PacedMode.SHORT
    return PacedMode.PACED
}

/** Top-edge label: "2/3 TEMPO", at most 11 characters; longer drops the counter, then truncates with an ellipsis. */
internal fun stepLabelText(step: WorkoutStep): String {
    val name = step.label.uppercase()
    val full = listOfNotNull(step.repeat, name).joinToString(" ")
    if (full.length <= 11) return full
    return if (name.length <= 11) name else name.take(10) + "…"
}

private fun perUnit(sKm: Double, unit: PaceUnit) = if (unit == PaceUnit.MILE) sKm * 1.609344 else sKm

/** A pace given in s/km, as m:ss in the workout's unit. */
internal fun paceText(sKm: Double, unit: PaceUnit): String = Format.pace(1000.0 / sKm, unit)

/** Printed range, slow to fast with an en dash, no unit (the hero carries it): "5:00–4:40". One-sided: null. */
internal fun rangeText(r: PaceRange, unit: PaceUnit): String? {
    val f = r.fastSKm ?: return null
    val s = r.slowSKm ?: return null
    val a = paceText(s, unit)
    val b = paceText(f, unit)
    return if (a == b) a else "$a–$b"
}

/** Row-4 text for a one-sided target: "SLOWER" with a cap on speed, "FASTER" with a floor, plus the edge pace. */
internal fun oneSidedText(r: PaceRange, unit: PaceUnit): Pair<String, String>? = when {
    r.fastSKm != null && r.slowSKm == null -> "SLOWER" to paceText(r.fastSKm, unit)
    r.slowSKm != null && r.fastSKm == null -> "FASTER" to paceText(r.slowSKm, unit)
    else -> null
}

/** Whole seconds per km (or mile) past the edge, `99+` at the cap. */
internal fun deltaText(beyondSKm: Double, unit: PaceUnit): String {
    val v = perUnit(beyondSKm, unit).roundToInt()
    return if (v > 99) "99+" else v.toString()
}

internal fun deltaUnit(unit: PaceUnit) = if (unit == PaceUnit.MILE) "s/mi" else "s/km"

/** The verdict spoken in words, whatever the cue row setting is. */
internal fun verdictSpeech(side: Side, beyondSKm: Double, unit: PaceUnit): String? {
    if (side == Side.NONE) return null
    val n = deltaText(beyondSKm, unit).replace("+", " or more")
    val per = if (unit == PaceUnit.MILE) "mile" else "kilometre"
    return (if (side == Side.FAST) "Too fast" else "Too slow") + " by $n seconds per $per"
}

/** Whole-rep verdict for the rest screen: one value against the snapped band, no dwell. */
internal fun restVerdict(range: PaceRange, avgSKm: Double): Pair<Side, Double> {
    val snapped = Grid.snapRange(range)
    val shown = Grid.snap(avgSKm, snapped.gridSKm)
    val side = JudgeEngine.sideOf(shown, snapped)
    val edge = when (side) { Side.FAST -> snapped.fastSKm!!; Side.SLOW -> snapped.slowSKm!!; else -> return Side.NONE to 0.0 }
    return side to abs(shown - edge)
}

/** The text the TalkBack description reads for the whole page's state. */
internal fun pacedSpeech(state: JudgeState, side: Side, beyondSKm: Double, unit: PaceUnit): String = when (state) {
    JudgeState.OUT -> verdictSpeech(side, beyondSKm, unit) ?: "Off pace"
    JudgeState.NEAR -> "Near the edge of the target range"
    JudgeState.IN -> "On pace"
    JudgeState.SETTLING -> "Settling"
    JudgeState.WEAK -> "Weak GPS"
    JudgeState.GPS_WAIT -> "Waiting for GPS"
    JudgeState.PAUSED -> "Paused"
    JudgeState.SHORT -> "Short step"
}

/** Distinct haptic per judged side: EASE (too fast) is one long pulse, PUSH (too slow) a flutter. */
internal fun judgeBuzzPattern(side: Side): LongArray? = when (side) {
    Side.FAST -> Haptics.TOO_HIGH
    Side.SLOW -> Haptics.TOO_LOW
    Side.NONE -> null
}
