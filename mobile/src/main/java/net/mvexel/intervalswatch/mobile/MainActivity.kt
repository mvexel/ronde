package net.mvexel.intervalswatch.mobile

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.shared.Format
import net.mvexel.intervalswatch.shared.Workout

data class UiState(
    val settings: Settings = Settings(),
    val workouts: List<Workout> = emptyList(),
    val watches: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    val editing: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = store.current()
            _state.update { it.copy(settings = s, editing = !s.configured) }
            refreshWatches()
            if (s.configured) sync()
        }
    }

    fun edit() = _state.update { it.copy(editing = true) }

    fun save(s: Settings) {
        viewModelScope.launch {
            store.save(s)
            SyncWorker.schedule(getApplication(), s.autoSync)
            _state.update { it.copy(settings = store.current(), editing = false) }
            sync()
        }
    }

    fun sync() {
        val s = _state.value.settings
        if (!s.configured) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Syncing…") }
            runCatching { WatchSync.syncNow(getApplication(), s) }
                .onSuccess { ws ->
                    _state.update {
                        it.copy(busy = false, workouts = ws, status = "Sent ${ws.size} workout(s) to the watch")
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, status = "Failed: ${e.message}") } }
            refreshWatches()
        }
    }

    private suspend fun refreshWatches() {
        val w = WatchSync.connectedWatches(getApplication())
        _state.update { it.copy(watches = w) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val st by vm.state.collectAsStateWithLifecycle()
                MainScreen(st, onSync = vm::sync, onEdit = vm::edit, onSave = vm::save)
            }
        }
    }
}

@Composable
internal fun MainScreen(st: UiState, onSync: () -> Unit, onEdit: () -> Unit, onSave: (Settings) -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("intervals.icu → Watch", style = MaterialTheme.typography.headlineSmall)
            if (st.editing) {
                SettingsForm(st.settings, onSave = onSave)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onSync, enabled = !st.busy) { Text("Sync now") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onEdit) { Text("Settings") }
                }
                Text(
                    if (st.watches.isEmpty()) "No watch connected (it will sync when it reconnects)"
                    else "Watch: ${st.watches.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                st.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.workouts, key = { it.id }) { WorkoutCard(it) }
                }
            }
        }
    }
}

@Composable
private fun SettingsForm(initial: Settings, onSave: (Settings) -> Unit) {
    var athlete by remember { mutableStateOf(initial.athleteId) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var days by remember { mutableStateOf(initial.daysAhead.toString()) }
    var auto by remember { mutableStateOf(initial.autoSync) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Find your API key and athlete ID in intervals.icu → Settings → Developer Settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it }, label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = athlete, onValueChange = { athlete = it }, label = { Text("Athlete ID (0 = me)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Days ahead") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = auto, onCheckedChange = { auto = it })
            Spacer(Modifier.width(8.dp))
            Text("Sync in the background every 4 hours")
        }
        Button(onClick = {
            onSave(Settings(athlete.ifBlank { "0" }, key, days.toIntOrNull()?.coerceIn(1, 28) ?: 7, auto))
        }, enabled = key.isNotBlank()) { Text("Save & sync") }
    }
}

@Composable
private fun WorkoutCard(w: Workout) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium)
            val meta = buildList {
                add(w.startLocal.replace('T', ' ').take(16))
                add(w.sport.name.lowercase())
                w.totalDurationSec?.let { add(Format.duration(it.toDouble())) }
                add(if (w.steps.size == 1) "1 step" else "${w.steps.size} steps")
            }
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
