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

    @Test fun runCadenceIsStridesPerMinute() {
        val run = recording()
        assertEquals(100L, FitWriter.fitCadence(run, 200))
        assertEquals(91L, FitWriter.fitCadence(run, 181))
        val ride = run.copy(sport = Sport.RIDE)
        assertEquals(90L, FitWriter.fitCadence(ride, 90))
    }

    @Test fun walkIsFitSportWalkingWithStridesCadence() {
        val run = recording()
        val walk = run.copy(sport = Sport.WALK)
        assertEquals(100L, FitWriter.fitCadence(walk, 200))
        val r = FitWriter.encode(run)
        val w = FitWriter.encode(walk)
        assertEquals(r.size, w.size)
        // Only the sport bytes (lap + session) differ, apart from the trailing CRC.
        val diffs = (0 until r.size - 2).filter { r[it] != w[it] }
        assertTrue(diffs.isNotEmpty())
        diffs.forEach { assertEquals(1, r[it].toInt()); assertEquals(11, w[it].toInt()) }
    }

    @Test fun cadenceFieldIsWrittenHalvedForRuns() {
        fun fit(cad: Int, sport: Sport): ByteArray {
            val b = RecordingBuilder("C", sport, false, 1_790_000_000_000L)
            b.add(RecSample(0.0, 0.0, cadence = cad))
            return FitWriter.encode(b.build(1.0, 0.0))
        }
        // 168 spm → 84 strides; the raw byte 168 must not appear as the record cadence.
        val run = fit(168, Sport.RUN); val ride = fit(168, Sport.RIDE)
        assertTrue(run.contains(84.toByte()) && !run.contains(168.toByte()))
        assertTrue(ride.contains(168.toByte()))
    }

    @Test fun lapsFollowStepBoundaries() {
        val r = recording()
        assertEquals(2, r.laps.size)
        assertEquals(StepKind.WARMUP, r.laps[0].kind)
        assertEquals(2.0, r.laps[0].endSec, 0.0)
        assertEquals(3.0, r.laps[1].endSec, 0.0)
    }

    @Test fun resultMessageRoundTrips() {
        val r = RecordingProtocol.parseResult(RecordingProtocol.result(false, "a.fit", "HTTP 401", retry = false))
        assertEquals(false, r.ok); assertEquals("a.fit", r.fileName); assertEquals("HTTP 401", r.reason)
        assertEquals(false, r.retry)
        assertEquals(true, RecordingProtocol.parseResult(RecordingProtocol.result(true, "a.fit")).retry)
    }

    @Test fun workoutNameSurvivesTheFileName() {
        val name = "Threshold 4x8' @ 95% — Ünïcode"
        val f = RecordingProtocol.fileName(1_790_000_000_000L, name)
        assertTrue(f.matches(Regex("[0-9]+_[A-Za-z0-9_-]+\\.fit")))
        assertEquals(name, RecordingProtocol.displayName(f))
        assertEquals("intervalswatch-1790000000", RecordingProtocol.externalId(f))
    }

    @Test fun longMultibyteNamesStayUnderTheFileNameLimit() {
        val f = RecordingProtocol.fileName(1_790_000_000_000L, "跑".repeat(200) + "😀")
        // The phone adds ".part" to it.
        assertTrue(f.length + 5 <= 200)
        assertTrue(RecordingProtocol.displayName(f).all { it == '跑' })
    }

    @Test fun oneRecordPerSecond() {
        val b = RecordingBuilder("D", Sport.RUN, false, 0L)
        b.add(RecSample(1.2, 0.0)); b.add(RecSample(1.9, 1.0)); b.add(RecSample(2.1, 2.0))
        assertEquals(2, b.build(3.0, 2.0).samples.size)
    }

    @Test fun pausesSplitElapsedAndTimerTime() {
        val b = RecordingBuilder("P", Sport.RIDE, false, 1_790_000_000_000L)
        b.onStep(0, null, 0.0, 0.0)
        b.add(RecSample(0.0, 0.0))
        b.pause(10.0); b.pause(11.0); b.resume(40.0)
        b.add(RecSample(41.0, 5.0))
        val r = b.build(60.0, 100.0)
        assertEquals(60.0, r.elapsedSec, 0.0)
        assertEquals(30.0, r.timerSec, 0.0)
        assertEquals(1, r.pauses.size)
        // stop_all + start around the pause, plus initial start and final stop
        assertEquals(4, messageCounts(FitWriter.encode(r))[21])
    }

    /** Writes a 10-minute ride (GPS, HR, power, 2 laps, 30 s pause) when FIT_SAMPLE_OUT is set. */
    @Test fun writeSampleRide() {
        val out = System.getenv("FIT_SAMPLE_OUT") ?: return
        val b = RecordingBuilder("Sample ride", Sport.RIDE, false, 1_790_000_000_000L)
        val step = WorkoutStep("a", StepKind.WARMUP, durationSec = 300)
        var d = 0.0
        for (t in 0..630) {
            if (t == 300) b.pause(300.0)
            if (t == 330) b.resume(330.0)
            if (t in 301..329) continue
            b.onStep(if (t < 300) 0 else 1, step.copy(kind = if (t < 300) StepKind.WARMUP else StepKind.ACTIVE), t.toDouble(), d)
            d += 8.0
            b.add(RecSample(t.toDouble(), d, 52.0 + t * 1e-5, 5.0 + t * 1.5e-5, 12.0 + t % 10, 8.0, 120 + t / 10, 150 + t % 50))
        }
        java.io.File(out).writeBytes(FitWriter.encode(b.build(630.0, d)))
    }
}
