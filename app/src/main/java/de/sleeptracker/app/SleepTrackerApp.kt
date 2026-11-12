package de.sleeptracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.sleeptracker.app.maintenance.MaintenanceWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SleepTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "maintenance",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
        // Detect sessions interrupted by a crash or process kill.
        container.appScope.launch { container.tracking.recoverIfNeeded() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // sound is played by the ring service with USAGE_ALARM
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, getString(R.string.channel_info), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_INFO = "info"
    }
}

val Context.container: AppContainer get() = (applicationContext as SleepTrackerApp).container
