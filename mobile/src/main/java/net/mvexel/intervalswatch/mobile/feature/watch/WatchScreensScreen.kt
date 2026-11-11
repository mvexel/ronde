package net.mvexel.intervalswatch.mobile.feature.watch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.mvexel.intervalswatch.shared.BigNumber
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.PACE_WINDOWS
import net.mvexel.intervalswatch.shared.withPaceWindow
import net.mvexel.intervalswatch.shared.Field
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.Slot
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.SportSettings
import net.mvexel.intervalswatch.shared.UnitsSetting
import net.mvexel.intervalswatch.shared.label
import net.mvexel.intervalswatch.shared.pickerFields
import net.mvexel.intervalswatch.shared.sample
import net.mvexel.intervalswatch.shared.slot
import net.mvexel.intervalswatch.shared.withSlot
import net.mvexel.intervalswatch.shared.withSport
import net.mvexel.intervalswatch.shared.withTotalsField

/** What the picker is choosing a field for: a Step page slot or one of the four Totals quadrants. */
private sealed interface Target {
    data class StepSlot(val slot: Slot) : Target
    data class Totals(val index: Int) : Target
}

/** "Watch screens & alerts" (settings.md §5.2): every edit goes straight to [onEdit], which syncs it. */
@Composable
internal fun WatchScreensScreen(
    settings: ScreenSettings,
    watchConnected: Boolean,
    onEdit: ((ScreenSettings) -> ScreenSettings) -> Unit,
    onTestBuzz: () -> Unit,
    onBack: () -> Unit,
) {
    var sport by remember { mutableStateOf(Sport.RUN) }
    var picking by remember { mutableStateOf<Target?>(null) }
    val profile = settings.forSport(sport)
    fun editSport(edit: (SportSettings) -> SportSettings) = onEdit { it.withSport(sport, edit) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Watch screens & alerts", style = MaterialTheme.typography.titleMedium)
        }
        Segmented(listOf("Run" to Sport.RUN, "Ride" to Sport.RIDE), sport) { sport = it }

        RoundPreview(profile, sport) { picking = Target.StepSlot(it) }

        Section(if (sport == Sport.RUN) "Run" else "Ride")
        Segmented(
            listOf("Live value" to BigNumber.LIVE_VALUE, "Time left" to BigNumber.TIME_LEFT), profile.bigNumber,
            label = "Big number",
        ) { v -> editSport { it.copy(bigNumber = v) } }
        FieldRow("Left flank", profile.flankLeft) { picking = Target.StepSlot(Slot.FLANK_LEFT) }
        FieldRow("Right flank", profile.flankRight) { picking = Target.StepSlot(Slot.FLANK_RIGHT) }
        FieldRow("Bottom edge", profile.bottomEdge) { picking = Target.StepSlot(Slot.BOTTOM_EDGE) }
        ToggleRow("Totals page", profile.totalsPage) { v -> editSport { it.copy(totalsPage = v) } }
        if (profile.totalsPage) {
            profile.totalsFields.forEachIndexed { i, f -> FieldRow("Totals ${i + 1}", f) { picking = Target.Totals(i) } }
        }
        ToggleRow("Workout ring page", profile.workoutRingPage) { v -> editSport { it.copy(workoutRingPage = v) } }
        ToggleRow("Off-target buzz", profile.offTargetBuzz) { v -> editSport { it.copy(offTargetBuzz = v) } }
        Segmented(
            PACE_WINDOWS.map { "$it s" to it }, profile.paceWindowSec,
            label = "Pace smoothing window",
        ) { v -> editSport { it.withPaceWindow(v) } }

        HorizontalDivider()
        Section("All sports")
        Segmented(
            listOf("Normal" to BuzzStrength.NORMAL, "Strong" to BuzzStrength.STRONG), settings.buzzStrength,
            label = "Buzz strength",
        ) { v -> onEdit { it.copy(buzzStrength = v) } }
        OutlinedButton(onClick = onTestBuzz, enabled = watchConnected) { Text("Test buzzes on the watch") }
        Segmented(
            listOf("intervals.icu" to UnitsSetting.FROM_INTERVALS, "km" to UnitsSetting.KM, "mi" to UnitsSetting.MI),
            settings.units, label = "Units",
        ) { v -> onEdit { it.copy(units = v) } }
        ToggleRow("Auto-pause in warm-up and cool-down (Run only)", settings.autoPauseWarmupCooldown) { v ->
            onEdit { it.copy(autoPauseWarmupCooldown = v) }
        }
        ToggleRow("Keep screen on during work steps", settings.keepScreenOnDuringWork) { v ->
            onEdit { it.copy(keepScreenOnDuringWork = v) }
        }
        if (settings.keepScreenOnDuringWork) {
            Text("Uses more battery.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (watchConnected) "Changes are sent to your watch as you make them." else "Waiting for watch. Changes are sent when it reconnects.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
    }

    picking?.let { target ->
        val current = when (target) {
            is Target.StepSlot -> profile.slot(target.slot)
            is Target.Totals -> profile.totalsFields[target.index]
        }
        FieldPicker(sport, current, onDismiss = { picking = null }) { f ->
            when (target) {
                is Target.StepSlot -> editSport { it.withSlot(target.slot, f) }
                is Target.Totals -> editSport { it.withTotalsField(target.index, f) }
            }
            picking = null
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Selected state is ink, never cyan (visual-design §6). */
@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, label: String? = null, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (name, value) ->
                val on = value == selected
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f).clip(shape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .let { if (on) it else it.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape) }
                        .clickable { onSelect(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun FieldRow(label: String, field: Field, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(field.label + "  ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Each field with a live-looking sample value, so "Step avg pace" vs "Pace (now)" explains itself. */
@Composable
private fun FieldPicker(sport: Sport, current: Field, onDismiss: () -> Unit, onPick: (Field) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose a field") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pickerFields(sport).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (f == current) "● " + f.label else "○ " + f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(f.sample(sport), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** The Step page drawn round and black with sample values; tapping a user slot opens its picker. */
@Composable
internal fun RoundPreview(profile: SportSettings, sport: Sport, onSlot: (Slot) -> Unit) {
    val dim = Color.White.copy(alpha = 0.45f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) { drawCircle(dim, style = Stroke(5.dp.toPx()), radius = size.minDimension / 2 - 3.dp.toPx()) }
            // Fixed zones, dimmed: top edge, partner, hero, target line.
            Column(Modifier.size(220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(20.dp))
                Text("2/4 TEMPO", color = dim, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                val partner = if (profile.bigNumber == BigNumber.LIVE_VALUE) "2:10" else "4:28"
                val hero = if (profile.bigNumber == BigNumber.LIVE_VALUE) "4:28" else "2:10"
                Text(partner, color = dim, fontSize = 20.sp)
                Text(hero, color = dim, fontSize = 34.sp)
                Text(if (sport == Sport.RIDE) "240–260 W" else "4:39–4:25", color = dim, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    SlotBox(profile.flankLeft, sport) { onSlot(Slot.FLANK_LEFT) }
                    SlotBox(profile.flankRight, sport) { onSlot(Slot.FLANK_RIGHT) }
                }
                Spacer(Modifier.height(4.dp))
                SlotBox(profile.bottomEdge, sport, compact = true) { onSlot(Slot.BOTTOM_EDGE) }
            }
        }
    }
}

@Composable
private fun SlotBox(field: Field, sport: Sport, compact: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(6.dp))
            .border1(Color.White.copy(alpha = 0.7f))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(field.label, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
        Text(field.sample(sport), color = Color.White, fontSize = if (compact) 13.sp else 17.sp)
    }
}

private fun Modifier.border1(c: Color): Modifier = border(1.dp, c, RoundedCornerShape(6.dp))
