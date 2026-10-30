package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class FitToGpxTest {

    private fun gpx(): String {
        val b = RecordingBuilder("Test & <go>", Sport.RUN, indoor = false, startEpochMs = 1_790_000_000_000L)
        val step = WorkoutStep("W", StepKind.WARMUP, durationSec = 2)
        b.onStep(0, step, 0.0, 0.0)
        b.add(RecSample(0.0, 0.0, 52.1, 5.1, 10.0, 3.0, 120, null, null))
        b.add(RecSample(1.0, 3.0, 52.1001, 5.1001, 10.5, 3.2, null, 200, 85))
        b.add(RecSample(2.0, 6.0, null, null, null, null, 130, null, null))
        return FitToGpx.convert(FitWriter.encode(b.build(3.0, 9.0)), "Test & <go>")
    }

    @Test fun producesWellFormedGpx11() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(gpx().byteInputStream())
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("1.1", doc.documentElement.getAttribute("version"))
        assertEquals("Test & <go>", doc.getElementsByTagName("name").item(0).textContent)
    }

    @Test fun trackPointsCarryPositionTimeAndHeartRate() {
        val g = gpx()
        // the sample without a position is skipped
        assertEquals(2, Regex("<trkpt ").findAll(g).count())
        assertTrue(g.contains("lat=\"52.1000000\" lon=\"5.1000000\""))
        assertTrue(g.contains("<time>2026-09-21T"))
        assertTrue(g.contains("<ele>10.0</ele>"))
        assertTrue(g.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
        // second point has no HR
        val second = g.substringAfter("<trkpt ").substringAfter("<trkpt ")
        assertFalse(second.substringBefore("</trkpt>").contains("hr"))
    }
}
