package net.mvexel.intervalswatch.mobile.feature.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.dayTimeLabel
import net.mvexel.intervalswatch.mobile.ui.EmptyState
import net.mvexel.intervalswatch.mobile.ui.EmptyText
import net.mvexel.intervalswatch.mobile.ui.RingDisc
import net.mvexel.intervalswatch.mobile.ui.RondeBadge
import net.mvexel.intervalswatch.mobile.ui.RondeFilterChip
import net.mvexel.intervalswatch.mobile.ui.RondeIcons
import net.mvexel.intervalswatch.mobile.ui.RowCard
import net.mvexel.intervalswatch.mobile.ui.ResultRing
import net.mvexel.intervalswatch.mobile.ui.RondeType
import net.mvexel.intervalswatch.mobile.ui.ScreenHeader
import net.mvexel.intervalswatch.mobile.ui.SectionHeader
import net.mvexel.intervalswatch.mobile.ui.SportDisc
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.PaceUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun HistoryRoute(vm: HistoryViewModel, onOpen: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    // Back from an activity: its detail may be cached now, so its ring can show the result.
    LaunchedEffect(Unit) { vm.recheckResults() }
    HistoryScreen(st, onOpen, onRefresh = vm::refresh)
}

enum class HistoryFilter(val label: String) {
    ALL("All"), RUN("Run"), RIDE("Ride"), WALK("Walk"), RONDE("Ronde only");

    fun matches(a: ActivityEntity): Boolean = when (this) {
        ALL -> true
        RUN -> activityKind(a.type) == Kind.RUN
        RIDE -> activityKind(a.type) == Kind.RIDE
        WALK -> activityKind(a.type) == Kind.WALK
        RONDE -> a.isRonde
    }
}

internal enum class Kind { RUN, RIDE, WALK, OTHER }

/** intervals.icu types: Run, VirtualRun, TrailRun; Ride, VirtualRide, GravelRide, EBikeRide...; Walk, Hike. */
internal fun activityKind(type: String): Kind = when {
    type.contains("Run", ignoreCase = true) -> Kind.RUN
    type.contains("Ride", ignoreCase = true) -> Kind.RIDE
    type.contains("Walk", ignoreCase = true) || type.contains("Hike", ignoreCase = true) -> Kind.WALK
    else -> Kind.OTHER
}

internal data class WeekGroup(val title: String, val caption: String, val rows: List<HistoryRow>)

