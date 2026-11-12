package de.sleeptracker.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import de.sleeptracker.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives the exact system alarm and starts the ring service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val instance = intent.getLongExtra(EXTRA_INSTANCE, 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val plan = context.container.runtime.plan()
                // Ring unless we know for sure this is a stale/duplicate alarm. When in doubt (no
                // plan readable), ringing is preferred over silently skipping.
                val stale = plan != null && (plan.instanceId != instance || plan.ringing)
                if (!stale) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AlarmRingService::class.java)
                            .setAction(AlarmRingService.ACTION_RING)
                            .putExtra(AlarmRingService.EXTRA_INSTANCE, instance)
                            .putExtra(AlarmRingService.EXTRA_REASON, AlarmRingService.REASON_DEADLINE),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "de.sleeptracker.app.ALARM_FIRE"
        const val EXTRA_INSTANCE = "instance"
    }
}
