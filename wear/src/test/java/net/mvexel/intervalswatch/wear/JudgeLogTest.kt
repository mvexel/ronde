package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.CueRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JudgeLogTest {
    private fun out(state: JudgeState = JudgeState.IN, buzz: Side = Side.NONE) = JudgeOut(
        state = state, displaySKm = 288.4, shownSKm = 290.0, drawnSKm = 290.0, judgeMeanSKm = 291.0, judgeWinSec = 45,
        sigmaHat = 9.8, marginSKm = 10.0, candidate = Side.NONE, fastPath = false, dwellSec = 0, side = Side.NONE, severity = 0,
        beyondSKm = 0.0, range = null, alertAgeSec = 0, buzz = buzz, buzzCount5Min = 0, stepAgeSec = 63.4,
    )

    private fun row(j: JudgeOut = out(), t: Long = 1731300123456L) = JudgeLog.Row(
        tMs = t, stepIdx = 2, stepLenSec = 480.0, rawSpeed = 3.52, judge = j, washOn = false, hr = 171, ambient = false,
        cueRow = CueRow.NUMBER, washSetting = true,
    )

    @Test fun lineMatchesTheSpecExample() {
        // v5-design.md section 7: `1731300123456,2,63,480,3.52,6,288.4,290,291.0,45,9.8,10,0,0,0,IN,0,0,0,0,0,0,171,0,N,1`
        // (the spec example has a gps accuracy of 6 m, which this build cannot read: that field is empty here)
        assertEquals("1731300123456,2,63,480,3.52,,288.4,290,291.0,45,9.8,10,0,0,0,IN,0,0,0,0,0,0,171,0,N,1", JudgeLog.line(row()))
        assertEquals(JudgeLog.HEADER.split(",").size, JudgeLog.line(row()).split(",").size)
        assertEquals(26, JudgeLog.HEADER.split(",").size)
    }

    @Test fun missingValuesAreEmptyAndFlagsEncode() {
        val j = out(JudgeState.OUT, Side.FAST).copy(displaySKm = null, shownSKm = null, judgeMeanSKm = null, judgeWinSec = null, sigmaHat = null,
            candidate = Side.SLOW, fastPath = true, severity = 2)
        val l = JudgeLog.line(row(j).copy(rawSpeed = null, hr = null, ambient = true, cueRow = CueRow.WORDS, washSetting = false, washOn = true))
        val f = l.split(",")
        assertEquals("", f[4]); assertEquals("", f[6]); assertEquals("", f[7]); assertEquals("", f[8]); assertEquals("", f[9]); assertEquals("", f[10])
        assertEquals("S", f[12]); assertEquals("1", f[13]); assertEquals("OUT", f[15]); assertEquals("2", f[16])
        assertEquals("1", f[18]); assertEquals("1", f[20]); assertEquals("", f[22]); assertEquals("1", f[23]); assertEquals("W", f[24]); assertEquals("0", f[25])
    }

    @Test fun writerAppendsHeaderOnceAndFlushesEveryTenSeconds() {
        val dir = Files.createTempDirectory("judge").toFile()
        val w = JudgeLogWriter(dir, flushEveryMs = 10_000L)
        val t0 = 1731300123456L
        repeat(5) { w.append(row(t = t0 + it * 1000L)) }
        val f = File(dir, JudgeLog.fileName(t0))
        assertEquals("nothing flushed before 10 s", 0L, f.length())
        w.append(row(t = t0 + 10_000L))
        assertEquals(1 + 6, f.readLines().size)
        assertTrue(f.readLines().first().startsWith("t_ms,step_idx"))
        w.close()
        assertEquals(1 + 6, f.readLines().size)
        // reopening the same day appends without a second header
        val w2 = JudgeLogWriter(dir); w2.append(row(t = t0 + 20_000L)); w2.close()
        assertEquals(1 + 7, f.readLines().size)
        assertEquals(1, f.readLines().count { it.startsWith("t_ms") })
    }
}
