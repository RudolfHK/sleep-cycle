package de.sleeptracker.app.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.sleeptracker.app.R
import de.sleeptracker.app.SleepTrackerApp
import de.sleeptracker.app.container
import de.sleeptracker.app.tracking.SleepTrackingService
import de.sleeptracker.app.ui.Fmt
import de.sleeptracker.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Daily bedtime reminder: bedtime = wake-up time − sleep goal − usual time to fall asleep; the
 * notification comes [lead] minutes earlier. Inexact alarm (no special permission needed).
 */
object BedtimeReminder {
    private const val REQUEST = 4001
    const val NOTIFICATION_ID = 4002

    /** Bedtime as minute of day. */
    fun bedtimeMinute(wakeMinute: Int, goalMinutes: Int, latencyMinutes: Int): Int =
        Math.floorMod(wakeMinute - goalMinutes - latencyMinutes, 1440)

    private suspend fun usualLatency(context: Context): Int {
        val recent = context.container.sleep.completedBefore(System.currentTimeMillis(), 14)
        return if (recent.isEmpty()) 15 else recent.map { it.sleepLatencyMinutes }.average().toInt().coerceIn(0, 90)
    }

    suspend fun reschedule(context: Context) {
        val c = context.container
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)
        val user = c.settings.user()
        if (!user.bedtimeReminderEnabled) return
        val alarm = c.settings.alarm()
        val bed = bedtimeMinute(alarm.targetWakeTime, user.sleepGoalMinutes, usualLatency(context))
        val remindAt = Math.floorMod(bed - user.bedtimeReminderLeadMinutes, 1440)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(LocalTime.of(remindAt / 60, remindAt % 60)).atZone(zone)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        am.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), 5 * 60_000L, pi)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, BedtimeReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    suspend fun show(context: Context) {
        val c = context.container
        val user = c.settings.user()
        if (!user.bedtimeReminderEnabled || SleepTrackingService.isRunning) return
        val alarm = c.settings.alarm()
        val bed = bedtimeMinute(alarm.targetWakeTime, user.sleepGoalMinutes, usualLatency(context))
        val goal = "%d h%s".format(user.sleepGoalMinutes / 60, if (user.sleepGoalMinutes % 60 != 0) " %02d".format(user.sleepGoalMinutes % 60) else "")
        val open = PendingIntent.getActivity(
            context, REQUEST + 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, SleepTrackerApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.bedtime_notif_title, Fmt.minuteOfDay(bed), goal))
            .setContentText(context.getString(R.string.bedtime_notif_text, Fmt.minuteOfDay(alarm.targetWakeTime)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.start_tracking), open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }
}

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BedtimeReminder.show(context)
                BedtimeReminder.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Your October report is ready" once per month, on the first app start in a new month. */
object MonthlyReportNotifier {
    private const val NOTIFICATION_ID = 4003

    suspend fun checkAndNotify(context: Context) {
        val c = context.container
        val current = LocalDate.now().withDayOfMonth(1)
        val previous = current.minusMonths(1)
        val key = previous.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        if (c.runtime.lastMonthlyReport() == key) return
        val zone = ZoneId.systemDefault()
        val nights = c.sleep.completedBetween(previous.atStartOfDay(zone).toInstant().toEpochMilli(), current.atStartOfDay(zone).toInstant().toEpochMilli())
        c.runtime.setLastMonthlyReport(key)
        if (nights.isEmpty()) return
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_MONTHLY_REPORT, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val month = previous.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val n = NotificationCompat.Builder(context, SleepTrackerApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.monthly_notif_title, month))
            .setContentText(context.getString(R.string.monthly_notif_text, nights.size))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }
}
