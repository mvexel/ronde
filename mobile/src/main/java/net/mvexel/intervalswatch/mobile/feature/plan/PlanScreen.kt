package net.mvexel.intervalswatch.mobile.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.data.plan.PlanItem
import net.mvexel.intervalswatch.mobile.planLine
import net.mvexel.intervalswatch.mobile.ui.CardShape
import net.mvexel.intervalswatch.mobile.ui.EmptyState
import net.mvexel.intervalswatch.mobile.ui.RingDisc
import net.mvexel.intervalswatch.mobile.ui.RondeIcons
import net.mvexel.intervalswatch.mobile.ui.RondeSwitch
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.mobile.ui.RondeType
import net.mvexel.intervalswatch.mobile.ui.ScreenHeader
import net.mvexel.intervalswatch.mobile.ui.Slab
import net.mvexel.intervalswatch.shared.Samples
import net.mvexel.intervalswatch.shared.Sport
import net.mvexel.intervalswatch.shared.Workout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun PlanRoute(vm: PlanViewModel, onOpen: (Long) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    PlanScreen(st, onOpen = onOpen, onTogglePinned = vm::togglePinned, onToggleHidden = vm::toggleHidden, onToggleShowHidden = vm::toggleShowHidden, onSync = vm::sync)
}

internal fun Workout.date(): LocalDate? = runCatching { LocalDateTime.parse(startLocal).toLocalDate() }.getOrNull()

private fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun dayMonth(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

/** Agenda days: every day with a workout plus today, in date order; each day's workouts in time order. */
internal fun agendaDays(items: List<PlanItem>, today: LocalDate): List<Pair<LocalDate, List<PlanItem>>> {
    val byDay = items.sortedBy { it.workout.startLocal }.groupBy { it.workout.date() ?: today }.toMutableMap()
    byDay.putIfAbsent(today, emptyList())
    return byDay.toSortedMap().toList()
}

/**
 * The Plan tab (direction B): the "On your watch" slab in watch order, the week strip, and an agenda of every
 * planned workout with an on-watch switch. Long-press a row to pin it.
 */
@Composable
fun PlanScreen(
    st: PlanUiState,
    onOpen: (Long) -> Unit = {},
    onTogglePinned: (PlanItem) -> Unit = {},
    onToggleHidden: (PlanItem) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleShowHidden: () -> Unit = {},
    onSync: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val all = st.visible + st.hidden
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                "Plan",
                leading = { RingDisc(Samples.workouts.first().steps, size = 32.dp) },
                actions = {
                    IconButton(onClick = onSync, enabled = !st.busy) {
                        Icon(RondeIcons.Sync, contentDescription = "Sync now", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
            )
        }
        item { WatchSlab(st, today) }
        item { WeekStrip(today, all.mapNotNull { it.workout.date() }.toSet()) }
        if (all.isEmpty()) {
            item { EmptyState("No planned workouts yet", "Add workouts to your intervals.icu calendar; Ronde picks them up on the next sync.") }
            return@LazyColumn
        }
        agendaDays(all, today).forEach { (day, list) ->
            item(key = "d-$day") { DayHeader(day, today) }
            if (list.isEmpty()) {
                item(key = "rest-$day") { RestDay() }
            }
            items(list, key = { it.workout.id }) { PlanRow(it, onOpen, onTogglePinned, onToggleHidden) }
        }
    }
}

/** "On your watch" in watch order (pinned first), or the no-watch slab. */
@Composable
private fun WatchSlab(st: PlanUiState, today: LocalDate) {
    val c = RondeTheme.colors
    Slab {
        if (st.watches.isEmpty()) {
            Text("No watch paired · Ronde works without one", style = MaterialTheme.typography.titleMedium, color = c.onSlab)
            Text(
                "Your plan still syncs from intervals.icu. Pair a Wear OS watch to run these workouts with Ronde.",
                style = MaterialTheme.typography.bodySmall, color = c.onSlab2, modifier = Modifier.padding(top = 2.dp),
            )
            return@Slab
        }
        Text("On your watch", style = MaterialTheme.typography.titleMedium, color = c.onSlab)
        Text(
            listOfNotNull(
                if (st.visible.size == 1) "1 workout" else "${st.visible.size} workouts",
                st.watches.joinToString(),
                st.lastSynced?.let { "synced $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = c.onSlab2,
        )
        if (st.visible.isEmpty()) {
            Text("Nothing on the watch: every workout is switched off.", style = MaterialTheme.typography.bodySmall, color = c.onSlab2, modifier = Modifier.padding(top = 8.dp))
            return@Slab
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            st.visible.take(4).forEachIndexed { i, item -> WatchSlot(i + 1, item, today, Modifier.weight(1f)) }
            repeat(4 - st.visible.size.coerceAtMost(4)) { Spacer(Modifier.weight(1f)) }
        }
        if (st.visible.size > 4) {
            Text("+${st.visible.size - 4} more on the watch", style = MaterialTheme.typography.bodySmall, color = c.onSlab2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun WatchSlot(n: Int, item: PlanItem, today: LocalDate, modifier: Modifier) {
    val c = RondeTheme.colors
    val w = item.workout
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RingDisc(w.steps, size = 60.dp) { Text("$n", style = RondeType.ringNumber, color = c.onSlab) }
        Row(Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.pinned) Icon(RondeIcons.Pin, contentDescription = "Pinned", tint = c.onSlab, modifier = Modifier.size(13.dp))
            Text(w.name, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = c.onSlab, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (item.pinned) "pinned" else w.date()?.let { if (it == today) "today" else short(it.dayOfWeek) } ?: "",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = c.onSlab3,
        )
    }
}

/** "November ... from intervals.icu · read-only", then Mon-Sun of this week; today is the black disc. */
@Composable
private fun WeekStrip(today: LocalDate, planned: Set<LocalDate>) {
    val c = RondeTheme.colors
    val cs = MaterialTheme.colorScheme
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("from intervals.icu · read-only", style = MaterialTheme.typography.bodySmall, color = cs.outline)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            (0L until 7L).map { monday.plusDays(it) }.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(short(d.dayOfWeek).take(1), style = MaterialTheme.typography.labelMedium, color = cs.outline)
                    val isToday = d == today
                    Box(
                        Modifier.padding(top = 4.dp, bottom = 2.dp).size(36.dp)
                            .then(if (isToday) Modifier.background(c.slab, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${d.dayOfMonth}", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            color = if (isToday) c.onBadge else cs.onBackground, textAlign = TextAlign.Center,
                        )
                    }
                    Box(Modifier.height(6.dp), contentAlignment = Alignment.Center) {
                        if (d in planned && !d.isEqual(today)) Box(Modifier.size(5.dp).background(cs.outline, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 4.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (day == today) {
            Text("Today", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = RondeTheme.colors.brandText)
            Text("${short(day.dayOfWeek)} ${dayMonth(day)}", style = MaterialTheme.typography.bodySmall, color = cs.outline)
        } else {
            Text(short(day.dayOfWeek), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = cs.onBackground)
            Text(dayMonth(day), style = MaterialTheme.typography.bodySmall, color = cs.outline)
        }
    }
}

@Composable
private fun RestDay() {
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("Rest day · nothing planned", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** "40 min · ~7.4 km". */
internal fun planSize(w: Workout): String = listOfNotNull(
    w.totalDurationSec?.let { "${(it + 30) / 60} min" },
    w.totalDistanceM?.takeIf { it > 0 }?.let { "~%.1f km".format(it / 1000) },
).joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(item: PlanItem, onOpen: (Long) -> Unit, onTogglePinned: (PlanItem) -> Unit, onToggleHidden: (PlanItem) -> Unit) {
    val w = item.workout
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = cs.surface, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Row(
            Modifier.combinedClickable(onClick = { onOpen(w.id) }, onLongClick = { menu = true }, onLongClickLabel = "Pin or unpin")
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingDisc(w.steps, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.pinned) Icon(RondeIcons.Pin, contentDescription = "Pinned", tint = cs.onSurface, modifier = Modifier.size(16.dp))
                    Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(sportIcon(w.sport), contentDescription = w.sport.name.lowercase(), tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(planSize(w) + if (w.indoor) " · indoor" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                planLine(w).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RondeSwitch(checked = !item.hidden, onCheckedChange = { onToggleHidden(item) })
                Text("on watch", style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal), color = cs.outline)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (item.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onTogglePinned(item) })
                DropdownMenuItem(text = { Text(if (item.hidden) "Show on watch" else "Hide from watch") }, onClick = { menu = false; onToggleHidden(item) })
            }
        }
    }
}

private fun sportIcon(s: Sport) = when (s) {
    Sport.RIDE -> RondeIcons.Bike
    Sport.WALK -> RondeIcons.Walk
    else -> RondeIcons.Run
}
