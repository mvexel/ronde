package net.mvexel.intervalswatch.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.session.Session

data class SettingsUiState(
    val settings: Settings = Settings(),
    val busy: Boolean = false,
    val authExpired: Boolean = false,
    /** The sync options form is open. */
    val editingSync: Boolean = false,
) {
    override fun toString() = "SettingsUiState(settings=$settings, busy=$busy, authExpired=$authExpired, editingSync=$editingSync)"
}

/** The Settings tab: account (OAuth or API key), sync options, and a way into watch behaviour. */
class SettingsViewModel(private val session: Session) : ViewModel() {
    private val editingSync = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(session.state, editingSync) { st, e -> SettingsUiState(st.settings, st.busy, st.authExpired, e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun editSync(open: Boolean) {
        editingSync.value = open
    }

    /** Saves sync options and closes the form. */
    fun save(s: Settings) {
        editingSync.value = false
        session.save(s)
    }

    fun logout() {
        editingSync.value = false
        session.logout()
    }
}
