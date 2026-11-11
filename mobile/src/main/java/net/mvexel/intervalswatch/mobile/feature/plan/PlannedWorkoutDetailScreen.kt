package net.mvexel.intervalswatch.mobile.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.WorkoutRingDisc
import net.mvexel.intervalswatch.mobile.ui.BackHeader
import net.mvexel.intervalswatch.mobile.ui.EmptyText
import net.mvexel.intervalswatch.mobile.ui.Overline
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard
import net.mvexel.intervalswatch.mobile.workoutMeta
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutStep

@Composable
fun PlannedWorkoutDetailRoute(vm: PlannedWorkoutDetailViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    PlannedWorkoutDetailScreen(st, onBack, vm::togglePinned, vm::toggleHidden)
}

@Composable
fun PlannedWorkoutDetailScreen(st: PlannedWorkoutDetailUiState, onBack: () -> Unit, onTogglePinned: () -> Unit = {}, onToggleHidden: () -> Unit = {}) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackHeader("Planned workout", onBack) }
        val item = st.item
        if (item == null) {
            if (st.loaded) item { EmptyText("This workout is no longer in your plan. Sync to refresh.") }
            return@LazyColumn
        }
        val w = item.workout
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkoutRingDisc(w.steps, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(w.name, style = MaterialTheme.typography.headlineSmall)
                    Text(workoutMeta(w), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTogglePinned) { Text(if (item.pinned) "Unpin" else "Pin to top") }
                OutlinedButton(onClick = onToggleHidden) { Text(if (item.hidden) "Show on watch" else "Hide from watch") }
            }
        }
        if (item.hidden) item { EmptyText("Hidden: not sent to the watch.") }
        w.description?.takeIf { it.isNotBlank() }?.let { d -> item { Text(d, style = MaterialTheme.typography.bodyMedium) } }
        item { Overline("Steps") }
        item {
            SurfaceCard {
                w.steps.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    StepRow(w, s)
                }
            }
        }
    }
}

@Composable
private fun StepRow(w: Workout, s: WorkoutStep) {
    Row(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(s.label.ifBlank { s.kind.name.lowercase().replaceFirstChar { it.uppercase() } }, s.repeat).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            s.primaryTarget?.let { t ->
                Text(Format.target(t, 0.0, w.paceUnit, w.sport), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stepLength(s), style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun stepLength(s: WorkoutStep): String =
    s.durationSec?.let { Format.duration(it.toDouble()) } ?: s.distanceM?.let { "${it.toInt()} m" } ?: "lap"
