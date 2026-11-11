package net.mvexel.intervalswatch.mobile.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.ActivityStreams
import net.mvexel.intervalswatch.mobile.dayTimeLabel
import net.mvexel.intervalswatch.mobile.ui.BackHeader
import net.mvexel.intervalswatch.mobile.ui.EmptyText
import net.mvexel.intervalswatch.mobile.ui.Overline
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.PaceUnit

@Composable
fun ActivityDetailRoute(vm: ActivityDetailViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    ActivityDetailScreen(st, onBack)
}

/** Placeholder sections; Steps, Charts and Map get real content with the intervals.icu fetch. */
@Composable
fun ActivityDetailScreen(st: ActivityDetailUiState, onBack: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackHeader("Activity", onBack) }
        val a = st.activity
        if (a == null) {
            if (st.loaded) item { EmptyText("This activity is not on the phone.") }
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.headlineSmall)
                    Text(listOf(dayTimeLabel(a.startLocal), a.type).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (a.isRonde) RondeBadge()
            }
        }
        item { Overline("Summary") }
        item {
            SurfaceCard {
                summaryRows(a).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Overline("Steps") }
        item { EmptyText(if (a.pairedEventId != null) "Planned steps vs. actual: coming soon." else "Not linked to a planned workout.") }
        item { Overline("Charts") }
        item { EmptyText(chartsPlaceholder(st.streams)) }
        item { Overline("Map") }
        item { EmptyText(if (st.streams?.hasMap == true) "Route map: coming soon." else "No GPS track.") }
    }
}

/** Label/value pairs for the fields the activity has. */
internal fun summaryRows(a: ActivityEntity): List<Pair<String, String>> = listOfNotNull(
    a.distanceM?.let { "Distance" to Format.distance(it, PaceUnit.KM) },
    a.movingTimeSec?.let { "Moving time" to Format.duration(it.toDouble()) },
    a.elapsedTimeSec?.let { "Elapsed time" to Format.duration(it.toDouble()) },
    a.avgSpeed?.takeIf { it > 0 }?.let { "Avg pace" to Format.pace(it, PaceUnit.KM) + Format.paceSuffix(PaceUnit.KM) },
    a.avgHr?.let { "Avg heart rate" to "$it bpm" },
    a.avgPower?.let { "Avg power" to "$it W" },
    a.load?.let { "Load" to it.toString() },
)

internal fun chartsPlaceholder(s: ActivityStreams?): String =
    if (s == null || s.samples == 0) "No streams on the phone yet." else "${s.samples} samples. Charts: coming soon."
