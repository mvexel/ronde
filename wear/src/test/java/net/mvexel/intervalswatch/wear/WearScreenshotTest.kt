package net.mvexel.intervalswatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import net.mvexel.intervalswatch.wear.ui.RunbumTheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.SaveStatus
import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Target
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep
import net.mvexel.intervalswatch.shared.StepKind
import org.junit.Rule
import org.junit.Test

/** Run `./gradlew :wear:recordPaparazziDebug`; PNGs land in wear/src/test/snapshots/images. */
abstract class WearScreenshotBase(device: DeviceConfig) {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = device,
        theme = "android:Theme.DeviceDefault",
        maxPercentDifference = 0.1,
    )

    // Render via a ComposeView with a time offset: ScalingLazyColumn only shows its items after
    // the first frame, so a zero-offset snapshot comes out blank.
    private fun snap(content: @Composable () -> Unit) {
        val view = ComposeView(paparazzi.context).apply { setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) { RunbumTheme { content() } }
            } }
        paparazzi.snapshot(view, offsetMillis = 1000L)
    }

    private fun session(
        w: Workout, index: Int, remainingSec: Double?, remainingM: Double? = null,
        power: Double? = null, hr: Double? = null, speed: Double? = null,
        paused: Boolean = false, ended: Boolean = false, stepAvg: Double? = null,
    ) = Session(
        workout = w,
        stepIndex = index,
        step = w.steps.getOrNull(index),
        next = w.steps.getOrNull(index + 1),
        progress = StepProgress(60.0, remainingSec, 300.0, remainingM, 0.3),
        activeSec = 1834.0,
        distanceM = 15230.0,
        heartRate = hr,
        speed = speed,
        power = power,
        cadence = null,
        powerStatus = PowerMeter.Status.CONNECTED,
        gpsReady = true,
        paused = paused,
        finished = index >= w.steps.size,
        ended = ended,
        stepAvgSpeed = stepAvg,
    )

    @Test fun list() = snap { WorkoutList(Samples.workouts) {} }

    @Test fun listWithOpenRow() = snap { WorkoutList(Samples.workouts.take(1)) {} }

    @Test fun listPhoneLoggedOut() = snap { WorkoutList(emptyList(), phoneLoggedIn = false) {} }

    @Test fun rideInRange() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.ride, 3, 312.0, power = 307.0, hr = 162.0, speed = 9.1))
    }

    @Test fun rideTooLow() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.ride, 5, 205.0, power = 281.0, hr = 158.0, speed = 8.7))
    }

    @Test fun runTooFast() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.run, 1, 431.0, hr = 171.0, speed = 3.95, stepAvg = 3.8))
    }

    private val hrRun get() = Samples.run.copy(steps = listOf(
        WorkoutStep("Tempo", StepKind.ACTIVE, durationSec = 600, targets = listOf(Target(Metric.HEART_RATE, 160.0, 170.0)), primary = Metric.HEART_RATE, repeat = "2/4"),
        Samples.run.steps[0],
    ))

    @Test fun runHrStep() = snap {
        ActiveWorkout(clock = "14:05", s = session(hrRun, 0, 431.0, hr = 175.0, speed = 3.4, stepAvg = 3.3))
    }

    @Test fun indoorRide() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.ride.copy(indoor = true), 3, 312.0, power = 307.0, hr = 162.0))
    }

    @Test fun controlsOnTap() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.run, 1, 431.0, hr = 171.0, speed = 3.95, stepAvg = 3.8), controlsInitiallyUp = true)
    }

    @Test fun ambientRunAvg() = snap {
        AmbientWorkout(session(Samples.run, 1, 431.0, hr = 171.0, speed = 3.95, stepAvg = 3.8), paceView = PaceView.AVG)
    }

    @Test fun runDistanceStep() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.run, 0, null, remainingM = 1240.0, hr = 138.0, speed = 3.02))
    }

    @Test fun runNextNearEnd() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.run, 1, 8.0, hr = 171.0, speed = 3.2))
    }

    @Test fun runGpsWaiting() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.run, 1, 300.0, hr = 120.0).copy(gpsReady = false))
    }

    @Test fun rideNoPower() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.ride, 3, 312.0, hr = 140.0).copy(powerStatus = PowerMeter.Status.DISCONNECTED))
    }

    @Test fun ambient() = snap {
        AmbientWorkout(session(Samples.ride, 3, 312.0, power = 307.0, hr = 162.0, speed = 9.1))
    }

    private val openRide get() = Samples.ride.copy(steps = listOf(WorkoutStep("Free ride", StepKind.ACTIVE)))
    private val lapRun get() = Samples.run.copy(steps = listOf(
        WorkoutStep("Warm-up", StepKind.WARMUP), Samples.run.steps[1], Samples.run.steps[0],
    ))

    @Test fun openRide() = snap {
        ActiveWorkout(clock = "14:05", s = session(openRide, 0, null, power = 150.0, hr = 128.0, speed = 8.0))
    }

    @Test fun openRideAmbient() = snap {
        AmbientWorkout(session(openRide, 0, null, power = 150.0, hr = 128.0, speed = 8.0))
    }

    @Test fun lapStep() = snap {
        ActiveWorkout(clock = "14:05", s = session(lapRun, 0, null, hr = 128.0, speed = 3.0))
    }

    @Test fun pausedOpenRide() = snap {
        ActiveWorkout(clock = "14:05", s = session(openRide, 0, null, hr = 128.0, paused = true))
    }

    @Test fun paused() = snap {
        ActiveWorkout(clock = "14:05", s = session(Samples.ride, 3, 312.0, power = 0.0, hr = 140.0, paused = true))
    }

    @Test fun powerMeterPicker() = snap {
        PowerMeterPicker(
            devices = listOf(
                FoundPowerMeter("AA:BB:CC:00:00:01", "Stryd 4F2A"),
                FoundPowerMeter("AA:BB:CC:00:00:02", "Favero Assioma"),
            ),
            scanning = false,
            selectedAddress = "AA:BB:CC:00:00:01",
            selectedName = "Stryd 4F2A",
            onPick = {}, onForget = {}, onRescan = {}, onBack = {},
        )
    }

    @Test fun summary() = snap {
        Summary(session(Samples.ride, Samples.ride.steps.size, null, ended = true), SaveStatus.Saved) {}
    }

    @Test fun summaryFailed() = snap {
        Summary(
            session(Samples.ride, Samples.ride.steps.size, null, ended = true),
            SaveStatus.Failed("Phone not connected. Will send when it is."),
        ) {}
    }

    @Test fun summaryNothingRecorded() = snap {
        Summary(
            session(Samples.ride, 0, null, ended = true),
            SaveStatus.Failed("Nothing recorded"),
        ) {}
    }
}

class WearScreenshotTest : WearScreenshotBase(DeviceConfig.WEAR_OS_SMALL_ROUND)

/** Same screens on a 454 px round watch (about 227 dp, Pixel Watch 45 mm class); Paparazzi has no built-in large round. */
class WearScreenshotLargeTest : WearScreenshotBase(DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 454, screenHeight = 454))
