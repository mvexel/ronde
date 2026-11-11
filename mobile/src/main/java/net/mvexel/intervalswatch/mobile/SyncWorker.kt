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
import kotlinx.coroutines.CancellationException
import net.mvexel.intervalswatch.mobile.app.AppContainer
import java.util.concurrent.TimeUnit

/** Background refresh every few hours so the watch has today's workout without opening the phone app. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).current()
        if (!settings.configured || !settings.autoSync) return Result.success()
        val container = AppContainer.from(applicationContext)
        return run(
            plan = { container.planSync.sync(settings) },
            history = { container.activityRepository.backgroundSync() },
        )
    }

    companion object {
        private const val NAME = "intervals-sync"

        /**
         * One worker run. Any failure of the plan sync (the fetch, Room, the hidden set, the push) is a retry,
         * except an expired login, which no retry fixes. History: a refresh when the last one is old, plus one page
         * of older-history backfill (at most 2 calls); best effort, a failure there waits for the next run.
         */
        internal suspend fun run(plan: suspend () -> Unit, history: suspend () -> Unit): Result {
            val error = try {
                plan()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (error is AuthExpiredException) return Result.failure()
            try {
                history()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SyncWorker", "history sync: ${e.javaClass.simpleName}")
            }
            return if (error == null) Result.success() else Result.retry()
        }

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
