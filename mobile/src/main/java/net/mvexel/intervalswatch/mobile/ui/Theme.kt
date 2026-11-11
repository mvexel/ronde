package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.mobile.ui.RondePalette as P

/*
 * Direction B "Paper and slab" (docs/phone/design/design.md). Fixed hues, no dynamic colour: a hue on screen
 * always means the same thing. Slots follow the design's Compose mapping table.
 */
private val paperScheme: ColorScheme = lightColorScheme(
    primary = P.PaperInk, onPrimary = P.PaperSurface, // Switch track, filled button
    primaryContainer = P.PaperSurface2, onPrimaryContainer = P.PaperInk,
    secondary = P.PaperInk2, onSecondary = P.PaperSurface,
    secondaryContainer = P.PaperInk, onSecondaryContainer = P.PaperSurface, // nav indicator: ink pill
    tertiary = P.AmberDeep, onTertiary = P.PaperSurface, // brand text (Today)
    tertiaryContainer = P.PaperSurface2, onTertiaryContainer = P.AmberDeep,
    background = P.Paper, onBackground = P.PaperInk,
    surface = P.PaperSurface, onSurface = P.PaperInk,
    surfaceVariant = P.PaperSurface2, onSurfaceVariant = P.PaperInk2,
    surfaceTint = Color.Transparent,
    surfaceBright = P.PaperSurface, surfaceDim = P.PaperSurface2,
    surfaceContainerLowest = P.PaperSurface, surfaceContainerLow = P.PaperSurface,
    surfaceContainer = P.PaperSurface, surfaceContainerHigh = P.PaperSurface2, surfaceContainerHighest = P.PaperSurface2,
    inverseSurface = P.PaperInk, inverseOnSurface = P.PaperSurface, inversePrimary = P.Work,
    outline = P.PaperInk3, outlineVariant = P.PaperLine,
    error = P.PaceFastDeep, onError = P.PaperSurface,
    errorContainer = P.PaperSurface2, onErrorContainer = P.PaceFastDeep,
    scrim = Color.Black,
)

private val nightScheme: ColorScheme = darkColorScheme(
    primary = P.NightInk, onPrimary = P.Night,
    primaryContainer = P.NightSurface2, onPrimaryContainer = P.NightInk,
    secondary = P.NightInk2, onSecondary = P.Night,
    secondaryContainer = P.NightLine, onSecondaryContainer = P.NightInk,
    tertiary = P.Work, onTertiary = Color.Black,
    tertiaryContainer = P.NightSurface2, onTertiaryContainer = P.Work,
    background = P.Night, onBackground = P.NightInk,
    surface = P.NightSurface, onSurface = P.NightInk,
    surfaceVariant = P.NightSurface2, onSurfaceVariant = P.NightInk2,
    surfaceTint = Color.Transparent,
    surfaceBright = P.NightSurface2, surfaceDim = P.Night,
    surfaceContainerLowest = P.Night, surfaceContainerLow = P.NightSurface,
    surfaceContainer = P.NightSurface, surfaceContainerHigh = P.NightSurface2, surfaceContainerHighest = P.NightSurface2,
    inverseSurface = P.NightInk, inverseOnSurface = P.Night, inversePrimary = P.PaperInk,
    outline = P.NightInk3, outlineVariant = P.NightLine,
    error = P.PaceFast, onError = Color.Black,
    errorContainer = P.NightSurface2, onErrorContainer = P.PaceFast,
    scrim = Color.Black,
)

private const val TNUM = "tnum"

/**
 * Material slots. Screen titles (headlineSmall) use the condensed Roboto Flex numeral face; everything else is
 * the system Roboto with tabular figures. See [RondeType] for the numeral styles.
 */
private val typography = Typography(
    headlineLarge = RondeType.detailTitle,
    headlineMedium = RondeType.tabTitle,
    headlineSmall = RondeType.tabTitle,
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em),
)

/** The Ronde theme: direction B colours (light or dark, following the system), Ronde extras and type. */
@Composable
fun RondeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRondeColors provides if (dark) RondeColors.Dark else RondeColors.Light) {
        MaterialTheme(colorScheme = if (dark) nightScheme else paperScheme, typography = typography, content = content)
    }
}

/** Old name, kept for MainActivity and tests. */
@Composable
fun RunbumTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) = RondeTheme(dark, content)

/** Accessors for the non-Material parts of the theme, in the style of `MaterialTheme.colorScheme`. */
object RondeTheme {
    val colors: RondeColors
        @Composable @ReadOnlyComposable
        get() = LocalRondeColors.current

    val type: RondeType get() = RondeType
}
