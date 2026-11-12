package net.mvexel.intervalswatch.wear

import kotlin.math.max

/**
 * Ruler maths of v5-design.md section 5, on the 192 dp disc (x from the left edge, faster = right). Pure, no Compose.
 * Takes edges already snapped to the grid ([SnappedRange]).
 */
internal data class RulerGeometry(
    /** Centre speed in m/s the ruler is centred on (two-sided) or the edge speed (one-sided). */
    val anchorMps: Double,
    /** x in dp of [anchorMps]. */
    val anchorX: Double,
    val scaleDpPerMps: Double,
    /** Band left and right edge x in dp (band width at least 22 dp for a two-sided target). */
    val bandLeft: Double,
    val bandRight: Double,
    val twoSided: Boolean,
    /** One-sided only: the band runs to the lane end on the allowed side. */
    val openToRight: Boolean = false,
) {
    /** x in dp for a pace in s/km, clamped to 96 +/- 72. */
    fun xOf(paceSKm: Double): Double =
        (anchorX + (1000.0 / paceSKm - anchorMps) * scaleDpPerMps).coerceIn(RulerMath.CENTRE - RulerMath.CLAMP, RulerMath.CENTRE + RulerMath.CLAMP)
}

internal object RulerMath {
    const val CENTRE = 96.0
    const val LANE_WIDTH = 156.0
    const val CLAMP = 72.0
    const val W_FLOOR = 0.14
    const val W_ONE_SIDED = 0.24
    const val BAND_MIN = 22.0
    const val ONE_SIDED_OFFSET = 18.0
    const val LANE_LEFT = 18.0
    const val LANE_RIGHT = 174.0

    fun geometry(r: SnappedRange): RulerGeometry {
        val f = r.fastSKm
        val s = r.slowSKm
        if (f != null && s != null) {
            val vf = 1000.0 / f
            val vs = 1000.0 / s
            val w = vf - vs
            val wEff = max(w, W_FLOOR)
            val scale = LANE_WIDTH / (2.5 * wEff)
            val vc = (vf + vs) / 2
            val bw = max(w * scale, BAND_MIN)
            return RulerGeometry(vc, CENTRE, scale, CENTRE - bw / 2, CENTRE + bw / 2, twoSided = true)
        }
        val scale = LANE_WIDTH / (2.5 * W_ONE_SIDED)
        // No edge at all: nothing to measure against (the pages never draw this); a centred empty band instead of a crash.
        if (f == null && s == null) return RulerGeometry(0.0, CENTRE, scale, CENTRE - BAND_MIN / 2, CENTRE + BAND_MIN / 2, twoSided = true)
        return if (f != null) {
            // Cap ("slower than"): bracket right of centre, allowed side (slower) to the left.
            val x = CENTRE + ONE_SIDED_OFFSET
            RulerGeometry(1000.0 / f, x, scale, LANE_LEFT, x, twoSided = false, openToRight = false)
        } else {
            val x = CENTRE - ONE_SIDED_OFFSET
            RulerGeometry(1000.0 / s!!, x, scale, x, LANE_RIGHT, twoSided = false, openToRight = true)
        }
    }
}
