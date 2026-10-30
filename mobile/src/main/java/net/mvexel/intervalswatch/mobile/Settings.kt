package net.mvexel.intervalswatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Credentials

private val Context.dataStore by preferencesDataStore("settings")

enum class AuthType(val stored: String) {
    API_KEY("api_key"), OAUTH("oauth");

    companion object {
        fun parse(s: String?): AuthType = entries.firstOrNull { it.stored == s } ?: API_KEY
    }
}

data class Settings(
    /** "0" means the athlete that owns the credential. */
    val athleteId: String = "0",
    /** The pasted API key, or the OAuth access token, depending on [authType]. */
    val token: String = "",
    val daysAhead: Int = 7,
    val autoSync: Boolean = true,
    val authType: AuthType = AuthType.API_KEY,
    val athleteName: String = "",
) {
    val configured: Boolean get() = token.isNotBlank()

    /** Value of the Authorization header: Bearer for an OAuth token, Basic API_KEY:key for a pasted key. */
    val authHeader: String
        get() = when (authType) {
            AuthType.OAUTH -> "Bearer $token"
            AuthType.API_KEY -> Credentials.basic("API_KEY", token)
        }
}

/** Encrypts the API key at rest. Android impl is [KeystoreCipher]; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: String): String
    /** Returns null if the value cannot be decrypted (e.g. the Keystore key was lost). */
    fun decrypt(encoded: String): String?
}

private val PLAIN_KEY = stringPreferencesKey("api_key")
private val ENC_KEY = stringPreferencesKey("api_key_enc")
private val TOKEN_ENC_KEY = stringPreferencesKey("access_token_enc")
private val AUTH_TYPE = stringPreferencesKey("auth_type")
private val ATHLETE_ID = stringPreferencesKey("athlete_id")
private val ATHLETE_NAME = stringPreferencesKey("athlete_name")
private val DAYS_AHEAD = intPreferencesKey("days_ahead")
private val AUTO_SYNC = booleanPreferencesKey("auto_sync")

/** One-time migration: encrypt a legacy plain key and remove the plain copy. */
internal fun migratePlainApiKey(prefs: MutablePreferences, cipher: SecretCipher) {
    val plain = prefs[PLAIN_KEY] ?: return
    if (plain.isNotBlank() && prefs[ENC_KEY] == null) prefs[ENC_KEY] = cipher.encrypt(plain)
    prefs.remove(PLAIN_KEY)
}

internal fun readApiKey(prefs: Preferences, cipher: SecretCipher): String =
    prefs[ENC_KEY]?.let { cipher.decrypt(it) } ?: ""

/** Reads the credential for the stored auth type. A store with only a pasted key (pre-OAuth) reads as [AuthType.API_KEY]. */
internal fun readSettings(prefs: Preferences, cipher: SecretCipher): Settings {
    val type = AuthType.parse(prefs[AUTH_TYPE])
    val slot = if (type == AuthType.OAUTH) TOKEN_ENC_KEY else ENC_KEY
    return Settings(
        athleteId = prefs[ATHLETE_ID]?.ifBlank { "0" } ?: "0",
        token = prefs[slot]?.let { cipher.decrypt(it) } ?: "",
        daysAhead = prefs[DAYS_AHEAD] ?: 7,
        autoSync = prefs[AUTO_SYNC] ?: true,
        authType = type,
        athleteName = prefs[ATHLETE_NAME] ?: "",
    )
}

/** Stores [s]. Saving an OAuth login deletes any stored API key (and the other way round for the token). */
internal fun writeSettings(prefs: MutablePreferences, cipher: SecretCipher, s: Settings) {
    prefs.remove(PLAIN_KEY)
    prefs[ATHLETE_ID] = s.athleteId.trim()
    prefs[AUTH_TYPE] = s.authType.stored
    prefs[ATHLETE_NAME] = s.athleteName
    prefs[DAYS_AHEAD] = s.daysAhead
    prefs[AUTO_SYNC] = s.autoSync
    when (s.authType) {
        AuthType.OAUTH -> {
            prefs[TOKEN_ENC_KEY] = cipher.encrypt(s.token.trim())
            prefs.remove(ENC_KEY)
        }
        AuthType.API_KEY -> {
            prefs[ENC_KEY] = cipher.encrypt(s.token.trim())
            prefs.remove(TOKEN_ENC_KEY)
        }
    }
}

/** Removes every credential and the identity; keeps daysAhead and autoSync. */
internal fun clearCredentials(prefs: MutablePreferences) {
    prefs.remove(PLAIN_KEY)
    prefs.remove(ENC_KEY)
    prefs.remove(TOKEN_ENC_KEY)
    prefs.remove(AUTH_TYPE)
    prefs.remove(ATHLETE_ID)
    prefs.remove(ATHLETE_NAME)
}

/**
 * Logs out: tells intervals.icu to revoke an OAuth token, then clears local credentials.
 * The clear happens even if the revoke call fails (offline, already revoked).
 */
suspend fun logout(settings: Settings, api: IntervalsApi, clear: suspend () -> Unit) {
    try {
        if (settings.authType == AuthType.OAUTH && settings.configured) api.disconnect(settings)
    } catch (_: Exception) {
        // best effort
    } finally {
        clear()
    }
}

class SettingsStore(
    private val context: Context,
    private val cipher: SecretCipher = KeystoreCipher(),
) {
    val flow: Flow<Settings> = context.dataStore.data.map { readSettings(it, cipher) }

    suspend fun current(): Settings {
        migrate()
        return flow.first()
    }

    private suspend fun migrate() {
        if (context.dataStore.data.first()[PLAIN_KEY] == null) return
        context.dataStore.edit { migratePlainApiKey(it, cipher) }
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { writeSettings(it, cipher, s) }
    }

    suspend fun logout(api: IntervalsApi = IntervalsApi()) {
        logout(current(), api) { context.dataStore.edit { clearCredentials(it) } }
    }
}
