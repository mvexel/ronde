package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.shared.Tokens
import net.mvexel.intervalswatch.shared.TypeScale

// Light "paper" and dark schemes from the tokens; no Material You, so hues only ever mean something.
private val paper = lightColorScheme(
    primary = Color(Tokens.PAPER_INK), onPrimary = Color(Tokens.PAPER_GROUND),
    secondary = Color(Tokens.WORK_DEEP), onSecondary = Color(Tokens.PAPER_SURFACE),
    tertiary = Color(Tokens.RECOVERY_DEEP), onTertiary = Color(Tokens.PAPER_SURFACE),
    background = Color(Tokens.PAPER_GROUND), onBackground = Color(Tokens.PAPER_INK),
    surface = Color(Tokens.PAPER_SURFACE), onSurface = Color(Tokens.PAPER_INK),
    surfaceVariant = Color(Tokens.PAPER_LINE), onSurfaceVariant = Color(Tokens.PAPER_INK2),
    outline = Color(Tokens.PAPER_LINE), outlineVariant = Color(Tokens.PAPER_LINE),
    error = Color(Tokens.SIGNAL_DEEP), onError = Color(Tokens.PAPER_SURFACE),
    surfaceContainer = Color(Tokens.PAPER_SURFACE), surfaceContainerHighest = Color(Tokens.PAPER_LINE),
)

private val darkScheme = darkColorScheme(
    primary = Color(Tokens.INK), onPrimary = Color(Tokens.DARK_GROUND),
    secondary = Color(Tokens.WORK), onSecondary = Color(Tokens.GROUND),
    tertiary = Color(Tokens.RECOVERY), onTertiary = Color(Tokens.GROUND),
    background = Color(Tokens.DARK_GROUND), onBackground = Color(Tokens.INK),
    surface = Color(Tokens.DARK_SURFACE), onSurface = Color(Tokens.INK),
    surfaceVariant = Color(Tokens.DARK_LINE), onSurfaceVariant = Color(Tokens.INK2),
    outline = Color(Tokens.DARK_LINE), outlineVariant = Color(Tokens.DARK_LINE),
    error = Color(Tokens.SIGNAL), onError = Color(Tokens.GROUND),
    surfaceContainer = Color(Tokens.DARK_SURFACE), surfaceContainerHighest = Color(Tokens.DARK_LINE),
)

private const val TNUM = "tnum"

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = TypeScale.PHONE_DISPLAY.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = TypeScale.PHONE_TITLE.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = TypeScale.PHONE_BODY.sp, fontFeatureSettings = TNUM),
    bodySmall = TextStyle(fontSize = TypeScale.PHONE_SMALL.sp, fontFeatureSettings = TNUM),
    labelSmall = TextStyle(fontSize = TypeScale.PHONE_OVERLINE.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em),
)

@Composable
fun RunbumTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) darkScheme else paper, typography = typography, content = content)


