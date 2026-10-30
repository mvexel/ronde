package net.mvexel.intervalswatch.mobile

import android.app.Application
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import net.mvexel.intervalswatch.shared.Samples
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
import net.mvexel.intervalswatch.shared.ScreenSettings
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
    /** Recordings kept on the phone, newest first. */
    val recordings: List<RecordingItem> = emptyList(),
    /** The "Watch screens & alerts" screen is open. */
    val watchScreens: Boolean = false,
    /** Local time of the last successful sync, e.g. "07:12". */
    val lastSynced: String? = null,
) {
    // Settings.toString already hides the token; keep the whole state out of logs anyway.
    override fun toString() = "UiState(settings=$settings, workouts=${workouts.size}, busy=$busy, editing=$editing, authExpired=$authExpired)"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = store.current()
            _state.update { it.copy(settings = s) }
            refreshRecordings()
            if (!s.configured) WatchSync.pushLoggedIn(getApplication(), false)
            refreshWatches()
            if (s.configured) sync()
        }
    }

    private val library = RecordingLibrary.of(app)

    init {
        // An upload that finishes while the app is open updates its row.
        viewModelScope.launch { RecordingLibrary.changes.collect { refreshRecordings() } }
    }

    fun retryRecording(item: RecordingItem) {
        viewModelScope.launch { withContext(Dispatchers.IO) { UploadWorker.retry(getApplication(), item.fileName) } }
    }

    fun refreshRecordings() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { library.recent() }
            _state.update { it.copy(recordings = r) }
        }
    }

    private val pending = PendingLoginStore(app)

    /** Starts a login: persists verifier and state, then returns what the Custom Tab needs to open. */
    suspend fun beginLogin(): PendingLogin = pending.start()

    /** Handles a redirect. Without a pending login (stale, replayed or foreign) it is ignored. */
    fun handleRedirect(uri: String?) {
        if (!isLoginRedirect(uri)) return
        viewModelScope.launch {
            val p = pending.takeMatching(uri!!) ?: return@launch
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

    fun showStatus(msg: String) = _state.update { it.copy(status = msg) }

    fun edit() = _state.update { it.copy(editing = true) }

    fun openWatchScreens(open: Boolean) = _state.update { it.copy(watchScreens = open) }

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
            if (store.current().configured) withContext(Dispatchers.IO) { UploadWorker.retryPending(getApplication()) }
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
                        it.copy(busy = false, workouts = ws, status = "Sent ${ws.size} workout(s) to the watch", lastSynced = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
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

/** CreateDocument with the MIME type chosen per call (the stock contract fixes it at construction). */
private class CreateExport : ActivityResultContracts.CreateDocument("*/*") {
    var mime = "*/*"
    override fun createIntent(context: Context, input: String) = super.createIntent(context, input).setType(mime)
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val createExport = CreateExport()
    private var pendingSave: Pair<String, ExportFormat>? = null // restored in onCreate: the picker can outlive the activity
    private val saveLauncher = registerForActivityResult(createExport) { uri -> uri?.let(::writeExport) }

    private val saved = Bundle()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putAll(saved)
    }

    private fun writeExport(uri: Uri) {
        val (fileName, format) = pendingSave ?: run {
            Toast.makeText(this, "Could not save", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = RecordingLibrary.of(this@MainActivity).export(fileName, format)
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(this@MainActivity, if (ok) "Saved" else "Could not save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(item: RecordingItem, format: ExportFormat) {
        pendingSave = item.fileName to format
        saved.putString(KEY_SAVE_NAME, item.fileName); saved.putString(KEY_SAVE_FORMAT, format.name)
        createExport.mime = format.mime
        saveLauncher.launch(RecordingLibrary.of(this).exportName(item.fileName, format))
    }

    private fun share(item: RecordingItem, format: ExportFormat) {
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) { runCatching { RecordingLibrary.shareIntent(this@MainActivity, item.fileName, format) }.getOrNull() }
            if (intent != null) startActivity(intent) else Toast.makeText(this@MainActivity, "Could not share", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val KEY_SAVE_NAME = "save_name"
        private const val KEY_SAVE_FORMAT = "save_format"
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRecordings() // uploads finish while the app is in the background
    }

    private val screenSettings by lazy { ScreenSettingsStore(this) }

    override fun onStart() {
        super.onStart()
        screenSettings.startListening()
        lifecycleScope.launch { screenSettings.load() }
    }

    override fun onStop() {
        screenSettings.stopListening()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { b ->
            val n = b.getString(KEY_SAVE_NAME); val f = b.getString(KEY_SAVE_FORMAT)?.let { runCatching { ExportFormat.valueOf(it) }.getOrNull() }
            if (n != null && f != null) { pendingSave = n to f; createExport.mime = f.mime; saved.putAll(b) }
        }
        // After process death the redirect starts a fresh instance; the pending login is read from storage.
        if (savedInstanceState == null) vm.handleRedirect(intent?.dataString)
        setContent {
            RunbumTheme {
                val st by vm.state.collectAsStateWithLifecycle()
                val screens by screenSettings.settings.collectAsStateWithLifecycle()
                MainScreen(
                    st, screens = screens,
                    onEditScreens = { edit -> lifecycleScope.launch { screenSettings.update(edit) } },
                    onTestBuzz = { lifecycleScope.launch { WatchSync.testBuzz(this@MainActivity, screens.buzzStrength) } },
                    onWatchScreens = vm::openWatchScreens,
                    onSync = vm::sync, onEdit = vm::edit, onSave = vm::save, onLogout = vm::logout, onClose = vm::closeEditor,
                    onShareRecording = ::share, onSaveRecording = ::save, onRetryRecording = vm::retryRecording,
                    onLogin = {
                        lifecycleScope.launch {
                            if (!launchLoginTab(this@MainActivity, vm.beginLogin())) vm.showStatus("No browser found to log in with")
                        }
                    },
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
    screens: ScreenSettings = ScreenSettings(),
    onEditScreens: ((ScreenSettings) -> ScreenSettings) -> Unit = {},
    onTestBuzz: () -> Unit = {},
    onWatchScreens: (Boolean) -> Unit = {},
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onSave: (Settings) -> Unit,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onClose: () -> Unit = {},
    onShareRecording: (RecordingItem, ExportFormat) -> Unit = { _, _ -> },
    onSaveRecording: (RecordingItem, ExportFormat) -> Unit = { _, _ -> },
    onRetryRecording: (RecordingItem) -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkoutRingDisc(st.workouts.firstOrNull()?.steps ?: Samples.workouts.first().steps, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Ronde", style = MaterialTheme.typography.headlineSmall)
                    Text("for intervals.icu", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!st.settings.configured) {
                // Kept recordings stay reachable after a logout (T10 L2).
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { LoginScreen(st, onLogin = onLogin, onSave = onSave, onAdvanced = onEdit) }
                    recordingsSection(st, onShareRecording, onSaveRecording, onRetryRecording)
                }
            } else if (st.watchScreens) {
                WatchScreensScreen(screens, st.watches.isNotEmpty(), onEditScreens, onTestBuzz, onBack = { onWatchScreens(false) })
            } else if (st.editing) {
                SettingsForm(st.settings, onSave = onSave, onCancel = onClose, busy = st.busy)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { WatchCard(st, onSync) }
                    item { Overline("Next up") }
                    item {
                        if (st.workouts.isEmpty()) {
                            Text("No planned workouts yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                st.workouts.forEachIndexed { i, w ->
                                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                    WorkoutRow(w)
                                }
                            }
                        }
                    }
                    recordingsSection(st, onShareRecording, onSaveRecording, onRetryRecording)
                    item { NavRow("Watch screens & alerts") { onWatchScreens(true) } }
                    item { NavRow("Sync settings", onEdit) }
                    item { AccountCard(st, onLogin = onLogin, onLogout = onLogout) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recordingsSection(
    st: UiState,
    onShare: (RecordingItem, ExportFormat) -> Unit,
    onSave: (RecordingItem, ExportFormat) -> Unit,
    onRetry: (RecordingItem) -> Unit,
) {
    if (st.recordings.isEmpty()) return
    item { Overline("Recent uploads") }
    item {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            st.recordings.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                RecordingRow(r, onShare, onSave, onRetry)
            }
        }
    }
}

@Composable
private fun Overline(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

/** "On your watch · Pixel Watch 4 · synced 07:12" with Sync as an ink pill (ux F2). */
@Composable
private fun WatchCard(st: UiState, onSync: () -> Unit) {
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
private fun WorkoutRow(w: Workout) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkoutRingDisc(w.steps)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(w.name, style = MaterialTheme.typography.titleMedium)
            Text(workoutMeta(w), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            planLine(w).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** First run and logged-out state: one login button; the API key form hides behind "Use API key instead". */
@Composable
private fun LoginScreen(st: UiState, onLogin: () -> Unit, onSave: (Settings) -> Unit, onAdvanced: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLogin, enabled = !st.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth()) { Text("Log in with intervals.icu") }
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
                Text(listOfNotNull(if (isOpenRecording(r)) "Open (not planned)" else null, when_, "${(r.sizeBytes + 512) / 1024} kB").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
