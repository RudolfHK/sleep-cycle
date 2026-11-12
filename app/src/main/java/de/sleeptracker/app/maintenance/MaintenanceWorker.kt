package de.sleeptracker.app.maintenance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.sleeptracker.app.container

/** Daily, non-time-critical housekeeping: deletes expired recordings. Never used for alarms. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.container.recordings.deleteExpired()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
