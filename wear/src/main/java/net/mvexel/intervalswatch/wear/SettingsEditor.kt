package net.mvexel.intervalswatch.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.Field
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.Slot
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.SportSettings
import net.mvexel.intervalswatch.shared.label
import net.mvexel.intervalswatch.shared.pickerFields
import net.mvexel.intervalswatch.shared.sample
import net.mvexel.intervalswatch.shared.slot
import net.mvexel.intervalswatch.shared.stepField
import net.mvexel.intervalswatch.shared.withSlot
import net.mvexel.intervalswatch.wear.ui.Rb
import net.mvexel.intervalswatch.wear.ui.RbType

/** Settings.md §5.1 w7: a plain list. Only what the watch edits; everything else says "More on your phone". */
@Composable
internal fun SettingsList(
    settings: ScreenSettings,
    onEditSport: (Sport) -> Unit,
    onToggleOffTarget: (Sport) -> Unit,
    onBuzzStrength: () -> Unit,
) {
    WatchList {
        item { Text("Settings", style = MaterialTheme.typography.title3) }
        item("run") { SettingsChip("Run screens", "3 fields") { onEditSport(Sport.RUN) } }
        item("ride") { SettingsChip("Ride screens", "3 fields") { onEditSport(Sport.RIDE) } }
        item("buzz") {
            SettingsChip("Buzz strength", if (settings.buzzStrength == BuzzStrength.STRONG) "Strong" else "Normal", onClick = onBuzzStrength)
        }
        item("offRun") { OffTargetToggle("Run off-target buzz", settings.run.offTargetBuzz) { onToggleOffTarget(Sport.RUN) } }
        item("offRide") { OffTargetToggle("Ride off-target buzz", settings.ride.offTargetBuzz) { onToggleOffTarget(Sport.RIDE) } }
        item("more") { Text("More on your phone", fontSize = 11.sp, color = Rb.ink2, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun SettingsChip(label: String, secondary: String, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = { Text(label) },
    secondaryLabel = { Text(secondary) },
    colors = ChipDefaults.secondaryChipColors(),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun OffTargetToggle(label: String, checked: Boolean, onToggle: () -> Unit) = ToggleChip(
    checked = checked,
    onCheckedChange = { onToggle() },
    label = { Text(label, maxLines = 2) },
    toggleControl = { androidx.wear.compose.material.Switch(checked = checked) },
    colors = ToggleChipDefaults.toggleChipColors(),
    modifier = Modifier.fillMaxWidth(),
)

/** One crown detent, in rotary pixels (the 2-detent threshold lives in the Step page, not here). */
private const val DETENT_PX = 40f

/**
 * Settings.md §5.1 w5/w6: the Step page drawn with sample values. Fixed zones at 45%, the three user
 * slots outlined dashed. Tap a slot to select it (solid outline); the crown then dials through the
 * catalogue and a tick on the right bezel shows the position. Tapping the slot confirms. No save
 * button: [onChange] applies and syncs at once.
 */
@Composable
internal fun SlotMapScreen(sport: Sport, profile: SportSettings, onChange: (Slot, Field) -> Unit, initialSelected: Slot? = null) {
    var selected by remember { mutableStateOf(initialSelected) }
    val sel = selected
    val focus = remember { FocusRequester() }
    var carry by remember { mutableStateOf(0f) }
    val inspect = LocalInspectionMode.current
    LaunchedEffect(sel) { if (!inspect && sel != null) runCatching { focus.requestFocus() } }

    val fields = pickerFields(sport)
    val title = (if (sport == Sport.RIDE) "RIDE" else "RUN") + " · STEP PAGE"
    val dial = Modifier
        .fillMaxSize()
        .onRotaryScrollEvent { e ->
            val slot = selected ?: return@onRotaryScrollEvent false
            carry += e.verticalScrollPixels
            val detents = (carry / DETENT_PX).toInt()
            if (detents != 0) {
                carry -= detents * DETENT_PX
                val now = profile.slot(slot)
                val next = stepField(now, sport, detents)
                if (next != now) onChange(slot, next)
            }
            true
        }
        .focusRequester(focus)
        .focusable()

    Box(dial.background(Rb.ground)) {
        if (sel != null) PositionTick(fields.indexOf(profile.slot(sel)).coerceAtLeast(0), fields.size)
        // Fixed zones, dimmed: the editor must not look like it can change them.
        Column(Modifier.fillMaxSize().alpha(0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = Rb.ink2, modifier = Modifier.padding(top = 14.dp))
            Text("2:10", style = RbType.partner, modifier = Modifier.padding(top = 10.dp))
            Text("4:28", style = RbType.hero)
            Text("4:39–4:25", style = RbType.target)
        }
        SlotBox(Slot.FLANK_LEFT, sport, profile, sel, Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 52.dp)) { selected = toggle(sel, it) }
        SlotBox(Slot.FLANK_RIGHT, sport, profile, sel, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, top = 52.dp)) { selected = toggle(sel, it) }
        SlotBox(Slot.BOTTOM_EDGE, sport, profile, sel, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) { selected = toggle(sel, it) }
        Text(
            if (sel == null) "Tap a slot" else "◂ ${profile.slot(sel).label.uppercase()} ▸",
            fontSize = 11.sp, color = if (sel == null) Rb.ink3 else Rb.ink,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        )
    }
}

private fun toggle(current: Slot?, tapped: Slot): Slot? = if (current == tapped) null else tapped

@Composable
private fun SlotBox(slot: Slot, sport: Sport, profile: SportSettings, selected: Slot?, modifier: Modifier, onTap: (Slot) -> Unit) {
    val field = profile.slot(slot)
    val isSel = selected == slot
    val bottom = slot == Slot.BOTTOM_EDGE
    Column(
        modifier
            .width(if (bottom) 104.dp else 56.dp)
            .slotOutline(isSel)
            .clickable { onTap(slot) }
            .padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!bottom) Text(field.label, fontSize = 9.sp, color = Rb.ink2, maxLines = 1)
        Text(field.sample(sport), style = RbType.field.copy(fontSize = 16.sp), color = Rb.ink)
    }
}

/** ink2 1.5 dp dashed when idle, ink 2 dp solid when selected (designer, §5.1). */
private fun Modifier.slotOutline(selected: Boolean): Modifier = drawBehind {
    drawRoundRect(
        color = if (selected) Rb.ink else Rb.ink2,
        cornerRadius = CornerRadius(10.dp.toPx()),
        style = Stroke(
            width = (if (selected) 2.dp else 1.5.dp).toPx(),
            pathEffect = if (selected) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        ),
    )
}

/** `ink` 3 dp tick on a `track` hairline arc from 1 to 5 o'clock on the right bezel. */
@Composable
private fun PositionTick(index: Int, count: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val inset = 6.dp.toPx()
        val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
        // 1 o'clock = -60°, 5 o'clock = +60° (0° is 3 o'clock).
        drawArc(Rb.track, startAngle = -60f, sweepAngle = 120f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(1.dp.toPx()))
        val frac = if (count <= 1) 0f else index / (count - 1f)
        drawArc(Rb.ink, startAngle = -60f + 120f * frac - 1.5f, sweepAngle = 3f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Plays the GO pattern; Strong is pulses ×1.5 long at full amplitude (settings.md §4 row 6). */
internal fun playBuzzSample(context: Context, strength: BuzzStrength) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    val timings = Haptics.scaled(Haptics.GO, strength)
    val amplitudes = Haptics.amplitudes(Haptics.GO, strength)
    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
}
