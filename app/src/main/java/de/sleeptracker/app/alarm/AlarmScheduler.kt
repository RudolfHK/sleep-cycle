package de.sleeptracker.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import de.sleeptracker.app.data.repo.AlarmPlan
import de.sleeptracker.app.data.repo.RuntimeStore
import de.sleeptracker.app.ui.MainActivity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules the system alarm. There is only ever one pending alarm PendingIntent (fixed request
 * code), so rescheduling replaces it and duplicates are impossible.
 */
class AlarmScheduler(private val context: Context, private val runtime: RuntimeStore) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(plan: AlarmPlan) {
        val operation = firePendingIntent(plan.instanceId)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context, REQUEST_SHOW, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // setAlarmClock is exact, exempt from Doze and shows the alarm icon in the status bar.
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(plan.nextFireMillis, show), operation)
        } else {
            // Degraded fallback when the user revoked the exact alarm permission.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.nextFireMillis, operation)
        }
    }

    fun cancel() {
        alarmManager.cancel(firePendingIntent(0))
    }

    /**
     * Makes the alarm ring within a few seconds (smart wake-up, or a missed alarm after reboot).
     * This goes through the exact system alarm on purpose: the alarm broadcast is allowed to start
     * the ring service from the background on every Android version, and it re-uses the single
     * PendingIntent, so the deadline alarm is replaced instead of duplicated.
     */
    suspend fun triggerSoon(plan: AlarmPlan, now: Long = System.currentTimeMillis()) {
        val updated = plan.copy(nextFireMillis = now + 1_500L)
        runtime.setPlan(updated)
        schedule(updated)
    }

    /**
     * Re-registers the pending alarm, e.g. after reboot or a clock/time zone change. The wall-clock
     * target time is re-resolved in the current time zone unless the alarm is snoozed.
     */
    suspend fun restore(now: Long = System.currentTimeMillis()) {
        val plan = runtime.plan() ?: return
        if (plan.ringing) return
        val updated = if (plan.snoozeCount == 0 && plan.firstRangAtMillis == null) {
            val deadline = resolveTarget(plan.targetMinuteOfDay, plan.deadlineMillis - 20 * 3_600_000L)
            plan.copy(deadlineMillis = deadline, nextFireMillis = deadline)
        } else plan
        runtime.setPlan(updated)
        if (updated.nextFireMillis <= now) {
            // The alarm time passed while we could not run (e.g. phone was off). Ring now if recent.
            if (now - updated.nextFireMillis < MISSED_ALARM_GRACE_MILLIS) triggerSoon(updated, now)
            else runtime.setPlan(null)
        } else schedule(updated)
    }

    private fun firePendingIntent(instanceId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_INSTANCE, instanceId)
        return PendingIntent.getBroadcast(
            context, REQUEST_FIRE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val REQUEST_FIRE = 1001
        private const val REQUEST_SHOW = 1002
        const val MISSED_ALARM_GRACE_MILLIS = 60 * 60_000L

        /** Next occurrence of [minuteOfDay] (local time) strictly after [afterMillis] + 1 minute. */
        fun resolveTarget(minuteOfDay: Int, afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val after = Instant.ofEpochMilli(afterMillis).atZone(zone)
            val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
            var candidate: ZonedDateTime = after.toLocalDate().atTime(time).atZone(zone)
            if (!candidate.isAfter(after.plusMinutes(1))) candidate = candidate.plusDays(1)
            return candidate.toInstant().toEpochMilli()
        }
    }
}
