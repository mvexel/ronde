package net.mvexel.intervalswatch.mobile.feature.history.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.ui.ResultRing
import net.mvexel.intervalswatch.mobile.ui.RondeBadge
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.mobile.ui.RondeType
import net.mvexel.intervalswatch.shared.StepKind
import net.mvexel.intervalswatch.shared.StepStatus

@Composable
fun ActivityDetailRoute(vm: ActivityDetailViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    ActivityDetailScreen(st, onBack, onRetry = vm::retry, onOpenWeb = { runCatching { uri.openUri(it) } })
}

/** Workout Detail, direction B "Paper and slab": one scrolling page. */
@Composable
fun ActivityDetailScreen(
    st: ActivityDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val c = st.content
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.weight(1f))
                if (st.loading && c != null) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
        }
        if (c == null) {
            item { if (st.error != null) FailedCard(st.error, onRetry) else LoadingSlab() }
            return@LazyColumn
        }
        if (st.error != null) item { RefreshFailedBanner(st.error, onRetry) }
        item { HeroSlab(c) }
        if (!c.detail.detailsAvailable) {
            item { NoticeCard("Details not available", STRAVA_TEXT) }
        } else {
            if (c.headline.isNotEmpty()) item { HeadlineGrid(c.headline) }
            if (c.rows.isNotEmpty()) item { StepsTable(c.rows) }
            if (c.charts.isEmpty()) item { NoticeCard("No samples", "This activity has no recorded samples, so there are no charts" + if (c.detail.plannedSteps != null) " and no plan comparison." else ".") }
            items(c.charts, key = { it.kind }) { ch -> DetailChartCard(ch, c.shading, c.hrZones, showLegend = ch === c.charts.first()) }
            c.route?.let { r -> item { RouteMapCard(r, c.headline.firstOrNull { it.label == "Distance" }?.let { "${it.value} km" }) } }
        }
        if (c.isGarmin) {
            item {
                Text(
                    "Recorded with ${c.detail.deviceName}. Garmin data via intervals.icu.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { onOpenWeb(DetailModel.intervalsUrl(c.detail.id)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
            ) { Text("Open in intervals.icu  ↗", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
        }
    }
}

internal const val STRAVA_TEXT =
    "intervals.icu can't share activities it imported from Strava with other apps, so Ronde only sees the date and name. " +
        "Connect your watch or bike computer to intervals.icu directly to see the full workout here."

@Composable
internal fun DetailCard(content: @Composable ColumnScope.() -> Unit) = Card(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

// ---- hero ----

@Composable
private fun Slab(content: @Composable ColumnScope.() -> Unit) {
    val p = RondeTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().background(p.slab, shape).let { m -> p.slabEdge?.let { m.border(1.dp, it, shape) } ?: m }.padding(20.dp),
        content = content,
    )
}

@Composable
private fun HeroSlab(c: DetailContent) {
    val p = RondeTheme.colors
    val d = c.detail
    Slab {
        Text(c.meta, style = MaterialTheme.typography.bodySmall, color = p.onSlab2)
        Text(d.name, style = RondeType.detailTitle, color = p.onSlab, modifier = Modifier.padding(top = 2.dp))
        val a = c.analysis
        if (a != null && c.ring.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val desc = c.rounds?.let { "Result ring: ${it.text} rounds in range" } ?: "Workout ring"
                ResultRing(
                    a.steps.map { it.step }, a.steps.map { it.verdict }, size = 164.dp,
                    reached = a.steps.map { it.status != StepStatus.NOT_REACHED }, contentDescription = desc,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    c.rounds?.let { r ->
                        Text(r.text, style = RondeType.hero, color = p.onSlab)
                        Text("rounds in range", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.onSlab)
                    }
                    Spacer(Modifier.height(10.dp))
                    SourceLine(c)
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            SourceLine(c)
        }
    }
}

/** Ronde chip and/or the recording device. */
@Composable
private fun SourceLine(c: DetailContent) {
    val p = RondeTheme.colors
    val device = c.detail.deviceName?.takeIf { it.isNotBlank() }
    if (!c.detail.isRonde && device == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (c.detail.isRonde) {
            RondeBadge()
            Spacer(Modifier.width(8.dp))
        }
        if (device != null) Text(device, style = MaterialTheme.typography.bodySmall, color = p.onSlab2)
    }
}

@Composable
private fun LoadingSlab() {
    Slab {
        Box(Modifier.size(width = 140.dp, height = 12.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(width = 220.dp, height = 26.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Loading activity" }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RondeTheme.colors.work)
        }
    }
}

@Composable
private fun FailedCard(message: String, onRetry: () -> Unit) = DetailCard {
    Column(Modifier.padding(16.dp)) {
        Text("Couldn't load this activity", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") }
    }
}

@Composable
private fun RefreshFailedBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Showing the saved copy. $message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun NoticeCard(title: String, body: String) = DetailCard {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---- headline numbers ----

@Composable
private fun HeadlineGrid(cells: List<Headline>) = DetailCard {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { h ->
                    Column(Modifier.weight(1f)) {
                        Text(h.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            buildAnnotatedString {
                                append(h.value)
                                h.unit?.let { u ->
                                    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                        append(if (u.startsWith("/")) u else " $u")
                                    }
                                }
                            },
                            style = RondeType.numberL,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- steps table ----

private val TargetW = 76.dp
private val AvgW = 40.dp
private val HrW = 32.dp
private val InRangeW = 70.dp
private val DiscW = 30.dp

@Composable
private fun StepsTable(rows: List<StepRow>) = DetailCard {
    val ink3 = RondeTheme.colors.ink3
    Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("plan vs actual", style = MaterialTheme.typography.bodySmall, color = ink3)
        }
        Spacer(Modifier.height(10.dp))
        val head = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink3)
        Row(Modifier.padding(bottom = 6.dp)) {
            Spacer(Modifier.width(DiscW))
            Text("Step", style = head, modifier = Modifier.weight(1f))
            Text("Target", style = head, modifier = Modifier.width(TargetW))
            Text("Avg", style = head, modifier = Modifier.width(AvgW))
            Text("HR", style = head, modifier = Modifier.width(HrW))
            Text("In range", style = head, modifier = Modifier.width(InRangeW))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        rows.forEach { StepRowView(it) }
    }
}

@Composable
private fun StepRowView(r: StepRow) {
    val p = RondeTheme.colors
    val ink = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val work = r.kind == StepKind.ACTIVE
    val body = TextStyle(fontSize = 13.5.sp, fontFeatureSettings = "tnum", color = ink)
    Column(Modifier.padding(top = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepDisc(r, Modifier.width(DiscW))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = body.copy(fontWeight = if (work) FontWeight.SemiBold else FontWeight.Normal, color = if (work) ink else ink2), maxLines = 1)
                val sub = listOfNotNull(r.subtitle, "partial".takeIf { r.status == StepStatus.PARTIAL }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = TextStyle(fontSize = 11.5.sp, color = p.ink3, fontFeatureSettings = "tnum"), maxLines = 1)
            }
            Text(r.target, style = body.copy(color = if (r.target == "easy") ink2 else ink), modifier = Modifier.width(TargetW), maxLines = 1)
            if (r.status == StepStatus.NOT_REACHED) {
                Text("not reached", style = TextStyle(fontSize = 12.sp, color = p.ink3), modifier = Modifier.width(AvgW + HrW + InRangeW))
            } else {
                Text(
                    r.avg ?: "—",
                    style = if (work) RondeType.numberM.copy(fontSize = 15.sp, lineHeight = 17.sp).copy(color = ink) else body.copy(color = ink2),
                    modifier = Modifier.width(AvgW), maxLines = 1,
                )
                Text(r.hr ?: "—", style = body.copy(color = ink2), modifier = Modifier.width(HrW), maxLines = 1)
                InRangeCell(r, Modifier.width(InRangeW))
            }
        }
        r.correction?.let { c ->
            val color = if (c.off == Off.OVER) p.paceFast else p.paceSlow
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = color)) { append(c.chevron) }
                    append(" ${c.amount} ")
                    withStyle(SpanStyle(color = color)) { append(c.word) }
                },
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink, fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(start = DiscW, top = 2.dp),
            )
        }
    }
}

/** Black disc: amber rep number for work, a short dash in the kind colour for the rest. */
@Composable
private fun StepDisc(r: StepRow, modifier: Modifier) = Box(modifier, contentAlignment = Alignment.CenterStart) {
    val p = RondeTheme.colors
    Box(Modifier.size(22.dp).background(RondeTheme.colors.ringDisc, CircleShape), contentAlignment = Alignment.Center) {
        if (r.rep != null) Text(r.rep.toString(), style = RondeType.ringNumber.copy(fontSize = 12.sp, lineHeight = 13.sp), color = p.work)
        else Box(Modifier.size(width = 10.dp, height = 3.dp).background(p.kind(r.kind), RoundedCornerShape(1.5.dp)))
    }
}

/** Ink bar on a track (no hue: being in range is the norm) and the time in range. */
@Composable
private fun InRangeCell(r: StepRow, modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    val f = r.inRangeFraction
    if (f == null) {
        Text("—", style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
        return@Row
    }
    Box(Modifier.size(width = 28.dp, height = 5.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0.04f, 1f)).height(5.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp)))
    }
    Spacer(Modifier.width(5.dp))
    Text(r.inRangeText ?: "", style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurface), maxLines = 1)
}
