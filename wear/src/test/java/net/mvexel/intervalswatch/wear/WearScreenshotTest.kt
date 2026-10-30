package net.mvexel.intervalswatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.wear.compose.material.MaterialTheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.SaveStatus
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.shared.Workout
import org.junit.Rule
import org.junit.Test

/** Run `./gradlew :wear:recordPaparazziDebug`; PNGs land in wear/src/test/snapshots/images. */
class WearScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.WEAR_OS_SMALL_ROUND,
        theme = "android:Theme.DeviceDefault",
        maxPercentDifference = 0.1,
    )

    // Render via a ComposeView with a time offset: ScalingLazyColumn only shows its items after
    // the first frame, so a zero-offset snapshot comes out blank.
    private fun snap(content: @Composable () -> Unit) {
        val view = ComposeView(paparazzi.context).apply { setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) { MaterialTheme { content() } }
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

    @Test fun rideInRange() = snap {
        ActiveWorkout(session(Samples.ride, 3, 312.0, power = 307.0, hr = 162.0, speed = 9.1))
    }

    @Test fun rideTooLow() = snap {
        ActiveWorkout(session(Samples.ride, 5, 205.0, power = 281.0, hr = 158.0, speed = 8.7))
    }

    @Test fun runTooFast() = snap {
        ActiveWorkout(session(Samples.run, 1, 431.0, hr = 171.0, speed = 3.95, stepAvg = 3.8))
    }

    @Test fun runDistanceStep() = snap {
        ActiveWorkout(session(Samples.run, 0, null, remainingM = 1240.0, hr = 138.0, speed = 3.02))
    }

    @Test fun runNextNearEnd() = snap {
        ActiveWorkout(session(Samples.run, 1, 8.0, hr = 171.0, speed = 3.2))
    }

    @Test fun runGpsWaiting() = snap {
        ActiveWorkout(session(Samples.run, 1, 300.0, hr = 120.0).copy(gpsReady = false))
    }

    @Test fun rideNoPower() = snap {
        ActiveWorkout(session(Samples.ride, 3, 312.0, hr = 140.0).copy(powerStatus = PowerMeter.Status.DISCONNECTED))
    }

    @Test fun ambient() = snap {
        AmbientWorkout(session(Samples.ride, 3, 312.0, power = 307.0, hr = 162.0, speed = 9.1))
    }

    @Test fun paused() = snap {
        ActiveWorkout(session(Samples.ride, 3, 312.0, power = 0.0, hr = 140.0, paused = true))
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
