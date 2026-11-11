package net.mvexel.intervalswatch.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.WorkoutStep

val CardShape = RoundedCornerShape(20.dp)
val SlabShape = RoundedCornerShape(24.dp)

/** Tab title row (64 dp): optional leading mark, condensed title, actions on the right. */
@Composable
fun ScreenHeader(title: String, leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = RondeType.tabTitle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        actions()
    }
}

/** "This week ........ 3 activities · 30.2 km · 1:59": a 16 sp title with an optional caption on the right. */
@Composable
fun SectionHeader(title: String, caption: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
    }
}

/** Small section label (sentence case, ink2). Kept for screens not yet moved to [SectionHeader]. */
@Composable
fun Overline(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

/**
 * The slab: a black piece of the watch face set into the page (direction B's brand motif). Content is
 * [RondeColors.onSlab] on [RondeColors.slab]; in dark mode it is true black with a 1 dp edge.
 */
@Composable
fun Slab(modifier: Modifier = Modifier, shape: Shape = SlabShape, content: @Composable ColumnScope.() -> Unit) {
    val c = RondeTheme.colors
    Surface(
        modifier.fillMaxWidth(), shape = shape, color = c.slab, contentColor = c.onSlab,
        border = c.slabEdge?.let { BorderStroke(1.dp, it) },
    ) { Column(Modifier.padding(16.dp), content = content) }
}

/** White row card with a hairline edge; tappable when [onClick] is set. */
@Composable
fun RowCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

/** Surface card for a list of rows. */
@Composable
fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

/** A tappable card row ending in a chevron. */
@Composable
fun NavRow(label: String, onClick: () -> Unit, caption: String? = null) {
    RowCard(onClick = onClick) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        Icon(RondeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The black "Ronde" chip with amber letters: marks activities recorded with Ronde on the watch. */
@Composable
fun RondeBadge(modifier: Modifier = Modifier) {
    val c = RondeTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Text(
        "Ronde",
        style = MaterialTheme.typography.labelSmall,
        color = c.onBadge,
        maxLines = 1,
        modifier = modifier
            .background(c.badge, shape)
            .then(c.badgeEdge?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Filter chip: ink when selected (with a check), outlined ink3 otherwise. */
@Composable
fun RondeFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (selected) ({ Icon(RondeIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = cs.background, labelColor = cs.onSurfaceVariant,
            selectedContainerColor = cs.onBackground, selectedLabelColor = cs.background, selectedLeadingIconColor = cs.background,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = cs.outline, selectedBorderColor = cs.onBackground),
    )
}

/** Ink switch (white thumb with a check when on), the "on watch" toggle. */
@Composable
fun RondeSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        thumbContent = if (checked) ({ Icon(RondeIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary, checkedIconColor = cs.primary, checkedBorderColor = cs.primary,
            uncheckedTrackColor = cs.surfaceContainerHigh, uncheckedThumbColor = cs.outline, uncheckedBorderColor = cs.outline,
        ),
    )
}

/** A 48 dp grey disc with a sport glyph: the leading mark of activities without a Ronde ring. */
@Composable
fun SportDisc(icon: ImageVector, size: Dp = 48.dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Ronde / for intervals.icu" with a ring. */
@Composable
fun BrandHeader(steps: List<WorkoutStep>?) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RingDisc(steps ?: Samples.workouts.first().steps, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Ronde", style = RondeType.tabTitle, color = MaterialTheme.colorScheme.onBackground)
            Text("for intervals.icu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Title row of a pushed screen. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyText(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Empty list: a ring-less card with a title, a line of help and an optional action. */
@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            action?.let { OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(it) } }
        }
    }
}

/** Something failed: the message in the signal colour plus a retry. Hue never alone: the "!" and the words carry it. */
@Composable
fun ErrorState(message: String, action: String? = "Try again", onAction: () -> Unit = {}) {
    Surface(
        Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("!", style = RondeType.numberM, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            action?.let { OutlinedButton(onClick = onAction) { Text(it) } }
        }
    }
}
