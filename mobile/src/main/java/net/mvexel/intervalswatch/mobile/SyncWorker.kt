package net.mvexel.intervalswatch.mobile

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.mvexel.intervalswatch.mobile.app.AppContainer
import java.util.concurrent.TimeUnit

/** Background refresh every few hours so the watch has today's workout without opening the phone app. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).current()
        if (!settings.configured || !settings.autoSync) return Result.success()
        val container = AppContainer.from(applicationContext)
        val hidden = container.prefsDao.hiddenIds()
        val error = runCatching { WatchSync.syncNow(applicationContext, settings, hidden) }.exceptionOrNull()
        if (error is AuthExpiredException) return Result.failure()
        // History: a refresh when the last one is old, plus one page of older-history backfill (at most 2 calls).
        // Best effort: a failure here waits for the next run instead of retrying the watch sync.
        runCatching { container.activityRepository.backgroundSync() }
            .onFailure { Log.w("SyncWorker", "history sync: ${it.javaClass.simpleName}") }
        return if (error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "intervals-sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
