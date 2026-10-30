package net.mvexel.intervalswatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

class SettingsStore(private val context: Context) {
    private val athleteId = stringPreferencesKey("athlete_id")
    private val apiKey = stringPreferencesKey("api_key")
    private val daysAhead = intPreferencesKey("days_ahead")
    private val autoSync = booleanPreferencesKey("auto_sync")

    val flow: Flow<Settings> = context.dataStore.data.map {
        Settings(
            athleteId = it[athleteId]?.ifBlank { "0" } ?: "0",
            apiKey = it[apiKey] ?: "",
            daysAhead = it[daysAhead] ?: 7,
            autoSync = it[autoSync] ?: true,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit {
            it[athleteId] = s.athleteId.trim()
            it[apiKey] = s.apiKey.trim()
            it[daysAhead] = s.daysAhead
            it[autoSync] = s.autoSync
        }
    }
}
