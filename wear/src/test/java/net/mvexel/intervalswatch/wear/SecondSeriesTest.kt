package net.mvexel.intervalswatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondSeriesTest {
    @Test fun aLateBatchLandsInItsTrueSeconds() {
        val s = SecondSeries()
        // a batch delivered at wall second 100 carrying points stamped 94.3 ... 99.3: each is read back at its own second
        for (i in 0..5) s.add(94.3 + i, 3.0 + i * 0.1)
        for (i in 0..5) assertEquals(3.0 + i * 0.1, s.at(94L + i)!!, 1e-9)
        assertNull(s.at(93)); assertNull(s.at(100))
    }

    @Test fun pointsInOneSecondAverageAndGapsStayGaps() {
        val s = SecondSeries()
        s.add(10.1, 3.0); s.add(10.9, 4.0); s.add(12.5, 5.0)
        assertEquals(3.5, s.at(10)!!, 1e-9)
        assertNull("no value is carried into a second without a point", s.at(11))
        assertEquals(5.0, s.at(12)!!, 1e-9)
        s.clear(); assertNull(s.at(10))
    }

    @Test fun oldSecondsAreDropped() {
        val s = SecondSeries(keepSec = 10)
        for (i in 0..100) s.add(i.toDouble(), 1.0)
        assertNull(s.at(0)); assertEquals(1.0, s.at(100)!!, 0.0)
    }
}
