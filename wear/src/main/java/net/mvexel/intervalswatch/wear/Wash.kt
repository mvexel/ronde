package net.mvexel.intervalswatch.wear

import net.mvexel.intervalswatch.shared.Tokens

/*
 * The lower-cap colour band ("wash", T3) of v5-design.md section 6. Pure Kotlin; the screen only paints what these say.
 * The wash follows the judge state, never the marker. No pulse, no flash, no breathing (brand ruling 2).
 */

/** Whether the wash is on this second, and its side and (after chronic step-down) severity. */
data class WashState(val on: Boolean, val side: Side = Side.NONE, val severity: Int = 0)

/**
 * Decides the wash second by second from the judge.
 * Only while judged OUT and the setting is on. Anti-strobe: a new onset needs 10 s since the previous onset (the 5 s dwell is
 * the judge's). Chronic: after 60 s of continuous OUT the wash steps down one severity (minimum s1), after 5 min it is off
 * (stretch, cue and buzz continue); it re-arms when OUT ends. Ambient and the setting only gate [enabled]: an episode that
 * continues underneath comes back at once with no new dwell (wake from ambient).
 */
class WashGate {
    private var episodeStart: Double? = null
    private var started = false
    private var lastOnset = -1e9

    fun step(tSec: Double, out: JudgeOut, enabled: Boolean): WashState {
        if (!out.out) { episodeStart = null; started = false; return WashState(false) }
        val start = episodeStart ?: tSec.also { episodeStart = it }
        if (!started && tSec - lastOnset >= ANTI_STROBE_S) { started = true; lastOnset = tSec }
        val elapsed = tSec - start
        if (!enabled || !started || elapsed >= OFF_AFTER_S) return WashState(false, out.side, out.severity)
        val sev = if (elapsed >= STEP_DOWN_AFTER_S) maxOf(1, out.severity - 1) else out.severity
        return WashState(true, out.side, sev)
    }

    companion object {
        const val ANTI_STROBE_S = 10.0
        const val STEP_DOWN_AFTER_S = 60.0
        const val OFF_AFTER_S = 300.0
    }
}

/** Colour of the wash for [side] and [severity] 1..3 (ARGB), by the brand `alert.*` tokens. */
fun washColor(side: Side, severity: Int): Long = when (side) {
    Side.FAST -> when (severity.coerceIn(1, 3)) { 1 -> Tokens.ALERT_FAST_S1; 2 -> Tokens.ALERT_FAST_S2; else -> Tokens.ALERT_FAST_S3 }
    else -> when (severity.coerceIn(1, 3)) { 1 -> Tokens.ALERT_SLOW_S1; 2 -> Tokens.ALERT_SLOW_S2; else -> Tokens.ALERT_SLOW_S3 }
}

/** One frame of the wash: opacity 0..1 and the ARGB colour to paint at full opacity. */
data class WashFrame(val alpha: Float, val argb: Long)

/**
 * The wash timeline on a caller-supplied clock: fade in 300 ms ease-out at onset, severity or side change 400 ms
 * colour cross-fade, fade out 600 ms ease-in; never longer than 1 s. [durationScale] 0 (system animations off) switches instantly.
 */
class WashAnimator(private val durationScale: Float = 1f) {
    private var from = WashFrame(0f, washColor(Side.SLOW, 1))
    private var to = from
    private var startMs = 0L
    private var durMs = 0L
    private var easing: (Float) -> Float = { 1f }
    private var targetOn = false
    private var targetColor = from.argb

    /** Sets the target at [nowMs]; call every frame or second, it only restarts a transition when the target changes. */
    fun target(nowMs: Long, state: WashState) {
        val on = state.on && state.side != Side.NONE
        val color = if (on) washColor(state.side, state.severity) else targetColor
        if (on == targetOn && color == targetColor) return
        val cur = sample(nowMs)
        from = cur
        when {
            on && !targetOn -> { to = WashFrame(1f, color); durMs = IN_MS; easing = ::easeOut; from = WashFrame(cur.alpha, color) }
            !on -> { to = WashFrame(0f, cur.argb); durMs = OUT_MS; easing = ::easeIn }
            else -> { to = WashFrame(1f, color); durMs = CROSS_MS; easing = ::easeInOut }
        }
        durMs = (durMs * durationScale).toLong()
        startMs = nowMs; targetOn = on; targetColor = color
    }

    fun sample(nowMs: Long): WashFrame {
        if (durMs <= 0L) return to
        val f = ((nowMs - startMs).toFloat() / durMs).coerceIn(0f, 1f)
        if (f >= 1f) return to
        val e = easing(f)
        return WashFrame(from.alpha + (to.alpha - from.alpha) * e, lerpColor(from.argb, to.argb, e))
    }

    companion object {
        const val IN_MS = 300L
        const val CROSS_MS = 400L
        const val OUT_MS = 600L
        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
        private fun easeIn(x: Float) = x * x * x
        private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1f - Math.pow((-2.0 * x + 2.0), 3.0).toFloat() / 2f

        private fun lerpColor(a: Long, b: Long, f: Float): Long {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toLong().coerceIn(0, 255)
            return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
