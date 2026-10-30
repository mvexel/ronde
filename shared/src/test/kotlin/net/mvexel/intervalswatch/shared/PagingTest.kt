package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagingTest {
    @Test fun meanIsNullUntilFirstReading() {
        val m = RunningMean()
        assertNull(m.value)
        m.add(150.0); m.add(160.0); m.add(170.0)
        assertEquals(160.0, m.value!!, 1e-9)
        m.clear()
        assertNull(m.value)
    }

    @Test fun oneDetentIsNotDeliberate() {
        val p = CrownPager()
        assertEquals(0, p.turn(1f, 0))
        assertEquals(1, p.turn(1f, 100))
    }

    @Test fun turningBackCancelsTheCount() {
        val p = CrownPager()
        assertEquals(0, p.turn(1f, 0))
        assertEquals(0, p.turn(-1f, 50))
        assertEquals(0, p.turn(1f, 100))
        assertEquals(1, p.turn(1f, 150))
    }

    @Test fun slowTurnsDontAccumulate() {
        val p = CrownPager()
        assertEquals(0, p.turn(1f, 0))
        assertEquals(0, p.turn(1f, 5000))
    }

    @Test fun backwardsTurnPagesBackwards() {
        val p = CrownPager()
        assertEquals(-1, p.turn(-2f, 0))
    }

    @Test fun pagesFollowSettings() {
        val s = SportSettings.RUN_DEFAULT
        assertEquals(listOf(WatchPage.STEP, WatchPage.TOTALS, WatchPage.RING), pagesFor(s))
        assertEquals(listOf(WatchPage.STEP, WatchPage.RING), pagesFor(s.copy(totalsPage = false)))
        assertEquals(listOf(WatchPage.STEP), pagesFor(s.copy(totalsPage = false, workoutRingPage = false)))
    }

    @Test fun pagingStopsAtTheEnds() {
        val pages = listOf(WatchPage.STEP, WatchPage.TOTALS, WatchPage.RING)
        assertEquals(WatchPage.TOTALS, movePage(pages, WatchPage.STEP, 1))
        assertEquals(WatchPage.RING, movePage(pages, WatchPage.RING, 1))
        assertEquals(WatchPage.STEP, movePage(pages, WatchPage.STEP, -1))
        // A page that was turned off falls back to the first.
        assertEquals(WatchPage.STEP, movePage(listOf(WatchPage.STEP), WatchPage.RING, 0))
    }
}
