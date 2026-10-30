package net.mvexel.intervalswatch.wear

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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
import kotlinx.coroutines.sync.withLock

/**
 * Finished recordings live as FIT files until the phone confirms the upload, so a run recorded
 * out of Bluetooth range is sent when the phone reconnects or the app next starts.
 */
object RecordingStore {
    private const val TAG = "RecordingStore"
    private const val SEND_TIMEOUT_MS = 90_000L
    /** Shorter active time (pauses excluded) is not worth an activity or a "phone not reachable" banner. */
    internal const val MIN_SAVE_ACTIVE_SEC = 60.0

    private val _status = MutableStateFlow<SaveStatus?>(null)
    /** Outcome of the most recent recording, for the summary screen. */
    val status: StateFlow<SaveStatus?> = _status.asStateFlow()
    private var latest: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendLock = kotlinx.coroutines.sync.Mutex()
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

    /** An end at 0:00 or a short test is neither saved nor sent. */
    internal fun isWorthSaving(rec: Recording): Boolean =
        rec.samples.isNotEmpty() && rec.timerSec >= MIN_SAVE_ACTIVE_SEC

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".fit") }?.sortedBy { it.name }.orEmpty()

    /** A new workout starts: forget the previous outcome so the summary can't show it. */
    fun reset(ctx: Context) {
        restored = true
        latest = null
        _status.value = null
        prefs(ctx).edit().clear().apply()
    }

    /**
     * Writes the FIT file and sends it in the background, independent of the service that
     * recorded it, so a new workout can start while the transfer runs.
     */
    fun saveAsync(ctx: Context, rec: Recording) {
        val app = ctx.applicationContext
        restored = true
        if (!isWorthSaving(rec)) {
            set(app, SaveStatus.Failed("Nothing recorded"))
            return
        }
        val name = RecordingProtocol.fileName(rec.startEpochMs, rec.name)
        latest = name
        set(app, SaveStatus.Saving)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { File(dir(app), name).writeBytes(FitWriter.encode(rec)) }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not write recording", t)
                set(app, SaveStatus.Failed("Couldn't write file: ${t.message}"))
                return@launch
            }
            sendPending(app)
        }
    }

    fun sendPendingAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { sendPending(app) }
    }

    /** Sends every file the phone hasn't confirmed yet. Safe to call repeatedly. */
    suspend fun sendPending(ctx: Context) = sendLock.withLock {
        restore(ctx)
        val files = pending(ctx)
        if (files.isEmpty()) return@withLock
        try {
            // Only nodes that run the phone app; a node without it would never read the channel.
            val nodes = Wearable.getCapabilityClient(ctx)
                .getCapability(RecordingProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
            if (nodes.isEmpty()) {
                fail(ctx, files, "Phone not reachable. Will send when it is.")
                return@withLock
            }
            for (f in files) for (n in nodes) sendFile(ctx, n, f)
        } catch (t: Throwable) {
            Log.w(TAG, "Send failed", t)
            fail(ctx, files, "Couldn't reach the phone. Will retry.")
        }
    }

    /** Returns once the transfer has finished; closing earlier can cut the file off. */
    private suspend fun sendFile(ctx: Context, node: Node, f: File) {
        val channels = Wearable.getChannelClient(ctx)
        val ch = channels.openChannel(node.id, RecordingProtocol.CHANNEL_PREFIX + f.name).await()
        val done = CompletableDeferred<Boolean>()
        val cb = object : ChannelClient.ChannelCallback() {
            override fun onOutputClosed(c: ChannelClient.Channel, reason: Int, code: Int) {
                done.complete(reason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL)
            }
            override fun onChannelClosed(c: ChannelClient.Channel, reason: Int, code: Int) {
                done.complete(false)
            }
        }
        channels.registerChannelCallback(ch, cb).await()
        var ok = false
        try {
            channels.sendFile(ch, f.toUri()).await()
            ok = withTimeoutOrNull(SEND_TIMEOUT_MS) { done.await() } == true
            if (!ok) throw IOException("transfer did not complete")
        } finally {
            runCatching { channels.unregisterChannelCallback(cb).await() }
            // On success the phone closes the channel once it has the whole file.
            if (!ok) runCatching { channels.close(ch).await() }
        }
    }

    private fun fail(ctx: Context, files: List<File>, reason: String) {
        if (files.any { it.name == latest }) set(ctx, SaveStatus.Failed(reason))
    }

    internal fun onResult(ctx: Context, r: RecordingProtocol.Result) {
        restore(ctx)
        val file = File(dir(ctx), File(r.fileName).name)
        if (r.ok) file.delete()
        else if (!r.retry) {
            // Rejected for good: park it so it isn't resent on every start or reconnect.
            val rejected = File(dir(ctx), "rejected").apply { mkdirs() }
            file.renameTo(File(rejected, file.name))
        }
        if (r.fileName == latest || latest == null) {
            set(
                ctx,
                when {
                    r.ok -> SaveStatus.Saved
                    r.retry -> SaveStatus.Failed("Upload failed: ${r.reason}. Phone will retry.")
                    else -> SaveStatus.Failed("intervals.icu rejected the file: ${r.reason}")
                },
            )
        }
    }
}

/** Receives the phone's upload result. */
class RecordingResultService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RecordingProtocol.RESULT_PATH) return
        RecordingStore.onResult(this, RecordingProtocol.parseResult(event.data))
    }

    /** The phone app became reachable: send whatever it hasn't confirmed. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.isNotEmpty()) RecordingStore.sendPendingAsync(this)
    }
}
