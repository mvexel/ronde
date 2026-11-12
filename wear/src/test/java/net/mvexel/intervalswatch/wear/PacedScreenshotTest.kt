package net.mvexel.intervalswatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.shared.ColourBand
import net.mvexel.intervalswatch.shared.CueRow
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.wear.ui.RunbumTheme
import org.junit.Rule
import org.junit.Test

/**
 * Paced step page (v5 build spec states) at 384 and 456 px, each with the release defaults (Number cue, wash off) and, for
 * the alert states, Number + wash on and Words + wash off. Run `./gradlew :wear:recordPaparazziDebug`; PNGs land in
 * wear/src/test/snapshots/images (git-ignored). The test only renders: nothing here compares against the design PNGs.
 */
abstract class PacedScreenshotBase(device: DeviceConfig) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = device, theme = "android:Theme.DeviceDefault", maxPercentDifference = 0.1)

    private fun snap(content: @Composable () -> Unit) {
        val view = ComposeView(paparazzi.context).apply {
            setContent { CompositionLocalProvider(LocalInspectionMode provides true) { RunbumTheme { content() } } }
        }
        paparazzi.snapshot(view, offsetMillis = 1000L)
    }

    // 4:40-5:00 per km
    private val low = 1000.0 / 300.0
    private val high = 1000.0 / 280.0
    private val tempo = WorkoutStep("Tempo", StepKind.ACTIVE, durationSec = 600, targets = listOf(Target(Metric.PACE, low, high)), primary = Metric.PACE, repeat = "2/3")
    private val rest = WorkoutStep("Rest", StepKind.REST, durationSec = 120)
    private val rep30 = tempo.copy(durationSec = 30, label = "Rep", repeat = "3/8")
    private val capped = WorkoutStep("Easy", StepKind.ACTIVE, durationSec = 1200, targets = listOf(Target(Metric.PACE, 0.0, 1000.0 / 330.0)), primary = Metric.PACE)

    private fun workout(vararg steps: WorkoutStep) = Workout(1, "Test", Sport.RUN, startLocal = "2026-11-11T07:00:00", steps = steps.toList())
    private val range = Grid.snapRange(PaceRange.fromSpeeds(low, high))

    private fun judged(
        state: JudgeState, shown: Double?, drawn: Double? = shown, side: Side = Side.NONE, sev: Int = 0, beyond: Double = 0.0, age: Double = 200.0,
        r: SnappedRange? = range,
    ) = JudgeOut(
        state = state, displaySKm = shown, shownSKm = shown, drawnSKm = drawn, judgeMeanSKm = shown, judgeWinSec = 45, sigmaHat = 9.0, marginSKm = 5.0,
        candidate = Side.NONE, fastPath = false, dwellSec = 0, side = side, severity = sev, beyondSKm = beyond, range = r, alertAgeSec = 0,
        buzz = Side.NONE, buzzCount5Min = 0, stepAgeSec = age,
    )

    private fun session(
        w: Workout, index: Int, remaining: Double, judge: JudgeOut?, wash: WashState? = null, paused: Boolean = false, gps: Boolean = true,
        avg: Double? = 1000.0 / 286.0, elapsed: Double = 200.0, lastRep: RepResult? = null,
    ) = Session(
        workout = w, stepIndex = index, step = w.steps.getOrNull(index), next = w.steps.getOrNull(index + 1),
        progress = StepProgress(elapsed, remaining, 300.0, null, 0.3), activeSec = 1834.0, distanceM = 15230.0,
        heartRate = 171.0, speed = 3.5, power = null, cadence = null, powerStatus = null, gpsReady = gps, paused = paused,
        finished = false, ended = false, stepAvgSpeed = avg, judge = judge, wash = wash, lastRep = lastRep,
    )

    private fun page(s: Session, cue: CueRow = CueRow.NUMBER, wash: Boolean = false) = snap {
        ActiveWorkout(s, ScreenSettings(cueRow = cue, colourBand = if (wash) ColourBand.ON else ColourBand.OFF), clock = "14:05")
    }

    private val w = workout(tempo, rest, tempo)
    private val inRange = judged(JudgeState.IN, 290.0)

    @Test fun inRange() = page(session(w, 0, 372.0, inRange))
    @Test fun nearPastTheEdge() = page(session(w, 0, 372.0, judged(JudgeState.NEAR, 270.0, side = Side.FAST, beyond = 10.0)))
    @Test fun fastSlightNumber() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 275.0, side = Side.FAST, sev = 1, beyond = 5.0), WashState(true, Side.FAST, 1)))
    @Test fun fastSlightNumberWash() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 275.0, side = Side.FAST, sev = 1, beyond = 5.0), WashState(true, Side.FAST, 1)), wash = true)
    @Test fun fastSlightWords() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 275.0, side = Side.FAST, sev = 1, beyond = 5.0)), cue = CueRow.WORDS)
    @Test fun fastSlightWordsWash() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 275.0, side = Side.FAST, sev = 1, beyond = 5.0), WashState(true, Side.FAST, 1)), CueRow.WORDS, true)
    @Test fun slowMidNumber() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 310.0, side = Side.SLOW, sev = 2, beyond = 10.0)))
    @Test fun slowMidNumberWash() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 310.0, side = Side.SLOW, sev = 2, beyond = 10.0), WashState(true, Side.SLOW, 2)), wash = true)
    @Test fun fastFarNumber() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 265.0, side = Side.FAST, sev = 3, beyond = 15.0)))
    @Test fun fastFarNumberWash() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 265.0, side = Side.FAST, sev = 3, beyond = 15.0), WashState(true, Side.FAST, 3)), wash = true)
    @Test fun slowFarWordsWash() = page(session(w, 0, 372.0, judged(JudgeState.OUT, 335.0, side = Side.SLOW, sev = 3, beyond = 35.0), WashState(true, Side.SLOW, 3)), CueRow.WORDS, true)
    @Test fun settling() = page(session(w, 0, 585.0, judged(JudgeState.SETTLING, 475.0, age = 8.0), avg = null, elapsed = 8.0))
    // judge output that belongs to the previous step (index 0) on step 1: ignored, the page shows settling with no stale marker or cue
    @Test fun staleJudgeFromThePreviousStepIsIgnored() = page(session(workout(tempo, tempo), 1, 590.0, judged(JudgeState.OUT, 265.0, side = Side.FAST, sev = 3, beyond = 15.0), WashState(true, Side.FAST, 3), elapsed = 3.0), wash = true)
    @Test fun weakGps() = page(session(w, 0, 372.0, judged(JudgeState.WEAK, 290.0)))
    @Test fun gpsWaiting() = page(session(w, 0, 442.0, null, gps = false, avg = null))
    @Test fun paused() = page(session(w, 0, 372.0, inRange, paused = true, avg = null))
    @Test fun shortRepPastTheEdge() = page(session(workout(rep30, rest), 0, 5.0, judged(JudgeState.SHORT, 270.0, beyond = 10.0), avg = 1000.0 / 280.0, elapsed = 25.0))
    @Test fun nextNearTheEnd() = page(session(w, 0, 8.0, inRange))
    @Test fun restInRange() = page(session(w, 1, 52.0, null, avg = null, elapsed = 8.0, lastRep = RepResult(0, PaceRange.fromSpeeds(low, high), 1000.0 / 286.0)))
    @Test fun restOutOfRange() = page(session(w, 1, 52.0, null, avg = null, elapsed = 8.0, lastRep = RepResult(0, PaceRange.fromSpeeds(low, high), 1000.0 / 274.0)))
    @Test fun oneSidedCapExceeded() {
        val cw = workout(capped)
        val r = Grid.snapRange(PaceRange.fromSpeeds(0.0, 1000.0 / 330.0))
        page(session(cw, 0, 900.0, judged(JudgeState.OUT, 320.0, side = Side.FAST, sev = 2, beyond = 10.0, r = r), avg = 1000.0 / 338.0))
    }
    @Test fun ambient() = snap { PacedAmbient(session(w, 0, 372.0, inRange)) }
    @Test fun ambientShifted() = snap { PacedAmbient(session(w, 0, 372.0, inRange, paused = true), 3, -3) }
}

class PacedScreenshot384Test : PacedScreenshotBase(DeviceConfig.WEAR_OS_SMALL_ROUND)
class PacedScreenshot456Test : PacedScreenshotBase(DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 456, screenHeight = 456))

/** LARGE mode: font scale 1.3 selects the large layout (no flanks, labels or HR). */
class PacedScreenshotLargeTest : PacedScreenshotBase(DeviceConfig.WEAR_OS_SMALL_ROUND.copy(fontScale = 1.3f))