/** Rows (newest first) grouped by Monday-start week: "This week", "Last week", then "Week of 26 Oct". */
internal fun weekGroups(rows: List<HistoryRow>, today: LocalDate): List<WeekGroup> {
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return rows.groupBy { r ->
        (runCatching { LocalDateTime.parse(r.activity.startLocal).toLocalDate() }.getOrNull() ?: today)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.map { (monday, list) ->
        val title = when (monday) {
            thisMonday -> "This week"
            thisMonday.minusWeeks(1) -> "Last week"
            else -> "Week of ${monday.dayOfMonth} ${monday.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
        }
        WeekGroup(title, weekTotals(list.map { it.activity }), list)
    }
}

/** "3 activities · 30.2 km · 1:59" (moving time, h:mm). */
internal fun weekTotals(list: List<ActivityEntity>): String {
    val km = list.sumOf { it.distanceM ?: 0.0 } / 1000
    val sec = list.sumOf { it.movingTimeSec ?: 0 }
    return listOfNotNull(
        if (list.size == 1) "1 activity" else "${list.size} activities",
        km.takeIf { it > 0 }?.let { "%.1f km".format(it) },
        sec.takeIf { it > 0 }?.let { "%d:%02d".format(it / 3600, (it % 3600) / 60) },
    ).joinToString(" · ")
}

/**
 * The small grey label after a non-Ronde activity's name: the recording device ("Garmin Forerunner 265", "Zwift"),
 * or "Garmin" for Garmin Connect uploads without one. intervals.icu's API terms require Garmin attribution
 * wherever Garmin data is shown; Ronde rows carry the Ronde chip instead.
 */
internal fun deviceLabel(a: ActivityEntity): String? {
    if (a.isRonde) return null
    return a.deviceName?.takeIf { it.isNotBlank() } ?: "Garmin".takeIf { a.isGarmin }
}

/** Strava stubs: intervals.icu may not pass Strava data on, so only the date and name are known. */
internal const val STRAVA_STUB_LINE = "Strava activity · details not available"

/**
 * Stat line pieces: distance, moving time, then pace on foot or, on rides, power when recorded else speed, then
 * heart rate. Four pieces at most so the line fits a 412 dp phone.
 */
internal fun statLine(a: ActivityEntity): List<String> {
    val ride = activityKind(a.type) == Kind.RIDE
    val effort = when {
        ride && a.avgPower != null -> "${a.avgPower} W"
        else -> a.avgSpeed?.takeIf { it > 0.1 }?.let { if (ride) "%.1f km/h".format(it * 3.6) else Format.pace(it, PaceUnit.KM) + " /km" }
    }
    return listOfNotNull(
        a.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, PaceUnit.KM) },
        a.movingTimeSec?.let { Format.duration(it.toDouble()) },
        effort,
        a.avgHr?.let { "$it bpm" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    st: HistoryUiState,
    onOpen: (String) -> Unit = {},
    initialFilter: HistoryFilter = HistoryFilter.ALL,
    today: LocalDate = LocalDate.now(),
    onRefresh: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    val shown = st.rows.filter { filter.matches(it.activity) }
    PullToRefreshBox(isRefreshing = st.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        HistoryList(st, shown, filter, { filter = it }, today, onOpen)
    }
}

@Composable
private fun HistoryList(
    st: HistoryUiState,
    shown: List<HistoryRow>,
    filter: HistoryFilter,
    onFilter: (HistoryFilter) -> Unit,
    today: LocalDate,
    onOpen: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("History") }
        if (!st.loading && st.rows.isEmpty()) {
            item { EmptyState("No activities yet", "Workouts you finish will show up here, with the ring of how they went.") }
            return@LazyColumn
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f -> RondeFilterChip(f.label, f == filter) { onFilter(f) } }
            }
        }
        if (!st.loading && shown.isEmpty()) {
            item { EmptyText("No ${filter.label.lowercase()} activities in your history.") }
        }
        weekGroups(shown, today).forEach { g ->
            item(key = "wk-" + g.title) { SectionHeader(g.title, g.caption, Modifier.padding(top = 8.dp)) }
            items(g.rows, key = { it.activity.id }) { r -> ActivityRow(r, today, onOpen) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityRow(r: HistoryRow, today: LocalDate, onOpen: (String) -> Unit) {
    val a = r.activity
    val cs = MaterialTheme.colorScheme
    RowCard(onClick = { onOpen(a.id) }) {
        when {
            r.result != null -> ResultRing(r.result.steps, r.result.verdicts, reached = r.result.reached)
            a.isRonde -> RingDisc(emptyList())
            else -> SportDisc(sportIcon(a.type))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.isRonde) RondeBadge()
                deviceLabel(a)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(dayTimeLabel(a.startLocal, today), style = MaterialTheme.typography.bodySmall, color = cs.outline)
            val stats = statLine(a)
            if (!a.detailsAvailable) {
                Text(STRAVA_STUB_LINE, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            } else if (stats.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    stats.forEachIndexed { i, s -> Text(s, style = RondeType.stat, color = if (i == 0) cs.onSurface else cs.onSurfaceVariant, maxLines = 1) }
                }
            }
            r.result?.rounds?.let { (inRange, of) ->
                Text("$inRange of $of rounds in range", style = MaterialTheme.typography.bodySmall, color = cs.outline)
            }
        }
        Icon(RondeIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

private fun sportIcon(type: String): ImageVector = when (activityKind(type)) {
    Kind.RUN -> RondeIcons.Run
    Kind.RIDE -> RondeIcons.Bike
    Kind.WALK -> RondeIcons.Walk
    Kind.OTHER -> RondeIcons.History
}

/** "Today · 07:00 · Run · 10.0 km · 52:10" (moving time). */
internal fun activityMeta(a: ActivityEntity, today: LocalDate = LocalDate.now()): String = listOfNotNull(
    dayTimeLabel(a.startLocal, today),
    a.type.takeIf { it.isNotBlank() },
    a.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, PaceUnit.KM) },
    a.movingTimeSec?.let { Format.duration(it.toDouble()) },
).joinToString(" · ")
