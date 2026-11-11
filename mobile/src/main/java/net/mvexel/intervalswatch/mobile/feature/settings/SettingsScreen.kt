package net.mvexel.intervalswatch.mobile.feature.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mvexel.intervalswatch.mobile.AuthType
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.ui.NavRow
import net.mvexel.intervalswatch.mobile.ui.Overline

@Composable
fun SettingsRoute(vm: SettingsViewModel, onLogin: () -> Unit, onWatchScreens: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        st, onLogin = onLogin, onLogout = vm::logout, onEditSync = { vm.editSync(true) }, onCancelSync = { vm.editSync(false) },
        onSave = vm::save, onWatchScreens = onWatchScreens,
    )
}

@Composable
fun SettingsScreen(
    st: SettingsUiState,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onEditSync: () -> Unit = {},
    onCancelSync: () -> Unit = {},
    onSave: (Settings) -> Unit = {},
    onWatchScreens: () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item { Overline("Account") }
        item { AccountCard(st, onLogin = onLogin, onLogout = onLogout) }
        item { Overline("Sync") }
        item {
            if (st.editingSync) SyncSettingsForm(st.settings, onSave = onSave, onCancel = onCancelSync, busy = st.busy)
            else NavRow("Sync settings", onEditSync)
        }
        item { Overline("Watch behaviour") }
        item { NavRow("Watch screens & alerts", onWatchScreens) }
    }
}

/** Who is signed in, how, and the way out. A 401 turns it into "Log in again". */
@Composable
internal fun AccountCard(st: SettingsUiState, onLogin: () -> Unit, onLogout: () -> Unit) {
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

/** Sync options for a logged-in user. */
@Composable
private fun SyncSettingsForm(initial: Settings, onSave: (Settings) -> Unit, onCancel: () -> Unit, busy: Boolean) {
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
