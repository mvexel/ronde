package net.mvexel.intervalswatch.mobile.feature.plan

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.WorkoutRingDisc
import net.mvexel.intervalswatch.mobile.data.plan.PlanItem
import net.mvexel.intervalswatch.mobile.planLine
import net.mvexel.intervalswatch.mobile.ui.BrandHeader
import net.mvexel.intervalswatch.mobile.ui.EmptyText
import net.mvexel.intervalswatch.mobile.ui.Overline
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard
import net.mvexel.intervalswatch.mobile.workoutMeta

@Composable
fun PlanRoute(vm: PlanViewModel, onOpen: (Long) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    PlanScreen(st, onOpen = onOpen, onTogglePinned = vm::togglePinned, onToggleHidden = vm::toggleHidden, onToggleShowHidden = vm::toggleShowHidden)
}

@Composable
fun PlanScreen(
    st: PlanUiState,
    onOpen: (Long) -> Unit = {},
    onTogglePinned: (PlanItem) -> Unit = {},
    onToggleHidden: (PlanItem) -> Unit = {},
    onToggleShowHidden: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrandHeader(st.visible.firstOrNull()?.workout?.steps) }
        item { Overline("Next up") }
        item {
            if (st.visible.isEmpty()) {
                EmptyText(if (st.hidden.isEmpty()) "No planned workouts yet." else "All planned workouts are hidden.")
            } else {
                PlanList(st.visible, onOpen, onTogglePinned, onToggleHidden)
            }
        }
        if (st.hidden.isNotEmpty()) {
            item {
                TextButton(onClick = onToggleShowHidden) {
                    Text(if (st.showHidden) "Hide hidden workouts" else "Show ${st.hidden.size} hidden (not on the watch)")
                }
            }
            if (st.showHidden) item { PlanList(st.hidden, onOpen, onTogglePinned, onToggleHidden) }
        }
    }
}

@Composable
private fun PlanList(items: List<PlanItem>, onOpen: (Long) -> Unit, onTogglePinned: (PlanItem) -> Unit, onToggleHidden: (PlanItem) -> Unit) {
    SurfaceCard {
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            PlanRow(item, onOpen, onTogglePinned, onToggleHidden)
        }
    }
}

@Composable
private fun PlanRow(item: PlanItem, onOpen: (Long) -> Unit, onTogglePinned: (PlanItem) -> Unit, onToggleHidden: (PlanItem) -> Unit) {
    val w = item.workout
    Row(Modifier.fillMaxWidth().clickable { onOpen(w.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkoutRingDisc(w.steps)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(if (item.pinned) "Pinned" else null, w.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            Text(workoutMeta(w), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            planLine(w).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        RowMenu(item, onTogglePinned, onToggleHidden)
    }
}

@Composable
private fun RowMenu(item: PlanItem, onTogglePinned: (PlanItem) -> Unit, onToggleHidden: (PlanItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text("⋯") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if (item.pinned) "Unpin" else "Pin to top") }, onClick = { open = false; onTogglePinned(item) })
            DropdownMenuItem(text = { Text(if (item.hidden) "Show on watch" else "Hide from watch") }, onClick = { open = false; onToggleHidden(item) })
        }
    }
}
