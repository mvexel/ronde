package net.mvexel.intervalswatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceSmootherTest {
    @Test fun noDataIsNull() = assertNull(PaceSmoother().at(10.0, 20.0))

    @Test fun shortHistoryIsMeanOfWhatThereIs() {
        val s = PaceSmoother()
        s.add(0.0, 3.0); s.add(2.0, 5.0)
        assertEquals(4.0, s.at(4.0, 20.0)!!, 1e-9)
    }

    @Test fun windowDropsOldSamplesAndWeightsByTime() {
        for (w in listOf(15.0, 20.0, 30.0)) {
            val s = PaceSmoother()
            s.add(0.0, 10.0)
            s.add(100.0, 2.0); s.add(105.0, 4.0)
            // last w seconds before t=110: 2.0 held for 5 s, 4.0 for 5 s... plus older 10.0 not counted
            s.add(110.0, 6.0)
            val now = 112.0
            val from = now - w
            // segments (value, start, end), each clipped to the window and held at most 3 s
            val segs = listOf(Triple(10.0, 0.0, 100.0), Triple(2.0, 100.0, 105.0), Triple(4.0, 105.0, 110.0), Triple(6.0, 110.0, now))
            var sum = 0.0; var len = 0.0
            for ((v, st, en) in segs) { val l = minOf(en, st + 3.0) - maxOf(st, from); if (l > 0) { sum += v * l; len += l } }
            assertEquals(sum / len, s.at(now, w)!!, 1e-9)
        }
    }

    @Test fun zeroSpeedCounts() {
        val s = PaceSmoother()
        for (t in 0..9) s.add(t.toDouble(), 4.0)
        for (t in 10..19) s.add(t.toDouble(), 0.0)
        assertEquals(2.0, s.at(20.0, 20.0)!!, 1e-9)
    }

    @Test fun lateBatchLandsAtTrueTimes() {
        val s = PaceSmoother()
        for (t in 0..9) s.add(t.toDouble(), 4.0)
        for (t in 10..19) s.add(t.toDouble(), 2.0)
        assertEquals(3.0, s.at(20.0, 20.0)!!, 1e-9)
    }

    @Test fun staleIsNull() {
        val s = PaceSmoother(staleSec = 15.0)
        s.add(0.0, 3.0)
        assertNull(s.at(16.0, 20.0))
    }

    @Test fun pauseGapDoesNotDragPaceAfterResume() {
        val s = PaceSmoother()
        for (t in 0..9) s.add(t.toDouble(), 0.5)   // decelerating into the pause
        s.add(70.0, 4.0); s.add(71.0, 4.0); s.add(72.0, 4.0)
        // window 80 s at t=73: the 61 s gap counts for only 3 s of the old sample (9 s + 3 s at 0.5, 3 s at 4.0)
        val v = s.at(73.0, 80.0)!!
        assertEquals((0.5 * 12 + 4.0 * 3) / 15, v, 1e-9)
    }
}
