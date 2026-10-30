package net.mvexel.intervalswatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.remove
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class Settings(
    /** "0" means the athlete that owns the API key. */
    val athleteId: String = "0",
    val apiKey: String = "",
    val daysAhead: Int = 7,
    val autoSync: Boolean = true,
) {
    val configured: Boolean get() = apiKey.isNotBlank()
}

/** Encrypts the API key at rest. Android impl is [KeystoreCipher]; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: String): String
    /** Returns null if the value cannot be decrypted (e.g. the Keystore key was lost). */
    fun decrypt(encoded: String): String?
}

private val PLAIN_KEY = stringPreferencesKey("api_key")
private val ENC_KEY = stringPreferencesKey("api_key_enc")

/** One-time migration: encrypt a legacy plain key and remove the plain copy. */
internal fun migratePlainApiKey(prefs: MutablePreferences, cipher: SecretCipher) {
    val plain = prefs[PLAIN_KEY] ?: return
    if (plain.isNotBlank() && prefs[ENC_KEY] == null) prefs[ENC_KEY] = cipher.encrypt(plain)
    prefs.remove(PLAIN_KEY)
}

internal fun readApiKey(prefs: Preferences, cipher: SecretCipher): String =
    prefs[ENC_KEY]?.let { cipher.decrypt(it) } ?: ""

class SettingsStore(
    private val context: Context,
    private val cipher: SecretCipher = KeystoreCipher(),
) {
    private val athleteId = stringPreferencesKey("athlete_id")
    private val daysAhead = intPreferencesKey("days_ahead")
    private val autoSync = booleanPreferencesKey("auto_sync")

    val flow: Flow<Settings> = context.dataStore.data.map {
        Settings(
            athleteId = it[athleteId]?.ifBlank { "0" } ?: "0",
            apiKey = readApiKey(it, cipher),
            daysAhead = it[daysAhead] ?: 7,
            autoSync = it[autoSync] ?: true,
        )
    }

    suspend fun current(): Settings {
        migrate()
        return flow.first()
    }

    private suspend fun migrate() {
        if (context.dataStore.data.first()[PLAIN_KEY] == null) return
        context.dataStore.edit { migratePlainApiKey(it, cipher) }
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit {
            it.remove(PLAIN_KEY)
            it[athleteId] = s.athleteId.trim()
            it[ENC_KEY] = cipher.encrypt(s.apiKey.trim())
            it[daysAhead] = s.daysAhead
            it[autoSync] = s.autoSync
        }
    }
}
