package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.StepKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test fun strongLengthensPulsesNotGaps() {
        val strong = net.mvexel.intervalswatch.shared.BuzzStrength.STRONG
        val normal = net.mvexel.intervalswatch.shared.BuzzStrength.NORMAL
        assertArrayEquals(longArrayOf(0, 225, 100, 225, 100, 225), Haptics.scaled(Haptics.GO, strong))
        assertArrayEquals(Haptics.GO, Haptics.scaled(Haptics.GO, normal))
        assertEquals(255, Haptics.amplitudes(Haptics.GO, strong)[1])
        assertEquals(0, Haptics.amplitudes(Haptics.GO, strong)[2])
    }

    @Test fun workAndRecoveryDiffer() {
        assertArrayEquals(Haptics.GO, Haptics.stepChange(StepKind.ACTIVE))
        for (k in listOf(StepKind.REST, StepKind.WARMUP, StepKind.COOLDOWN, null))
            assertArrayEquals(Haptics.EASE, Haptics.stepChange(k))
    }

    @Test fun offTargetUsesShortPulsesOnly() {
        assertTrue(Haptics.TOO_LOW.drop(1).filterIndexed { i, _ -> i % 2 == 0 }.all { it <= 150 })
        assertTrue(Haptics.TOO_HIGH.drop(1).filterIndexed { i, _ -> i % 2 == 0 }.all { it <= 150 })
        assertNotEquals(Haptics.TOO_LOW.toList(), Haptics.TOO_HIGH.toList())
        assertArrayEquals(Haptics.TOO_HIGH, Haptics.rangeAlert(1))
        assertArrayEquals(Haptics.TOO_LOW, Haptics.rangeAlert(-1))
    }

    @Test fun countdownPulsesAreStrong() {
        assertTrue(Haptics.COUNTDOWN.drop(1).filterIndexed { i, _ -> i % 2 == 0 }.all { it in 120..150 })
    }

    @Test fun distanceCountdownFromSpeed() {
        assertEquals(12.0, Haptics.countdownMeters(4.0)!!, 1e-9)
        assertNull(Haptics.countdownMeters(0.0))
        assertNull(Haptics.countdownMeters(null))
    }
}
