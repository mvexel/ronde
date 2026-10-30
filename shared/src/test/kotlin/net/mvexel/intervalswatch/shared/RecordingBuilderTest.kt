package net.mvexel.intervalswatch.shared

import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingBuilderTest {
    private fun builder() = RecordingBuilder("w", Sport.RUN, true, 0L)

    @Test fun lateSampleSlotsInByTime() {
        val b = builder()
        b.add(RecSample(t = 10.2, distanceM = 30.0, power = 200))
        b.add(RecSample(t = 8.5, distanceM = 20.0, hr = 140))
        assertEquals(listOf(8L, 10L), b.build(11.0, 30.0).samples.map { it.t.toLong() })
    }

    @Test fun sameSecondFillsGaps() {
        val b = builder()
        b.add(RecSample(t = 5.1, distanceM = 10.0, power = 200))
        b.add(RecSample(t = 5.7, distanceM = 11.0, hr = 150, lat = 1.0, lon = 2.0))
        val s = b.build(6.0, 11.0).samples.single()
        assertEquals(200, s.power)
        assertEquals(150, s.hr)
        assertEquals(1.0, s.lat)
    }
}
