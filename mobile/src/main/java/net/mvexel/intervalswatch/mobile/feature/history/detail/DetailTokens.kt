package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.mobile.R
import net.mvexel.intervalswatch.shared.StepKind

/**
 * Direction B "Paper and slab" colours the Activity Detail needs beyond MaterialTheme.colorScheme
 * (docs/phone/design/design.md). Private to this package until the app-wide RondeColors exists; the lead swaps
 * these for the shared theme tokens at merge.
 */
internal class DetailPalette(
    val dark: Boolean,
    /** Ring, route and legend colours; always on black or as a route line, never as text on paper. */
    val work: Color = Color(0xFFFFC53D),
    val recovery: Color = Color(0xFF3DC8FF),
    val warmup: Color = Color(0xFF96969C),
    /** Brand text (Today, Ronde): deep amber on paper. */
    val amberText: Color,
    val fast: Color,
    val slow: Color,
    val tintWork: Color,
    val tintEasy: Color,
    val tintWarmup: Color,
    /** Target band fill: ink at 16 % light / 22 % dark. */
    val band: Color,
    val bandEdge: Color,
    val slab: Color = Color(0xFF15161A),
    /** 1 dp edge around the slab in dark mode, where the slab is true black on #0E0F12. */
    val slabEdge: Color? = null,
    val slabInk2: Color = Color(0xFFC4C8CF),
    val ink3: Color,
    val surface2: Color,
    val line: Color,
    val grid: Color,
    val hrZones: List<Color>,
    val mapLand: Color,
    val mapMinor: Color,
    val mapPark: Color,
    val mapWater: Color,
    val casing: Color,
) {
    fun kind(k: StepKind): Color = when (k) {
        StepKind.ACTIVE -> work
        StepKind.REST -> recovery
        StepKind.WARMUP, StepKind.COOLDOWN -> warmup
    }

    fun tint(k: StepKind): Color = when (k) {
        StepKind.ACTIVE -> tintWork
        StepKind.REST -> tintEasy
        StepKind.WARMUP, StepKind.COOLDOWN -> tintWarmup
    }
}

internal object DetailTokens {
    val light = DetailPalette(
        dark = false,
        amberText = Color(0xFF7A5200),
        fast = Color(0xFFC4103D),
        slow = Color(0xFF3D5BE0),
        tintWork = Color(0xFFFFF0CD),
        tintEasy = Color(0xFFE0F6FF),
        tintWarmup = Color(0xFFF2F2F3),
        band = Color(0xFF15161A).copy(alpha = 0.16f),
        bandEdge = Color(0xFF15161A).copy(alpha = 0.55f),
        ink3 = Color(0xFF6B6F78),
        surface2 = Color(0xFFEFECE5),
        line = Color(0xFFE4E1DA),
        grid = Color(0xFF6B6F78).copy(alpha = 0.35f),
        hrZones = listOf(Color(0xFF9ADBC0), Color(0xFF4FC596), Color(0xFF00A66B), Color(0xFF007A4E), Color(0xFF004D31)),
        mapLand = Color(0xFFEEEBE4),
        mapMinor = Color(0xFFFFFFFF),
        mapPark = Color(0xFFDDE4D5),
        mapWater = Color(0xFFC8D6DF),
        casing = Color(0xFF15161A),
    )

    val dark = DetailPalette(
        dark = true,
        amberText = Color(0xFFFFC53D),
        fast = Color(0xFFFF5160),
        slow = Color(0xFF5586FF),
        tintWork = Color(0xFF3F3725),
        tintEasy = Color(0xFF1F323D),
        tintWarmup = Color(0xFF24262A),
        band = Color.White.copy(alpha = 0.22f),
        bandEdge = Color.White.copy(alpha = 0.6f),
        slab = Color(0xFF000000),
        slabEdge = Color(0xFF2B2D31),
        ink3 = Color(0xFF8E939B),
        surface2 = Color(0xFF222429),
        line = Color(0xFF2B2D31),
        grid = Color(0xFF8E939B).copy(alpha = 0.35f),
        hrZones = listOf(Color(0xFF009F68), Color(0xFF00BB7B), Color(0xFF00D98F), Color(0xFF00F7A4), Color(0xFFB4FFD6)),
        mapLand = Color(0xFF141518),
        mapMinor = Color(0xFF1F2125),
        mapPark = Color(0xFF121A15),
        mapWater = Color(0xFF0B1820),
        casing = Color(0xFF000000),
    )

    /** Roboto Flex at wdth 84 (wght 680 / 500), bundled as static subset instances (res/font, OFL). */
    val condensed = FontFamily(
        Font(R.font.roboto_flex_condensed_bold, FontWeight.Bold),
        Font(R.font.roboto_flex_condensed_medium, FontWeight.Medium),
    )

    fun numerals(size: TextUnit, weight: FontWeight = FontWeight.Bold) =
        TextStyle(fontFamily = condensed, fontWeight = weight, fontSize = size, fontFeatureSettings = "tnum", lineHeight = size * 1.1f)

    val heroTitle = numerals(30.sp)
    val heroVerdict = numerals(44.sp)
    val headline = numerals(26.sp)
}

/** Light or dark by the current scheme's background, so this follows whatever theme the app applies. */
internal val detailPalette: DetailPalette
    @Composable @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DetailTokens.dark else DetailTokens.light
