package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.StepProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientTest {
    private fun session(stepAvg: Double?, paused: Boolean = false, unit: PaceUnit = PaceUnit.KM, ride: Boolean = false) = Session(
        workout = (if (ride) Samples.run.copy(sport = net.mvexel.intervalswatch.shared.Sport.RIDE) else Samples.run).copy(paceUnit = unit),
        stepIndex = 1, step = Samples.run.steps[1], next = null,
        progress = StepProgress(60.0, 300.0, 300.0, null, 0.3),
        activeSec = 600.0, distanceM = 2000.0, heartRate = 150.0, speed = 3.7, power = null, cadence = 80.0,
        powerStatus = null, gpsReady = true, paused = paused, finished = false, ended = false, stepAvgSpeed = stepAvg,
    )

    @Test fun showsStepAveragePaceInMinutesPerKm() {
        // 3.6 m/s is 1000 / 3.6 = 277.8 s = 4:38 per km
        assertEquals("avg 4:38 /km", ambientAvgLine(session(stepAvg = 3.6)))
    }

    @Test fun showsMilesWhenTheWorkoutIsInMiles() {
        assertEquals("avg 7:27 /mi", ambientAvgLine(session(stepAvg = 3.6, unit = PaceUnit.MILE)))
    }

    @Test fun pausedReplacesTheAverage() {
        assertEquals("PAUSED", ambientAvgLine(session(stepAvg = 3.6, paused = true)))
        assertEquals("PAUSED", ambientAvgLine(session(stepAvg = null, paused = true)))
    }

    @Test fun noAverageYetShowsNothing() {
        assertNull(ambientAvgLine(session(stepAvg = null)))
    }

    @Test fun rideShowsSpeed() {
        assertEquals("avg 28.8 km/h", ambientAvgLine(session(stepAvg = 8.0, ride = true)))
        assertEquals("avg 17.9 mph", ambientAvgLine(session(stepAvg = 8.0, unit = PaceUnit.MILE, ride = true)))
    }

    @Test fun targetNeverSplitsInsideANumber() {
        assertEquals("4:39–4:25" to "/km", ambientTargetParts("4:39–4:25 /km"))
        assertEquals("250" to "W", ambientTargetParts("250 W"))
    }
}
