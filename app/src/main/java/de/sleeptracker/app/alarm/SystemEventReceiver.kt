package de.sleeptracker.app.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.sleeptracker.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores the alarm after reboot, app update, clock and time zone changes, and when the exact
 * alarm permission is granted. After a reboot, an active tracking session is marked interrupted
 * (its collected data is kept and can be analysed or resumed).
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val c = context.container
                if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
                    c.runtime.activeSession()?.let { id ->
                        c.sleep.markInterrupted(id)
                        c.tracking.notifyInterrupted(id)
                    }
                }
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    "android.intent.action.QUICKBOOT_POWERON",
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                    -> c.alarmScheduler.restore()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
