package net.mvexel.intervalswatch.wear

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.mvexel.intervalswatch.shared.FitWriter
import net.mvexel.intervalswatch.shared.Recording
import net.mvexel.intervalswatch.shared.RecordingProtocol
import net.mvexel.intervalswatch.shared.SaveStatus
import java.io.File

/**
 * Finished recordings live as FIT files until the phone confirms the upload, so a run recorded
 * out of Bluetooth range is sent the next time the app starts.
 */
object RecordingStore {
    private const val TAG = "RecordingStore"

    private val _status = MutableStateFlow<SaveStatus?>(null)
    /** Outcome of the most recent recording, for the summary screen. */
    val status: StateFlow<SaveStatus?> = _status.asStateFlow()
    private var latest: String? = null
    private var restored = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("recording", Context.MODE_PRIVATE)

    /** Loads the last status after a restart. */
    fun restore(ctx: Context) {
        if (restored) return
        restored = true
        val p = prefs(ctx)
        latest = p.getString("latest", null)
        if (_status.value == null) _status.value = when (p.getString("state", null)) {
            "saving" -> SaveStatus.Saving
            "saved" -> SaveStatus.Saved
            "failed" -> SaveStatus.Failed(p.getString("reason", "") ?: "")
            else -> null
        }
    }

    private fun set(ctx: Context, s: SaveStatus) {
        _status.value = s
        prefs(ctx).edit().putString("latest", latest).apply {
            when (s) {
                SaveStatus.Saving -> putString("state", "saving")
                SaveStatus.Saved -> putString("state", "saved")
                is SaveStatus.Failed -> putString("state", "failed").putString("reason", s.reason)
            }
        }.apply()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".fit") }?.sortedBy { it.name }.orEmpty()

    fun fileName(rec: Recording): String =
        "${rec.startEpochMs / 1000}_${rec.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "workout" }}.fit"

    /** Writes the FIT file and starts sending it. */
    suspend fun save(ctx: Context, rec: Recording) {
        restored = true
        if (rec.samples.isEmpty()) {
            set(ctx, SaveStatus.Failed("Nothing was recorded"))
            return
        }
        val name = fileName(rec)
        latest = name
        set(ctx, SaveStatus.Saving)
        try {
            withContext(Dispatchers.IO) { File(dir(ctx), name).writeBytes(FitWriter.encode(rec)) }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not write recording", t)
            set(ctx, SaveStatus.Failed("Couldn't write file: ${t.message}"))
            return
        }
        sendPending(ctx)
    }

    /** Sends every file the phone hasn't confirmed yet. Safe to call repeatedly. */
    suspend fun sendPending(ctx: Context) {
        restore(ctx)
        val files = pending(ctx)
        if (files.isEmpty()) return
        try {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            if (nodes.isEmpty()) {
                fail(ctx, files, "Phone not connected. Will send when it is.")
                return
            }
            val channels = Wearable.getChannelClient(ctx)
            for (f in files) for (n in nodes) {
                val ch = channels.openChannel(n.id, RecordingProtocol.CHANNEL_PREFIX + f.name).await()
                channels.sendFile(ch, f.toUri()).await()
                channels.close(ch).await()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Send failed", t)
            fail(ctx, files, "Couldn't reach the phone. Will retry.")
        }
    }

    private fun fail(ctx: Context, files: List<File>, reason: String) {
        if (files.any { it.name == latest }) set(ctx, SaveStatus.Failed(reason))
    }

    internal fun onResult(ctx: Context, ok: Boolean, name: String, reason: String) {
        restore(ctx)
        if (ok) File(dir(ctx), File(name).name).delete()
        if (name == latest || latest == null) {
            set(ctx, if (ok) SaveStatus.Saved else SaveStatus.Failed("Upload failed: $reason. Phone will retry."))
        }
    }
}

/** Receives the phone's upload result. */
class RecordingResultService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RecordingProtocol.RESULT_PATH) return
        val (ok, name, reason) = RecordingProtocol.parseResult(event.data)
        RecordingStore.onResult(this, ok, name, reason)
    }
}
