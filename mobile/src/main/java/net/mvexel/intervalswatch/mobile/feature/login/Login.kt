package net.mvexel.intervalswatch.mobile.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.mvexel.intervalswatch.mobile.AuthType
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.session.Session
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingActions
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingsViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingsList
import net.mvexel.intervalswatch.mobile.ui.RingDisc
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.mobile.ui.RondeType
import net.mvexel.intervalswatch.shared.Samples

data class LoginUiState(
    val settings: Settings = Settings(),
    val busy: Boolean = false,
    val status: String? = null,
    /** "Use API key instead" was pressed. */
    val apiKeyOpen: Boolean = false,
) {
    override fun toString() = "LoginUiState(settings=$settings, busy=$busy, apiKeyOpen=$apiKeyOpen)"
}

/** Shown instead of the tabs while signed out. The OAuth Custom Tab is opened by the activity. */
class LoginViewModel(private val session: Session) : ViewModel() {
    private val apiKeyOpen = MutableStateFlow(false)

    val state: StateFlow<LoginUiState> = combine(session.state, apiKeyOpen) { st, open -> LoginUiState(st.settings, st.busy, st.status, open) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LoginUiState())

    fun openApiKey() {
        apiKeyOpen.value = true
    }

    /** Blank athlete means "0", the key's own athlete. */
    fun saveApiKey(athleteId: String, key: String) {
        if (key.isBlank()) return
        apiKeyOpen.value = false
        session.save(state.value.settings.copy(athleteId = athleteId.ifBlank { "0" }, token = key, authType = AuthType.API_KEY))
    }
}

@Composable
fun LoginRoute(vm: LoginViewModel, recordings: RecordingsViewModel, actions: RecordingActions, onLogin: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val recs by recordings.items.collectAsStateWithLifecycle()
    LoginScreen(st, recs, onLogin = onLogin, onApiKey = vm::openApiKey, onSaveApiKey = vm::saveApiKey, actions = actions.copy(onRetry = recordings::retry))
}

/** First run and logged-out state: one login button; the API key form hides behind "Use API key instead". */
@Composable
fun LoginScreen(
    st: LoginUiState,
    recordings: List<RecordingItem> = emptyList(),
    onLogin: () -> Unit = {},
    onApiKey: () -> Unit = {},
    onSaveApiKey: (athleteId: String, key: String) -> Unit = { _, _ -> },
    actions: RecordingActions = RecordingActions(),
) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OnboardingPlate() }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLogin, enabled = !st.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Log in with intervals.icu")
                }
                if (!st.apiKeyOpen) {
                    TextButton(onClick = onApiKey, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use API key instead") }
                } else {
                    ApiKeyForm(st.settings, onSave = onSaveApiKey, busy = st.busy)
                }
                st.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // Kept recordings stay reachable after a logout (T10 L2).
        if (recordings.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    RecordingsList(recordings, actions)
                }
            }
        }
    }
}

/**
 * The amber plate: allowed on onboarding only (design.md, B with C's plate borrowed for onboarding). Ink on
 * amber 11.5:1, ink2 on amber 7.1:1; the ring is the launcher icon, full size.
 */
@Composable
private fun OnboardingPlate() {
    val c = RondeTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.plate, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .padding(horizontal = 24.dp).padding(top = 40.dp, bottom = 28.dp),
    ) {
        RingDisc(Samples.workouts.first().steps, size = 96.dp)
        Text("Ronde", style = RondeType.hero, color = c.plateInk, modifier = Modifier.padding(top = 20.dp))
        Text("for intervals.icu", style = MaterialTheme.typography.titleMedium, color = c.plateInk)
        Text(
            "Your planned workouts from intervals.icu, on your phone and your Wear OS watch. Ronde works without a watch too.",
            style = MaterialTheme.typography.bodyMedium, color = c.plateInk2, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ApiKeyForm(initial: Settings, onSave: (String, String) -> Unit, busy: Boolean) {
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
        Button(onClick = { onSave(athlete, key) }, enabled = key.isNotBlank() && !busy) { Text("Save & sync") }
    }
}
