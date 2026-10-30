package net.mvexel.intervalswatch.mobile

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.loginStore by preferencesDataStore("pending_login")

/** Registered intervals.icu OAuth client id (A01). Placeholder until the registration is approved. */
const val OAUTH_CLIENT_ID = "TODO_CLIENT_ID"

/** How long a started login stays valid; the user may be in the browser a while. */
const val PENDING_LOGIN_TTL_MS = 15 * 60 * 1000L

/** The verifier and state of a login in progress. The verifier is a secret: never log it. */
data class PendingLogin(val verifier: String, val state: String, val startedAt: Long) {
    override fun toString() = "PendingLogin(startedAt=$startedAt)"
}

private val P_VERIFIER = stringPreferencesKey("verifier")
private val P_STATE = stringPreferencesKey("state")
private val P_STARTED = longPreferencesKey("started_at")

internal fun writePending(prefs: MutablePreferences, p: PendingLogin) {
    prefs[P_VERIFIER] = p.verifier
    prefs[P_STATE] = p.state
    prefs[P_STARTED] = p.startedAt
}

/** The pending login, or null if there is none or it is older than [PENDING_LOGIN_TTL_MS]. */
internal fun readPending(prefs: Preferences, now: Long): PendingLogin? {
    val v = prefs[P_VERIFIER] ?: return null
    val s = prefs[P_STATE] ?: return null
    val t = prefs[P_STARTED] ?: return null
    return if (now - t in 0..PENDING_LOGIN_TTL_MS) PendingLogin(v, s, t) else null
}

internal fun clearPending(prefs: MutablePreferences) {
    prefs.remove(P_VERIFIER)
    prefs.remove(P_STATE)
    prefs.remove(P_STARTED)
}

/** True for the redirect URI the login flow expects ([OAuth.REDIRECT_URI]), ignoring query and fragment. */
internal fun isLoginRedirect(uri: String?): Boolean =
    uri != null && uri.substringBefore('?').substringBefore('#') == OAuth.REDIRECT_URI

/** Persists the login in progress in DataStore so it survives the app process dying while the user is in the browser. */
class PendingLoginStore(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun start(): PendingLogin {
        val p = PendingLogin(OAuth.newVerifier(), OAuth.newState(), clock())
        context.loginStore.edit { writePending(it, p) }
        return p
    }

    /** Returns the pending login and clears it, so a redirect can be used only once. */
    suspend fun take(): PendingLogin? {
        var found: PendingLogin? = null
        context.loginStore.edit {
            found = readPending(it, clock())
            clearPending(it)
        }
        return found
    }

    suspend fun peek(): PendingLogin? = readPending(context.loginStore.data.first(), clock())

    suspend fun cancel() {
        context.loginStore.edit { clearPending(it) }
    }
}

/** Opens the authorize URL in a Custom Tab. */
fun launchLoginTab(context: Context, p: PendingLogin) {
    val url = OAuth.authorizeUrl(OAUTH_CLIENT_ID, p.state, OAuth.challenge(p.verifier))
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url.toString()))
}
