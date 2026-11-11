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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import net.mvexel.intervalswatch.mobile.ui.RondeSwitch
import net.mvexel.intervalswatch.mobile.ui.ScreenHeader
import net.mvexel.intervalswatch.mobile.ui.SectionHeader
import net.mvexel.intervalswatch.mobile.ui.SurfaceCard

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
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader("Settings") }
        item { SectionHeader("Account") }
        item { AccountCard(st, onLogin = onLogin, onLogout = onLogout) }
        item { SectionHeader("Sync", modifier = Modifier.padding(top = 12.dp)) }
        item {
            if (st.editingSync) SyncSettingsForm(st.settings, onSave = onSave, onCancel = onCancelSync, busy = st.busy)
            else NavRow("Sync settings", onEditSync, caption = syncCaption(st.settings))
        }
        item { SectionHeader("Watch behaviour", modifier = Modifier.padding(top = 12.dp)) }
        item { NavRow("Watch screens & alerts", onWatchScreens, caption = "Fields, pace window, buzz strength") }
    }
}

/** "7 days ahead · every 4 hours" */
internal fun syncCaption(s: Settings): String =
    listOf("${s.daysAhead} days ahead", if (s.autoSync) "every 4 hours" else "manual only").joinToString(" · ")

/** Who is signed in, how, and the way out. A 401 turns it into "Log in again". */
@Composable
internal fun AccountCard(st: SettingsUiState, onLogin: () -> Unit, onLogout: () -> Unit) {
    val s = st.settings
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.authType == AuthType.OAUTH) {
                Text(s.athleteName.ifBlank { "intervals.icu" }, style = MaterialTheme.typography.titleMedium)
                Text("Athlete ${s.athleteId} · intervals.icu login", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                Text("Signed in with API key", style = MaterialTheme.typography.titleMedium)
            }
            if (st.authExpired) {
                Text(
                    "intervals.icu no longer accepts this login. Uploads stay queued until you log in again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.authExpired) {
                    Button(onClick = onLogin, enabled = !st.busy) { Text("Log in again") }
                } else if (s.authType == AuthType.API_KEY) {
                    Button(onClick = onLogin, enabled = !st.busy) { Text("Log in with intervals.icu") }
                }
                OutlinedButton(onClick = onLogout, enabled = !st.busy) { Text("Log out") }
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
            RondeSwitch(checked = auto, onCheckedChange = { auto = it })
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
