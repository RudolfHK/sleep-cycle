package de.sleeptracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.sleeptracker.app.maintenance.MaintenanceWorker
import kotlinx.coroutines.flow.distinctUntilChanged
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
        // Improved analysis (stable phases, sound baseline): re-analyse stored nights once.
        container.appScope.launch {
            if (container.runtime.analysisVersion() < ANALYSIS_VERSION && container.runtime.activeSession() == null) {
                val previous = container.runtime.analysisVersion()
                runCatching { container.sleep.reanalyzeAll(consolidateSounds = previous < 6) }
                    .onSuccess { container.runtime.setAnalysisVersion(ANALYSIS_VERSION) }
            }
        }
        // Bedtime reminder follows wake-up time, sleep goal and reminder settings.
        container.appScope.launch {
            kotlinx.coroutines.flow.combine(container.settings.userSettings, container.settings.alarmSettings) { u, a ->
                listOf(u.bedtimeReminderEnabled, u.bedtimeReminderLeadMinutes, u.sleepGoalMinutes, a.targetWakeTime)
            }.distinctUntilChanged().collect { runCatching { de.sleeptracker.app.reminders.BedtimeReminder.reschedule(this@SleepTrackerApp) } }
        }
        container.appScope.launch { runCatching { de.sleeptracker.app.reminders.MonthlyReportNotifier.checkAndNotify(this@SleepTrackerApp) } }
        container.appScope.launch {
            if (!container.runtime.environmentBackfillDone()) {
                runCatching { container.sleep.backfillEnvironment() }.onSuccess { container.runtime.setEnvironmentBackfillDone() }
            }
        }
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
        /** Bump when the sleep analysis changes so stored nights are re-analysed once. */
        const val ANALYSIS_VERSION = 6
    }
}

val Context.container: AppContainer get() = (applicationContext as SleepTrackerApp).container
