package net.mvexel.intervalswatch.mobile.fakes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.mvexel.intervalswatch.mobile.PendingLogin
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.data.RecordingsSource
import net.mvexel.intervalswatch.mobile.data.ScreenSettingsSource
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsDao
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.mobile.data.session.Session
import net.mvexel.intervalswatch.mobile.data.session.SessionState
import net.mvexel.intervalswatch.shared.ScreenSettings
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Sets Dispatchers.Main to an unconfined test dispatcher so viewModelScope runs eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FakeSession(initial: SessionState = SessionState(loaded = true)) : Session {
    val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SessionState> = mutable
    val calls = mutableListOf<String>()
    var saved: Settings? = null

    override fun start() { calls += "start" }
    override fun sync() { calls += "sync" }
    override fun pushToWatch() { calls += "pushToWatch" }
    override fun save(s: Settings) {
        calls += "save"
        saved = s
        mutable.update { it.copy(settings = s) }
    }
    override fun logout() {
        calls += "logout"
        mutable.update { it.copy(settings = Settings()) }
    }
    override suspend fun beginLogin() = PendingLogin("v", "s", 0)
    override fun handleRedirect(uri: String?) { calls += "redirect:$uri" }
    override fun showStatus(msg: String) = mutable.update { it.copy(status = msg) }
    override fun refreshWatches() { calls += "refreshWatches" }
}

/** In-memory [PlannedWorkoutPrefsDao] with the same semantics as the Room queries. */
class FakePrefsDao : PlannedWorkoutPrefsDao {
    val rows = MutableStateFlow<Map<Long, PlannedWorkoutPrefsEntity>>(emptyMap())
    override fun observeAll(): Flow<List<PlannedWorkoutPrefsEntity>> = rows.map { it.values.toList() }
    override suspend fun get(eventId: Long) = rows.value[eventId]
    override suspend fun hiddenIds() = rows.value.values.filter { it.hidden }.map { it.eventId }
    override suspend fun maxPinnedOrder() = rows.value.values.filter { it.pinned }.maxOfOrNull { it.sortOrder } ?: 0
    override suspend fun upsert(prefs: PlannedWorkoutPrefsEntity) = rows.update { it + (prefs.eventId to prefs) }
    override suspend fun insertDefault(eventId: Long) = rows.update { if (eventId in it) it else it + (eventId to PlannedWorkoutPrefsEntity(eventId)) }
    override suspend fun updateHidden(eventId: Long, hidden: Boolean) = change(eventId) { it.copy(hidden = hidden) }
    override suspend fun updatePinnedLast(eventId: Long) = rows.update { m ->
        val order = (m.values.filter { it.pinned }.maxOfOrNull { it.sortOrder } ?: 0) + 1
        m[eventId]?.let { m + (eventId to it.copy(pinned = true, sortOrder = order)) } ?: m
    }
    override suspend fun updateUnpinned(eventId: Long) = change(eventId) { it.copy(pinned = false, sortOrder = 0) }
    private fun change(id: Long, f: (PlannedWorkoutPrefsEntity) -> PlannedWorkoutPrefsEntity) =
        rows.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
}

class FakeRecordings(var items: List<RecordingItem> = emptyList()) : RecordingsSource {
    override val changes = MutableStateFlow(0)
    val retried = mutableListOf<String>()
    override suspend fun recent() = items
    override suspend fun retry(fileName: String) { retried += fileName }
}

class FakeScreenSettings : ScreenSettingsSource {
    override val settings = MutableStateFlow(ScreenSettings())
    override suspend fun update(edit: (ScreenSettings) -> ScreenSettings) = settings.update(edit)
}
