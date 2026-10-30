package net.mvexel.intervalswatch.mobile

import android.content.Context
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import net.mvexel.intervalswatch.shared.RecordingProtocol
import java.io.File
import java.util.concurrent.TimeUnit

private fun recordingsDir(ctx: Context) = File(ctx.filesDir, "recordings").apply { mkdirs() }

/** Receives finished-workout FIT files from the watch and queues their upload. */
class RecordingReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(RecordingProtocol.CHANNEL_PREFIX)) return
        val name = File(channel.path.removePrefix(RecordingProtocol.CHANNEL_PREFIX)).name
        if (!name.endsWith(".fit")) return
        val target = File(recordingsDir(this), "$name.part")
        Wearable.getChannelClient(this).receiveFile(channel, target.toUri(), false)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (!channel.path.startsWith(RecordingProtocol.CHANNEL_PREFIX)) return
        val name = File(channel.path.removePrefix(RecordingProtocol.CHANNEL_PREFIX)).name
        val part = File(recordingsDir(this), "$name.part")
        if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) {
            part.delete() // the watch resends pending files
            Wearable.getChannelClient(this).close(channel)
            return
        }
        val done = File(recordingsDir(this), name)
        if (part.renameTo(done)) UploadWorker.enqueue(this, name)
        Wearable.getChannelClient(this).close(channel)
    }
}

/** Uploads one FIT file to intervals.icu; retries with backoff until it works. Tells the watch the outcome. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val file = File(recordingsDir(applicationContext), name)
        if (!file.exists()) return Result.success()
        val settings = SettingsStore(applicationContext).current()
        val failure = uploadRecordingFile(IntervalsApi(), settings, file)

        if (failure?.authRejected == true) WatchSync.pushLoggedIn(applicationContext, false)
        notifyWatch(name, failure)
        return when {
            failure == null -> { file.delete(); Result.success() }
            failure.retryable -> Result.retry()
            else -> { file.delete(); Result.failure() } // the watch keeps its own copy
        }
    }

    private suspend fun notifyWatch(name: String, failure: UploadFailure?) {
        val msg = RecordingProtocol.result(failure == null, name, failure?.reason.orEmpty(), failure?.retryable ?: true)
        runCatching {
            val client = Wearable.getMessageClient(applicationContext)
            Wearable.getNodeClient(applicationContext).connectedNodes.await().forEach {
                client.sendMessage(it.id, RecordingProtocol.RESULT_PATH, msg).await()
            }
        }
    }

    companion object {
        const val KEY_NAME = "name"

        fun enqueue(ctx: Context, name: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(KEY_NAME, name).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-$name", ExistingWorkPolicy.KEEP, req)
        }
    }
}

class UploadFailure(val reason: String, val retryable: Boolean, val authRejected: Boolean = false)

/** @return null on success, else why not. The reason never contains the credential. */
suspend fun uploadRecordingFile(api: IntervalsApi, settings: Settings, file: File): UploadFailure? {
    if (!settings.configured) return UploadFailure("Not logged in to intervals.icu on the phone", retryable = true)
    return runCatching {
        api.uploadActivity(
            settings, file.readBytes(), file.name,
            RecordingProtocol.displayName(file.name), RecordingProtocol.externalId(file.name),
            RecordingProtocol.eventId(file.name),
        )
    }.exceptionOrNull()?.let {
        UploadFailure(it.message ?: "upload failed", (it as? UploadHttpException)?.retryable ?: true, (it as? UploadHttpException)?.let { e -> isAuthRejection(e.code, settings.authType) } == true)
    }
}
