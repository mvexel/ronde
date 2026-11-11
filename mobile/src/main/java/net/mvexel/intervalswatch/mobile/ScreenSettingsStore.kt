package net.mvexel.intervalswatch.mobile

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import net.mvexel.intervalswatch.shared.ScreenSettings
import net.mvexel.intervalswatch.shared.SettingsSync
import net.mvexel.intervalswatch.mobile.data.ScreenSettingsSource

/** Phone side of `/settings`: a local copy, written to the Data Layer on every change, merged on arrival. */
class ScreenSettingsStore(context: Context) : com.google.android.gms.wearable.DataClient.OnDataChangedListener, ScreenSettingsSource {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("screen_settings", Context.MODE_PRIVATE)
    private val client = Wearable.getDataClient(app)
    private val _settings = MutableStateFlow(SettingsSync.decodeOrNull(prefs.getString(SettingsSync.KEY_JSON, null)) ?: ScreenSettings())
    override val settings: StateFlow<ScreenSettings> = _settings.asStateFlow()

    /** A local edit: stamps it, caches it, sends it. */
    override suspend fun update(edit: (ScreenSettings) -> ScreenSettings) {
        val next = edit(_settings.value).copy(updatedAt = System.currentTimeMillis())
        adopt(next)
        val request = PutDataMapRequest.create(SettingsSync.PATH).apply {
            dataMap.putString(SettingsSync.KEY_JSON, next.toJson())
        }.asPutDataRequest().setUrgent()
        runCatching { client.putDataItem(request).await() }
    }

    /** Reads the item the watch may have written while the app was not running. */
    suspend fun load() {
        val items = runCatching { client.getDataItems(android.net.Uri.parse("wear://*${SettingsSync.PATH}")).await() }.getOrNull() ?: return
        try {
            items.forEach { merge(DataMapItem.fromDataItem(it).dataMap.getString(SettingsSync.KEY_JSON)) }
        } finally {
            items.release()
        }
    }

    fun startListening() = client.addListener(this)
    fun stopListening() = client.removeListener(this)

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == SettingsSync.PATH) {
                merge(DataMapItem.fromDataItem(e.dataItem).dataMap.getString(SettingsSync.KEY_JSON))
            }
        }
        events.release()
    }

    private fun merge(json: String?) {
        val remote = SettingsSync.decodeOrNull(json) ?: return
        val merged = SettingsSync.merge(_settings.value, remote)
        if (merged !== _settings.value) adopt(merged)
    }

    private fun adopt(s: ScreenSettings) {
        prefs.edit().putString(SettingsSync.KEY_JSON, s.toJson()).apply()
        _settings.value = s
    }
}
