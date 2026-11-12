package net.mvexel.intervalswatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun judgeOut(out: Boolean, side: Side = Side.FAST, sev: Int = 2) = JudgeOut(
    state = if (out) JudgeState.OUT else JudgeState.IN, displaySKm = null, shownSKm = null, drawnSKm = null, judgeMeanSKm = null,
    judgeWinSec = null, sigmaHat = null, marginSKm = 5.0, candidate = Side.NONE, fastPath = false, dwellSec = 0,
    side = if (out) side else Side.NONE, severity = if (out) sev else 0, beyondSKm = 0.0, range = null, alertAgeSec = 0,
    buzz = Side.NONE, buzzCount5Min = 0, stepAgeSec = 100.0,
)

class WashTest {
    @Test fun gateOnlyWhileOutAndEnabled() {
        val g = WashGate()
        assertFalse(g.step(0.0, judgeOut(false), true).on)
        val on = g.step(1.0, judgeOut(true, Side.SLOW, 3), true)
        assertTrue(on.on); assertEquals(Side.SLOW, on.side); assertEquals(3, on.severity)
        assertFalse(g.step(2.0, judgeOut(true), false).on) // setting off or ambient
        assertTrue(g.step(3.0, judgeOut(true), true).on) // wake from ambient: back at once, no new dwell
        assertFalse(g.step(4.0, judgeOut(false), true).on)
    }

    @Test fun antiStrobeNeedsTenSecondsSincePreviousOnset() {
        val g = WashGate()
        assertTrue(g.step(100.0, judgeOut(true), true).on)
        g.step(101.0, judgeOut(false), true) // OUT ends
        // new OUT at t=105: only 5 s since the previous onset, so no wash yet
        assertFalse(g.step(105.0, judgeOut(true), true).on)
        assertFalse(g.step(109.0, judgeOut(true), true).on)
        assertTrue(g.step(110.0, judgeOut(true), true).on)
    }

    @Test fun chronicStepDownAfterSixtySecondsAndOffAfterFiveMinutesThenRearms() {
        val g = WashGate()
        var s = WashState(false)
        for (t in 0..59) s = g.step(t.toDouble(), judgeOut(true, sev = 3), true)
        assertEquals(3, s.severity)
        s = g.step(60.0, judgeOut(true, sev = 3), true)
        assertEquals(2, s.severity)
        assertEquals(1, g.step(61.0, judgeOut(true, sev = 1), true).severity) // minimum s1, never below
        assertTrue(g.step(299.0, judgeOut(true, sev = 1), true).on)
        assertFalse(g.step(300.0, judgeOut(true, sev = 3), true).on)
        assertFalse(g.step(400.0, judgeOut(true, sev = 3), true).on)
        g.step(401.0, judgeOut(false), true)
        assertTrue(g.step(420.0, judgeOut(true, sev = 2), true).on) // re-armed
    }

    @Test fun usesTheBrandAlertTokens() {
        assertEquals(0xFF5C171DL, washColor(Side.FAST, 1)); assertEquals(0xFF7B222AL, washColor(Side.FAST, 2)); assertEquals(0xFF922A33L, washColor(Side.FAST, 3))
        assertEquals(0xFF1A2E5FL, washColor(Side.SLOW, 1)); assertEquals(0xFF263F7EL, washColor(Side.SLOW, 2)); assertEquals(0xFF2E4C96L, washColor(Side.SLOW, 3))
    }

    @Test fun timelineFadeInThreeHundredCrossFadeFourHundredFadeOutSixHundred() {
        val a = WashAnimator()
        assertEquals(0f, a.sample(0).alpha, 0f)
        a.target(1000, WashState(true, Side.FAST, 1))
        assertEquals(0f, a.sample(1000).alpha, 1e-6f)
        val mid = a.sample(1150).alpha
        assertTrue("ease-out is ahead of linear: $mid", mid > 0.5f && mid < 1f)
        assertEquals(1f, a.sample(1300).alpha, 1e-6f)
        assertEquals(0xFF5C171DL, a.sample(1300).argb)
        // severity change: 400 ms colour cross-fade, alpha stays 1
        a.target(2000, WashState(true, Side.FAST, 3))
        assertEquals(1f, a.sample(2200).alpha, 0f)
        assertTrue(a.sample(2200).argb != 0xFF5C171DL && a.sample(2200).argb != 0xFF922A33L)
        assertEquals(0xFF5C171DL, a.sample(2000).argb)
        assertEquals(0xFF922A33L, a.sample(2400).argb)
        // fade out: 600 ms, ease-in is behind linear
        a.target(3000, WashState(false))
        val mid2 = a.sample(3300).alpha
        assertTrue("ease-in is behind linear: $mid2", mid2 > 0.5f && mid2 < 1f)
        assertEquals(0f, a.sample(3600).alpha, 1e-6f)
        // never over 1 s
        assertTrue(WashAnimator.IN_MS <= 1000 && WashAnimator.CROSS_MS <= 1000 && WashAnimator.OUT_MS <= 1000)
    }

    @Test fun noFlashAlphaNeverExceedsOneNorDipsOnCrossFade() {
        val a = WashAnimator()
        a.target(0, WashState(true, Side.SLOW, 1)); a.target(500, WashState(true, Side.FAST, 2))
        for (ms in 500..900 step 20) assertEquals(1f, a.sample(ms.toLong()).alpha, 0f)
        a.target(5000, WashState(false))
        a.target(5100, WashState(true, Side.FAST, 2)) // interrupted fade out resumes from where it was
        assertTrue(a.sample(5100).alpha <= 1f)
    }

    @Test fun systemAnimationsOffSwitchesInstantly() {
        val a = WashAnimator(durationScale = 0f)
        a.target(0, WashState(true, Side.FAST, 2))
        assertEquals(1f, a.sample(0).alpha, 0f)
        a.target(10, WashState(false))
        assertEquals(0f, a.sample(10).alpha, 0f)
    }
}
