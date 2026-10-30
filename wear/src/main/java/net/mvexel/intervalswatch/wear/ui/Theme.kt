package net.mvexel.intervalswatch.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.Tokens
import net.mvexel.intervalswatch.shared.TypeScale

/** Watch colour tokens as Compose colours. */
object Rb {
    val ground = Color(Tokens.GROUND)
    val surface = Color(Tokens.SURFACE)
    val track = Color(Tokens.TRACK)
    val ink = Color(Tokens.INK)
    val ink2 = Color(Tokens.INK2)
    val ink3 = Color(Tokens.INK3)
    val work = Color(Tokens.WORK)
    val recovery = Color(Tokens.RECOVERY)
    val warmup = Color(Tokens.WARMUP)
    val signal = Color(Tokens.SIGNAL)
}

private const val TNUM = "tnum"

/** Watch type scale; numerals tabular so running digits don't shift sideways. */
object RbType {
    val hero = TextStyle(fontSize = TypeScale.WATCH_HERO.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.02).em)
    val partner = TextStyle(fontSize = TypeScale.WATCH_PARTNER.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val target = TextStyle(fontSize = TypeScale.WATCH_TARGET.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val field = TextStyle(fontSize = TypeScale.WATCH_FIELD.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val title = TextStyle(fontSize = TypeScale.WATCH_TITLE.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = TypeScale.WATCH_BODY.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val label = TextStyle(fontSize = TypeScale.WATCH_LABEL.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em)
    val edge = TypeScale.WATCH_EDGE.sp
}

fun kindColor(k: StepKind): Color = when (k) {
    StepKind.ACTIVE -> Rb.work
    StepKind.REST -> Rb.recovery
    StepKind.WARMUP, StepKind.COOLDOWN -> Rb.warmup
}

private val colors = Colors(
    primary = Rb.ink, primaryVariant = Rb.ink2, secondary = Rb.work, secondaryVariant = Rb.work,
    background = Rb.ground, surface = Rb.surface, error = Rb.signal,
    onPrimary = Rb.ground, onSecondary = Rb.ground, onBackground = Rb.ink, onSurface = Rb.ink, onSurfaceVariant = Rb.ink2,
    onError = Rb.ground,
)

private val typography = Typography(
    title2 = RbType.title.copy(fontSize = 18.sp), title3 = RbType.title,
    body1 = RbType.body.copy(fontSize = 16.sp), body2 = RbType.body,
    button = RbType.body.copy(fontWeight = FontWeight.SemiBold), caption1 = RbType.body, caption2 = RbType.label,
)

@Composable
fun RunbumTheme(content: @Composable () -> Unit) =
    MaterialTheme(colors = colors, typography = typography, content = content)
