package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Metric
import net.mvexel.intervalswatch.shared.StepProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    @Test fun cueWords() {
        assertEquals("▲ faster", cue(Metric.PACE, -1))
        assertEquals("▼ slower", cue(Metric.PACE, 1))
        assertEquals("▲ up", cue(Metric.POWER, -1))
        assertEquals("▼ ease", cue(Metric.HEART_RATE, 1))
        assertNull(cue(Metric.POWER, 0))
        assertNull(cue(Metric.POWER, null))
    }

    @Test fun nextOnlyNearEnd() {
        assertTrue(isNearEnd(StepProgress(0.0, 10.0, 60.0, null, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, 11.0, 60.0, null, 0.9)))
        assertTrue(isNearEnd(StepProgress(0.0, null, 60.0, 100.0, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, null, 60.0, 400.0, 0.9)))
        assertFalse(isNearEnd(StepProgress(0.0, null, 0.0, null, 0.0)))
    }

    @Test fun stepAverage() {
        assertNull(stepAvgSpeed(30.0, 5.0))
        assertNull(stepAvgSpeed(0.0, 60.0))
        assertEquals(4.0, stepAvgSpeed(240.0, 60.0)!!, 1e-9)
    }

    @Test fun powerText() {
        assertEquals("Power lost", powerStatusText(PowerMeter.Status.DISCONNECTED))
        assertEquals("No power", powerStatusText(PowerMeter.Status.NOT_FOUND))
    }
}
