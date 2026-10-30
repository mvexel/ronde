package net.mvexel.intervalswatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedValuesTest {
    @Test fun picksValueFromItsOwnMoment() {
        val v = TimedValues<Int>().apply { add(1.0, 10); add(5.0, 50); add(9.0, 90) }
        assertEquals(10, v.at(4.0, 100.0))
        assertEquals(50, v.at(5.0, 100.0))
        assertEquals(90, v.at(12.0, 100.0))
    }

    @Test fun staleOrEarlyGivesNull() {
        val v = TimedValues<Int>().apply { add(5.0, 50) }
        assertNull(v.at(4.0, 100.0))
        assertNull(v.at(20.0, 10.0))
    }

    @Test fun clearForgetsLastFix() {
        val v = TimedValues<Int>().apply { add(5.0, 50); clear() }
        assertNull(v.at(6.0, 100.0))
    }
}
