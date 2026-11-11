package net.mvexel.intervalswatch.mobile.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.ui.NavRow

@Composable
fun WatchRoute(vm: WatchViewModel, recordings: RecordingsViewModel, actions: RecordingActions, onWatchScreens: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val recs by recordings.items.collectAsStateWithLifecycle()
    WatchScreen(st, recs, onSync = vm::sync, actions = actions.copy(onRetry = recordings::retry), onWatchScreens = onWatchScreens)
}

@Composable
fun WatchScreen(
    st: WatchUiState,
    recordings: List<RecordingItem>,
    onSync: () -> Unit,
    actions: RecordingActions = RecordingActions(),
    onWatchScreens: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Watch", style = MaterialTheme.typography.headlineSmall) }
        item { WatchCard(st, onSync) }
        recordingsSection(recordings, actions)
        item { NavRow("Watch screens & alerts", onWatchScreens) }
    }
}

/** "On your watch · Pixel Watch 4 · synced 07:12" with Sync as an ink pill (ux F2). */
@Composable
internal fun WatchCard(st: WatchUiState, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On your watch", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        st.watches.joinToString().ifBlank { "No watch connected" },
                        st.lastSynced?.let { "synced $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                st.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(onClick = onSync, enabled = !st.busy, shape = CircleShape) { Text("Sync") }
        }
    }
}

@Composable
fun WatchScreensRoute(vm: WatchScreensViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        WatchScreensScreen(st.settings, st.watchConnected, vm::edit, vm::testBuzz, onBack)
    }
}
