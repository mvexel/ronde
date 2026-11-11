package net.mvexel.intervalswatch.mobile.data.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.mobile.AuthExpiredException
import net.mvexel.intervalswatch.mobile.AuthType
import net.mvexel.intervalswatch.mobile.OAUTH_CLIENT_ID
import net.mvexel.intervalswatch.mobile.OAuth
import net.mvexel.intervalswatch.mobile.OAuthError
import net.mvexel.intervalswatch.mobile.PendingLogin
import net.mvexel.intervalswatch.mobile.PendingLoginStore
import net.mvexel.intervalswatch.mobile.RedirectResult
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.SettingsStore
import net.mvexel.intervalswatch.mobile.SyncWorker
import net.mvexel.intervalswatch.mobile.UploadWorker
import net.mvexel.intervalswatch.mobile.WatchSync
import net.mvexel.intervalswatch.mobile.data.plan.PlanSync
import net.mvexel.intervalswatch.mobile.isLoginRedirect
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The real [Session]: DataStore settings, OAuth, intervals.icu and the Data Layer. Lives for the process. */
class AppSession(
    context: Context,
    private val scope: CoroutineScope,
    private val planSync: PlanSync,
    /**
     * Runs after the credential is cleared, e.g. to wipe cached History and the stored plan (another account may
     * log in next).
     */
    private val onLogout: suspend () -> Unit = {},
) : Session {
    private val app = context.applicationContext
    private val store = SettingsStore(app)
    private val pending = PendingLoginStore(app)
    private val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        // The Plan tab follows the stored list, so a SyncWorker fetch (or the list from before a restart) shows up.
        scope.launch {
            planSync.observe().collect { list ->
                _state.update {
                    it.copy(
                        workouts = list?.let { l -> planSync.upcoming(l.workouts) }.orEmpty(),
                        workoutsLoaded = true,
                        workoutsFetchedAt = list?.fetchedAtMillis,
                    )
                }
            }
        }
    }

    override fun start() {
        scope.launch {
            val s = store.current()
            _state.update { it.copy(settings = s, loaded = true) }
            if (!s.configured) WatchSync.pushLoggedIn(app, false)
            updateWatches()
            if (s.configured) sync()
        }
    }

    override suspend fun beginLogin(): PendingLogin = pending.start()

    override fun handleRedirect(uri: String?) {
        if (!isLoginRedirect(uri)) return
        scope.launch {
            val p = pending.takeMatching(uri!!) ?: return@launch
            when (val r = OAuth.parseRedirect(uri, p.state)) {
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

    override fun showStatus(msg: String) = _state.update { it.copy(status = msg) }

    override fun logout() {
        scope.launch {
            _state.update { it.copy(busy = true) }
            store.logout()
            runCatching { onLogout() }
            SyncWorker.schedule(app, false)
            WatchSync.pushLoggedIn(app, false)
            _state.update { it.copy(settings = Settings(), busy = false, authExpired = false, status = null) }
        }
    }

    override fun save(s: Settings) {
        scope.launch {
            store.save(s)
            SyncWorker.schedule(app, s.autoSync)
            if (store.current().configured) withContext(Dispatchers.IO) { UploadWorker.retryPending(app) }
            _state.update { it.copy(settings = store.current(), authExpired = false) }
            sync()
        }
    }

    override fun sync() {
        val s = _state.value.settings
        if (!s.configured) return
        scope.launch {
            _state.update { it.copy(busy = true, status = "Syncing…") }
            // The list itself reaches [state] through the store (see init), like a background sync's does.
            runCatching { planSync.sync(s) }
                .onSuccess { r ->
                    _state.update {
                        it.copy(busy = false, status = "Sent ${r.sent} workout(s) to the watch", lastSynced = LocalTime.now().format(HH_MM))
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(busy = false, status = "Failed: ${e.message}", authExpired = e is AuthExpiredException || it.authExpired)
                    }
                }
            updateWatches()
        }
    }

    override fun pushToWatch() {
        if (!_state.value.settings.configured) return
        scope.launch {
            // From the stored list (the newest fetch, whoever made it); null = nothing fetched yet, nothing sent.
            runCatching { planSync.pushStored() }
                .onSuccess { n -> if (n != null) _state.update { it.copy(status = "Sent $n workout(s) to the watch") } }
                .onFailure { e -> _state.update { it.copy(status = "Failed: ${e.message}") } }
        }
    }

    override fun refreshWatches() {
        scope.launch { updateWatches() }
    }

    private suspend fun updateWatches() {
        val w = WatchSync.connectedWatches(app)
        _state.update { it.copy(watches = w) }
    }

    private companion object {
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
