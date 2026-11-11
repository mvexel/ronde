package net.mvexel.intervalswatch.mobile.feature.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mvexel.intervalswatch.mobile.data.ScreenSettingsSource
import net.mvexel.intervalswatch.mobile.data.WatchBuzzer
import net.mvexel.intervalswatch.mobile.data.session.Session
import net.mvexel.intervalswatch.mobile.data.session.SessionState
import net.mvexel.intervalswatch.shared.ScreenSettings

data class WatchUiState(
    val watches: List<String> = emptyList(),
    val lastSynced: String? = null,
    val status: String? = null,
    val busy: Boolean = false,
)

internal fun SessionState.toWatchUi() = WatchUiState(watches, lastSynced, status, busy)

/** The Watch tab: connection, sync status and the Sync button. Recordings come from [RecordingsViewModel]. */
class WatchViewModel(private val session: Session) : ViewModel() {
    val state: StateFlow<WatchUiState> = session.state.map { it.toWatchUi() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.toWatchUi())

    fun sync() = session.sync()
    fun refreshWatches() = session.refreshWatches()
}

data class WatchScreensUiState(val settings: ScreenSettings = ScreenSettings(), val watchConnected: Boolean = false)

/** "Watch screens & alerts": every edit is stored locally and sent to the watch. */
class WatchScreensViewModel(
    private val screens: ScreenSettingsSource,
    private val buzzer: WatchBuzzer,
    session: Session,
) : ViewModel() {
    val state: StateFlow<WatchScreensUiState> = combine(screens.settings, session.state) { s, st -> WatchScreensUiState(s, st.watches.isNotEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WatchScreensUiState(screens.settings.value, session.state.value.watches.isNotEmpty()))

    fun edit(edit: (ScreenSettings) -> ScreenSettings) {
        viewModelScope.launch { screens.update(edit) }
    }

    fun testBuzz() {
        viewModelScope.launch { buzzer.testBuzz(state.value.settings.buzzStrength) }
    }
}
