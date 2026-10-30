package net.mvexel.intervalswatch.mobile

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
import net.mvexel.intervalswatch.mobile.ui.RunbumTheme
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
    /** The settings form (or, when logged out, the API key form) is open. */
    val editing: Boolean = false,
    /** intervals.icu answered 401: the user has to log in again. */
    val authExpired: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = store.current()
            _state.update { it.copy(settings = s) }
            if (!s.configured) WatchSync.pushLoggedIn(getApplication(), false)
            refreshWatches()
            if (s.configured) sync()
        }
    }

    private val pending = PendingLoginStore(app)

    /** Starts a login: persists verifier and state, then returns what the Custom Tab needs to open. */
    suspend fun beginLogin(): PendingLogin = pending.start()

    /** Handles a redirect. Without a pending login (stale, replayed or foreign) it is ignored. */
    fun handleRedirect(uri: String?) {
        if (!isLoginRedirect(uri)) return
        viewModelScope.launch {
            val p = pending.take() ?: return@launch
            when (val r = OAuth.parseRedirect(uri!!, p.state)) {
                is RedirectResult.Failure -> _state.update { it.copy(status = loginError(r.error)) }
                is RedirectResult.Code -> {
                    _state.update { it.copy(busy = true, status = "Logging in…") }
                    runCatching { OAuth.exchange(r.code, p.verifier, OAUTH_CLIENT_ID) }
                        .onSuccess { t ->
                            val old = store.current()
                            save(old.copy(token = t.accessToken, athleteId = t.athleteId, athleteName = t.athleteName, authType = AuthType.OAUTH))
                        }
                        .onFailure { e -> _state.update { it.copy(busy = false, status = e.message ?: "Login failed") } }
                }
            }
        }
    }

    private fun loginError(e: OAuthError) = when (e) {
        OAuthError.AccessDenied -> "Login cancelled"
        OAuthError.StateMismatch -> "Login failed: response did not match the request"
        OAuthError.MissingCode -> "Login failed: no code returned"
    }

    fun edit() = _state.update { it.copy(editing = true) }

    fun closeEditor() = _state.update { it.copy(editing = false) }

    /** Revokes the token, clears credentials, and tells the watch. Queued uploads stay on disk. */
    fun logout() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            store.logout()
            SyncWorker.schedule(getApplication(), false)
            WatchSync.pushLoggedIn(getApplication(), false)
            _state.update { it.copy(settings = Settings(), workouts = emptyList(), busy = false, editing = false, authExpired = false, status = null) }
        }
    }

    fun save(s: Settings) {
        viewModelScope.launch {
            store.save(s)
            SyncWorker.schedule(getApplication(), s.autoSync)
            _state.update { it.copy(settings = store.current(), editing = false, authExpired = false) }
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
                .onFailure { e ->
                    _state.update {
                        it.copy(busy = false, status = "Failed: ${e.message}", authExpired = e is AuthExpiredException || it.authExpired)
                    }
                }
            refreshWatches()
        }
    }

    private suspend fun refreshWatches() {
        val w = WatchSync.connectedWatches(getApplication())
        _state.update { it.copy(watches = w) }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After process death the redirect starts a fresh instance; the pending login is read from storage.
        if (savedInstanceState == null) vm.handleRedirect(intent?.dataString)
        setContent {
            RunbumTheme {
                val st by vm.state.collectAsStateWithLifecycle()
                MainScreen(
                    st, onSync = vm::sync, onEdit = vm::edit, onSave = vm::save, onLogout = vm::logout, onClose = vm::closeEditor,
                    onLogin = { lifecycleScope.launch { launchLoginTab(this@MainActivity, vm.beginLogin()) } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleRedirect(intent.dataString)
    }
}

@Composable
internal fun MainScreen(
    st: UiState,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onSave: (Settings) -> Unit,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("Ronde", style = MaterialTheme.typography.headlineSmall)
                Text("for intervals.icu", style = MaterialTheme.typography.bodySmall)
            }
            if (!st.settings.configured) {
                LoginScreen(st, onLogin = onLogin, onSave = onSave, onAdvanced = onEdit)
            } else if (st.editing) {
                SettingsForm(st.settings, onSave = onSave, onCancel = onClose, busy = st.busy)
            } else {
                AccountCard(st, onLogin = onLogin, onLogout = onLogout)
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

/** First run and logged-out state: one login button; the API key form hides behind "Use API key instead". */
@Composable
private fun LoginScreen(st: UiState, onLogin: () -> Unit, onSave: (Settings) -> Unit, onAdvanced: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLogin, enabled = !st.busy, modifier = Modifier.fillMaxWidth()) { Text("Log in with intervals.icu") }
        if (!st.editing) {
            TextButton(onClick = onAdvanced) { Text("Use API key instead") }
        } else {
            ApiKeyForm(st.settings, onSave = onSave, busy = st.busy)
        }
        st.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Who is signed in, how, and the way out. A 401 turns it into "Log in again". */
@Composable
private fun AccountCard(st: UiState, onLogin: () -> Unit, onLogout: () -> Unit) {
    val s = st.settings
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.authType == AuthType.OAUTH) {
                Text(s.athleteName.ifBlank { "intervals.icu" }, style = MaterialTheme.typography.titleMedium)
                Text("Athlete ${s.athleteId}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Signed in with API key", style = MaterialTheme.typography.titleMedium)
            }
            if (st.authExpired) {
                Text(
                    "intervals.icu no longer accepts this login. Uploads stay queued until you log in again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.authExpired) {
                    Button(onClick = onLogin, enabled = !st.busy) { Text("Log in again") }
                } else if (s.authType == AuthType.API_KEY) {
                    Button(onClick = onLogin, enabled = !st.busy) { Text("Log in with intervals.icu") }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onLogout, enabled = !st.busy) { Text("Log out") }
            }
        }
    }
}

@Composable
private fun ApiKeyForm(initial: Settings, onSave: (Settings) -> Unit, busy: Boolean) {
    var athlete by remember { mutableStateOf(initial.athleteId) }
    var key by remember { mutableStateOf(initial.token) }
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
        Button(onClick = {
            onSave(initial.copy(athleteId = athlete.ifBlank { "0" }, token = key, authType = AuthType.API_KEY))
        }, enabled = key.isNotBlank() && !busy) { Text("Save & sync") }
    }
}

/** Sync options for a logged-in user; the key fields only appear for a pasted key. */
@Composable
private fun SettingsForm(initial: Settings, onSave: (Settings) -> Unit, onCancel: () -> Unit, busy: Boolean) {
    var days by remember { mutableStateOf(initial.daysAhead.toString()) }
    var auto by remember { mutableStateOf(initial.autoSync) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Days ahead") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = auto, onCheckedChange = { auto = it })
            Spacer(Modifier.width(8.dp))
            Text("Sync in the background every 4 hours")
        }
        Row {
            Button(onClick = {
                onSave(initial.copy(daysAhead = days.toIntOrNull()?.coerceIn(1, 28) ?: 7, autoSync = auto))
            }, enabled = !busy) { Text("Save & sync") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
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
