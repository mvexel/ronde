package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Field
import net.mvexel.intervalswatch.shared.PaceUnit
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.StepProgress
import net.mvexel.intervalswatch.wear.ui.gaugeFraction
import net.mvexel.intervalswatch.wear.ui.gaugeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StepPageTest {
    private fun session(sport: Sport, unit: PaceUnit) = Session(
        workout = (if (sport == Sport.RIDE) Samples.ride else Samples.run).copy(sport = sport, paceUnit = unit),
        stepIndex = 1, step = Samples.run.steps[1], next = null,
        progress = StepProgress(60.0, 300.0, 300.0, null, 0.3),
        activeSec = 600.0, distanceM = 2000.0, heartRate = 150.0, speed = 3.7, power = null, cadence = 80.0,
        powerStatus = null, gpsReady = true, paused = false, finished = false, ended = false, stepAvgSpeed = 3.6,
    )

    @Test fun runAndWalkNeverShowKmhOrMph() {
        for (sport in listOf(Sport.RUN, Sport.WALK)) for (unit in PaceUnit.entries) {
            val s = session(sport, unit)
            for (f in Field.entries) {
                val shown = (fieldValue(s, f, "14:05") ?: "") + " " + fieldLabel(s, f)
                if (f != Field.SPEED) assertFalse("$sport $f: $shown", "km/h" in shown || "mph" in shown)
            }
        }
    }

    @Test fun gaugeSpanAddsHalfARangeEachSide() {
        val span = gaugeSpan(3.5..3.9)
        assertEquals(3.3, span.start, 1e-9); assertEquals(4.1, span.endInclusive, 1e-9)
    }

    @Test fun gaugeFractionClampsAndOrders() {
        assertEquals(0.25f, gaugeFraction(3.5, 3.3, 4.1), 1e-6f)
        assertEquals(0f, gaugeFraction(2.0, 3.3, 4.1), 0f)
        assertEquals(1f, gaugeFraction(9.0, 3.3, 4.1), 0f)
    }
}
