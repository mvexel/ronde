package net.mvexel.intervalswatch.mobile.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import net.mvexel.intervalswatch.mobile.ui.RondeIcons
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.mobile.ui.ScreenHeader
import net.mvexel.intervalswatch.mobile.ui.SectionHeader
import net.mvexel.intervalswatch.mobile.ui.Slab

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
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Watch") }
        item { WatchCard(st, onSync) }
        item { SectionHeader("On the watch", modifier = Modifier.padding(top = 8.dp)) }
        item { NavRow("Watch screens & alerts", onWatchScreens, caption = "Fields, pace window, buzz strength") }
        recordingsSection(recordings, actions)
    }
}

/** The watch slab: "On your watch · Pixel Watch 3 · synced 07:12" with Sync as a white pill (ux F2). */
@Composable
internal fun WatchCard(st: WatchUiState, onSync: () -> Unit) {
    val c = RondeTheme.colors
    Slab {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (st.watches.isEmpty()) {
                    Text("No watch paired", style = MaterialTheme.typography.titleMedium, color = c.onSlab)
                    Text(
                        listOfNotNull("Ronde works without one", st.lastSynced?.let { "synced $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.onSlab2,
                    )
                } else {
                    Text("On your watch", style = MaterialTheme.typography.titleMedium, color = c.onSlab)
                    Text(
                        listOfNotNull(st.watches.joinToString(), st.lastSynced?.let { "synced $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.onSlab2,
                    )
                }
            }
            Button(
                onClick = onSync, enabled = !st.busy, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.onSlab, contentColor = c.slab,
                    disabledContainerColor = c.onSlab3, disabledContentColor = c.slab,
                ),
            ) {
                Icon(RondeIcons.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sync")
            }
        }
        st.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSlab2, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
fun WatchScreensRoute(vm: WatchScreensViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        WatchScreensScreen(st.settings, st.watchConnected, vm::edit, vm::testBuzz, onBack)
    }
}
