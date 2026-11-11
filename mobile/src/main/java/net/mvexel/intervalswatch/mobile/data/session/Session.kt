package net.mvexel.intervalswatch.mobile.data.session

import kotlinx.coroutines.flow.StateFlow
import net.mvexel.intervalswatch.mobile.PendingLogin
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.shared.Workout

/** App-wide login and sync state, shared by every tab. */
data class SessionState(
    /** Settings have been read from storage; until then neither login nor tabs are shown. */
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    /** Everything the last sync fetched, hidden ones included. */
    val workouts: List<Workout> = emptyList(),
    val watches: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    /** intervals.icu answered 401: the user has to log in again. */
    val authExpired: Boolean = false,
    /** Local time of the last successful sync, e.g. "07:12". */
    val lastSynced: String? = null,
) {
    // Settings.toString already hides the token; keep the whole state out of logs anyway.
    override fun toString() = "SessionState(loaded=$loaded, settings=$settings, workouts=${workouts.size}, busy=$busy, authExpired=$authExpired)"
}

interface Session {
    val state: StateFlow<SessionState>

    /** Reads settings, tells the watch whether we are logged in, and syncs if we are. Call when the app opens. */
    fun start()

    /** Fetches planned workouts and sends the non-hidden ones to the watch. */
    fun sync()

    /** Re-sends the last fetched workouts with the current hidden set (after a hide/unhide), without fetching. */
    fun pushToWatch()

    /** Stores [s] (login, API key, sync options), reschedules background sync, and syncs. */
    fun save(s: Settings)

    /** Revokes the token, clears credentials, and tells the watch. Queued uploads stay on disk. */
    fun logout()

    /** Starts a login: persists verifier and state, then returns what the Custom Tab needs to open. */
    suspend fun beginLogin(): PendingLogin

    /** Handles an OAuth redirect. Without a pending login (stale, replayed or foreign) it is ignored. */
    fun handleRedirect(uri: String?)

    fun showStatus(msg: String)

    fun refreshWatches()
}
