package net.mvexel.intervalswatch.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitWriterTest {

    private fun recording(): Recording {
        val b = RecordingBuilder("Test", Sport.RUN, indoor = false, startEpochMs = 1_790_000_000_000L)
        val step = WorkoutStep("W", StepKind.WARMUP, durationSec = 2)
        b.onStep(0, step, 0.0, 0.0)
        b.add(RecSample(0.0, 0.0, 52.1, 5.1, 10.0, 3.0, 120, null, null))
        b.onStep(1, step.copy(kind = StepKind.ACTIVE), 2.0, 6.0)
        b.add(RecSample(1.0, 3.0, 52.1001, 5.1001, 10.5, 3.2, 130, 200, 85))
        b.add(RecSample(2.0, 6.0, null, null, null, null, null, null, null))
        return b.build(3.0, 9.0)
    }

    /** Walks the file with a tiny decoder; returns global message number → count. */
    private fun messageCounts(fit: ByteArray): Map<Int, Int> {
        val bb = ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = bb.getInt(4)
        val defs = HashMap<Int, Pair<Int, Int>>() // local → (global, record size)
        val counts = HashMap<Int, Int>()
        var p = 14
        while (p < 14 + dataSize) {
            val h = fit[p++].toInt() and 0xFF
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                val global = bb.getShort(p + 2).toInt() and 0xFFFF
                val n = fit[p + 4].toInt() and 0xFF
                var size = 0
                for (i in 0 until n) size += fit[p + 5 + i * 3 + 1].toInt() and 0xFF
                defs[local] = global to size
                p += 5 + n * 3
            } else {
                val (g, size) = defs.getValue(local)
                counts.merge(g, 1, Int::plus)
                p += size
            }
        }
        assertEquals(14 + dataSize, p)
        return counts
    }

    @Test fun headerAndCrcAreValid() {
        val fit = FitWriter.encode(recording())
        val bb = ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(14, fit[0].toInt())
        assertEquals(".FIT", String(fit, 8, 4))
        assertEquals(fit.size - 16, bb.getInt(4))
        assertEquals(FitWriter.crc(fit, 0, 12), bb.getShort(12).toInt() and 0xFFFF)
        // CRC over everything including the trailing CRC is 0.
        assertEquals(0, FitWriter.crc(fit, 0, fit.size))
    }

    @Test fun containsExpectedMessages() {
        val c = messageCounts(FitWriter.encode(recording()))
        assertEquals(1, c[0])   // file_id
        assertEquals(2, c[21])  // event start + stop
        assertEquals(3, c[20])  // records
        assertEquals(2, c[19])  // laps
        assertEquals(1, c[18])  // session
        assertEquals(1, c[34])  // activity
    }

    @Test fun missingSensorsUseInvalidValues() {
        val fit = FitWriter.encode(recording())
        // Third record has no lat: s32 invalid 0x7FFFFFFF must appear in the file.
        val inv = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertTrue(fit.toList().windowed(4).any { it == inv.toList() })
    }

    @Test fun lapsFollowStepBoundaries() {
        val r = recording()
        assertEquals(2, r.laps.size)
        assertEquals(StepKind.WARMUP, r.laps[0].kind)
        assertEquals(2.0, r.laps[0].endSec, 0.0)
        assertEquals(3.0, r.laps[1].endSec, 0.0)
    }

    @Test fun resultMessageRoundTrips() {
        val (ok, name, reason) = RecordingProtocol.parseResult(RecordingProtocol.result(false, "a.fit", "HTTP 401"))
        assertEquals(false, ok); assertEquals("a.fit", name); assertEquals("HTTP 401", reason)
    }
}
