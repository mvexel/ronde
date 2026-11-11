package net.mvexel.intervalswatch.mobile.feature.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.mobile.ExportFormat
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.StatusIcon
import net.mvexel.intervalswatch.mobile.UploadState
import net.mvexel.intervalswatch.mobile.data.RecordingsSource
import net.mvexel.intervalswatch.mobile.isOpenRecording
import net.mvexel.intervalswatch.mobile.ui.Overline
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard

/** Share and Save need an Activity (chooser, document picker), so the activity provides them. */
data class RecordingActions(
    val onShare: (RecordingItem, ExportFormat) -> Unit = { _, _ -> },
    val onSave: (RecordingItem, ExportFormat) -> Unit = { _, _ -> },
    val onRetry: (RecordingItem) -> Unit = {},
)

/** Recordings kept on the phone, newest first. Shown on the Watch tab and, after a logout, on the login screen. */
class RecordingsViewModel(private val source: RecordingsSource) : ViewModel() {
    private val _items = MutableStateFlow<List<RecordingItem>>(emptyList())
    val items: StateFlow<List<RecordingItem>> = _items.asStateFlow()

    init {
        // An upload that finishes while the app is open updates its row.
        viewModelScope.launch { source.changes.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch { _items.value = source.recent() }
    }

    fun retry(item: RecordingItem) {
        viewModelScope.launch { source.retry(item.fileName) }
    }
}

fun LazyListScope.recordingsSection(recordings: List<RecordingItem>, actions: RecordingActions) {
    if (recordings.isEmpty()) return
    item { Overline("Recent uploads") }
    item {
        SurfaceCard {
            recordings.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                RecordingRow(r, actions.onShare, actions.onSave, actions.onRetry)
            }
        }
    }
}

@Composable
internal fun RecordingRow(
    r: RecordingItem,
    onShare: (RecordingItem, ExportFormat) -> Unit,
    onSave: (RecordingItem, ExportFormat) -> Unit,
    onRetry: (RecordingItem) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(r.state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                val when_ = r.startEpochSec?.let {
                    java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)
                }
                Text(
                    listOfNotNull(if (isOpenRecording(r)) "Open (not planned)" else null, when_, "${(r.sizeBytes + 512) / 1024} kB").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (r.state) {
                        UploadState.UPLOADED -> "Saved to intervals.icu"
                        UploadState.PENDING -> "Waiting to upload"
                        UploadState.FAILED -> "Not uploaded" + r.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.state == UploadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row {
            if (r.state == UploadState.FAILED) TextButton(onClick = { onRetry(r) }) { Text("Retry") }
            FormatMenu("Share") { onShare(r, it) }
            FormatMenu("Save") { onSave(r, it) }
        }
    }
}

@Composable
private fun FormatMenu(label: String, onPick: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { f ->
                DropdownMenuItem(text = { Text("$label ${f.name}") }, onClick = { open = false; onPick(f) })
            }
        }
    }
}
