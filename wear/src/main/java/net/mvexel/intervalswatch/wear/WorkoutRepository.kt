package net.mvexel.intervalswatch.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import net.mvexel.intervalswatch.shared.Sync
import net.mvexel.intervalswatch.shared.Workout
import net.mvexel.intervalswatch.shared.WorkoutBundle

/** Reads the workout bundle the phone put in the Data Layer (it persists on the watch). */
class WorkoutRepository(context: Context) : DataClient.OnDataChangedListener {

    private val client = Wearable.getDataClient(context.applicationContext)
    private val _bundle = MutableStateFlow<WorkoutBundle?>(null)
    val bundle: StateFlow<WorkoutBundle?> = _bundle.asStateFlow()
    private var injected: Workout? = null

    /** Debug builds only: shows an extra workout on top of the synced ones. */
    fun inject(w: Workout) {
        injected = w
        _bundle.value = withInjected(_bundle.value ?: WorkoutBundle(0, emptyList()))
    }

    private fun withInjected(b: WorkoutBundle) =
        injected?.let { i -> b.copy(workouts = b.workouts.filter { it.id != i.id } + i) } ?: b

    suspend fun load() {
        val items = client.getDataItems(Uri.parse("wear://*${Sync.DATA_PATH}")).await()
        try {
            items.mapNotNull { decode(DataMapItem.fromDataItem(it).dataMap.getString(Sync.KEY_JSON)) }
                .maxByOrNull { it.syncedAtMillis }
                ?.let { _bundle.value = withInjected(it) }
        } finally {
            items.release()
        }
    }

    fun startListening() = client.addListener(this)
    fun stopListening() = client.removeListener(this)

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == Sync.DATA_PATH) {
                decode(DataMapItem.fromDataItem(e.dataItem).dataMap.getString(Sync.KEY_JSON))
                    ?.let { _bundle.value = withInjected(it) }
            }
        }
        events.release()
    }

    private fun decode(json: String?): WorkoutBundle? =
        json?.let { runCatching { Sync.decode(it) }.getOrNull() }
}
