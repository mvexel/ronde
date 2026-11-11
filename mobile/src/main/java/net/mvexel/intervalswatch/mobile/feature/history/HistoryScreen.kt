package net.mvexel.intervalswatch.mobile.feature.history

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.dayTimeLabel
import net.mvexel.intervalswatch.mobile.ui.EmptyText
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.PaceUnit
import java.time.LocalDate

@Composable
fun HistoryRoute(vm: HistoryViewModel, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    HistoryScreen(st, onOpen)
}

@Composable
fun HistoryScreen(st: HistoryUiState, onOpen: (String) -> Unit = {}) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall) }
        if (!st.loading && st.activities.isEmpty()) {
            item { EmptyText("No activities yet. Workouts you finish will show up here.") }
        }
        items(st.activities, key = { it.id }) { a -> SurfaceCard { ActivityRow(a, onOpen) } }
    }
}

@Composable
private fun ActivityRow(a: ActivityEntity, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(a.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium)
            Text(activityMeta(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (a.isRonde) {
            Spacer(Modifier.width(8.dp))
            RondeBadge()
        }
    }
}

/** Marks activities recorded with Ronde on the watch. */
@Composable
fun RondeBadge() {
    Text(
        "Ronde",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** "Today · 07:00 · Run · 10.0 km · 52:10" (moving time). */
internal fun activityMeta(a: ActivityEntity, today: LocalDate = LocalDate.now()): String = listOfNotNull(
    dayTimeLabel(a.startLocal, today),
    a.type.takeIf { it.isNotBlank() },
    a.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, PaceUnit.KM) },
    a.movingTimeSec?.let { Format.duration(it.toDouble()) },
).joinToString(" · ")
