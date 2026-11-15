package de.sleeptracker.app.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.sleeptracker.analysis.SnoozeCalculator
import de.sleeptracker.app.R
import de.sleeptracker.app.SleepTrackerApp
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.AlarmSettings
import de.sleeptracker.app.data.repo.AlarmPlan
import de.sleeptracker.app.ui.MainActivity
import de.sleeptracker.app.ui.alarm.AlarmActivity
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import de.sleeptracker.app.tracking.StopState
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** What the alarm screen shows while ringing. */
data class RingUiState(
    val instanceId: Long,
    val alarmMillis: Long,
    val deadlineMillis: Long,
    val windowMinutes: Int,
    val mode: TrackingMode,
    val nextSnoozeMinutes: Int?,
    val snoozeCount: Int,
    val soundTitle: String,
    val confirmationRequired: Boolean,
    val volumeButtonAction: de.sleeptracker.app.data.db.VolumeButtonAction,
)

object AlarmRingState {
    internal val mutable = MutableStateFlow<RingUiState?>(null)
    val state: StateFlow<RingUiState?> = mutable

    /** Session whose report should open after the alarm was stopped. */
    @Volatile
    var lastReportSessionId: Long? = null
}

/**
 * Plays the alarm. Runs as its own foreground service so it works with the screen off, from the
 * background and even if the tracking service died.
 */
class AlarmRingService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var ringing = false
    private var plan: AlarmPlan? = null
    private var settings: AlarmSettings = AlarmSettings()
    private var savedAlarmVolume: Int? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground immediately to satisfy the 5-second rule.
        enterForeground(null)
        when (intent?.action) {
            ACTION_SNOOZE -> scope.launch { snooze() }
            ACTION_DISMISS -> scope.launch { dismiss() }
            else -> {
                val instance = intent?.getLongExtra(EXTRA_INSTANCE, 0L) ?: 0L
                if (!ringing) scope.launch { startRinging(instance) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startRinging(instance: Long) {
        val c = applicationContext.container
        settings = c.settings.alarm()
        val now = System.currentTimeMillis()
        // Mark ringing atomically; a plan that is missing is recreated so the user can still snooze/stop.
        var current = c.runtime.updatePlan { p ->
            p.copy(ringing = true, firstRangAtMillis = p.firstRangAtMillis ?: now)
        }
        if (current == null) {
            current = AlarmPlan(
                instanceId = if (instance != 0L) instance else now,
                sessionId = c.runtime.activeSession() ?: -1L,
                mode = TrackingMode.REGULAR_ALARM,
                targetMinuteOfDay = settings.targetWakeTime,
                deadlineMillis = now,
                windowMinutes = 0,
                nextFireMillis = now,
                ringing = true,
                firstRangAtMillis = now,
            )
            c.runtime.setPlan(current)
        }
        plan = current
        ringing = true
        startedAt = now
        c.alarmScheduler.cancel()
        if (current.sessionId > 0) c.sleep.markAlarmRang(current.sessionId, now)

        acquireWakeLock()
        publishState(current)
        enterForeground(current)
        startSound()
        if (settings.vibrationEnabled) startVibration()
        startGestureDetection()
        // Safety net: stop the sound after 30 minutes (snooze if possible).
        handler.postDelayed({ scope.launch { if (ringing) { if (!snooze()) dismiss() } } }, AUTO_SILENCE_MILLIS)
    }

    private fun nextSnoozeMinutes(p: AlarmPlan, now: Long = System.currentTimeMillis()): Int? {
        val rangEarly = p.mode == TrackingMode.SMART_ALARM && (p.firstRangAtMillis ?: now) < p.deadlineMillis - 60_000L
        // Intelligent snooze never passes the final wake-up time; after a deadline alarm it may
        // extend for at most the configured number of fixed snoozes.
        val finalDeadline = if (rangEarly) p.deadlineMillis
        else p.deadlineMillis + settings.snoozeDurationMinutes * settings.maximumSnoozes * 60_000L
        return SnoozeCalculator.nextSnoozeMinutes(
            settings.snoozeMode, p.snoozeCount, settings.snoozeDurationMinutes, settings.maximumSnoozes, now, finalDeadline,
        )
    }

    /** @return true when snoozed. */
    private suspend fun snooze(): Boolean {
        val c = applicationContext.container
        val p = plan ?: c.runtime.plan() ?: return false
        if (plan == null) settings = c.settings.alarm()
        val minutes = nextSnoozeMinutes(p) ?: return false
        val now = System.currentTimeMillis()
        val next = p.copy(ringing = false, snoozeCount = p.snoozeCount + 1, nextFireMillis = now + minutes * 60_000L)
        c.runtime.setPlan(next)
        c.alarmScheduler.schedule(next)
        stopRinging()
        showSnoozedNotification(next)
        finish()
        return true
    }

    private suspend fun dismiss() {
        val c = applicationContext.container
        val p = plan ?: c.runtime.plan()
        c.alarmScheduler.cancel()
        c.runtime.setPlan(null)
        stopRinging()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_SNOOZED)
        // Waking up ends the night: finish tracking and build the report (same path as the Stop
        // button, so an open tracking screen follows along).
        c.tracking.requestStop()
        val sessionId = withTimeoutOrNull(60_000) {
            when (val st = c.tracking.stopState.first { it is StopState.Done || it is StopState.Failed }) {
                is StopState.Done -> st.sessionId
                else -> null
            }
        }
        val reportId = sessionId ?: p?.sessionId?.takeIf { it > 0 }
        if (reportId != null) c.tracking.notifyReportReady(reportId)
        AlarmRingState.lastReportSessionId = reportId
        finish()
    }

    private fun finish() {
        AlarmRingState.mutable.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(p: AlarmPlan) {
        AlarmRingState.mutable.value = RingUiState(
            instanceId = p.instanceId,
            alarmMillis = p.firstRangAtMillis ?: System.currentTimeMillis(),
            deadlineMillis = p.deadlineMillis,
            windowMinutes = p.windowMinutes,
            mode = p.mode,
            nextSnoozeMinutes = nextSnoozeMinutes(p),
            snoozeCount = p.snoozeCount,
            soundTitle = soundTitle(),
            confirmationRequired = settings.confirmationRequired,
            volumeButtonAction = settings.volumeButtonAction,
        )
    }

    private fun soundTitle(): String = try {
        RingtoneManager.getRingtone(this, soundUri())?.getTitle(this) ?: getString(R.string.alarm_sound_default)
    } catch (e: Exception) {
        getString(R.string.alarm_sound_default)
    }

    private fun soundUri(): Uri =
        settings.alarmSound.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    // --- Sound ------------------------------------------------------------------------------

    private fun startSound() {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = (max * settings.alarmVolume / 100f).toInt().coerceIn(1, max)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            // Do-not-disturb access may prevent volume changes; play at the current volume.
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull(
            soundUri(),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).distinct()
        for (uri in candidates) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                val initial = if (settings.gradualVolumeEnabled) 0.05f else 1f
                mp.setVolume(initial, initial)
                mp.start()
                player = mp
                break
            } catch (e: Exception) {
                // Try the next candidate; vibration still runs if every sound fails.
            }
        }
        if (player == null && !settings.vibrationEnabled) startVibration()
        if (settings.gradualVolumeEnabled) rampVolume(0)
    }

    private fun rampVolume(step: Int) {
        val mp = player ?: return
        val v = ((step + 1) / RAMP_STEPS.toFloat()).coerceIn(0.05f, 1f)
        try { mp.setVolume(v, v) } catch (e: IllegalStateException) { return }
        if (step + 1 < RAMP_STEPS) handler.postDelayed({ rampVolume(step + 1) }, RAMP_MILLIS / RAMP_STEPS)
    }

    private fun startVibration() {
        val vib = getSystemService(Vibrator::class.java) ?: return
        if (!vib.hasVibrator()) return
        vibrator = vib
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopRinging() {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        sensorManager?.unregisterListener(this)
        sensorManager = null
        savedAlarmVolume?.let {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        }
        savedAlarmVolume = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleeptracker:alarm").apply {
            acquire(AUTO_SILENCE_MILLIS + 60_000L)
        }
    }

    // --- Double-tap & pick-up gestures ------------------------------------------------------

    private var gravityRef: FloatArray? = null
    private var lastMagnitude = Float.NaN
    private var lastTapMillis = 0L
    private var gestureArmedAt = 0L

    private fun startGestureDetection() {
        if (!settings.doubleTapSnoozeEnabled && !settings.pickupSnoozeEnabled) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        gestureArmedAt = System.currentTimeMillis() + 1_500L // ignore vibration start-up
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (!ringing || now < gestureArmedAt) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        if (gravityRef == null) { gravityRef = floatArrayOf(x, y, z); lastMagnitude = mag; return }
        val g = gravityRef!!
        if (settings.pickupSnoozeEnabled) {
            val gm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            val cos = ((x * g[0] + y * g[1] + z * g[2]) / (mag * gm)).coerceIn(-1f, 1f)
            val angle = Math.toDegrees(acos(cos).toDouble())
            if (angle > PICKUP_ANGLE_DEGREES) { gestureSnooze(); return }
        }
        if (settings.doubleTapSnoozeEnabled) {
            val jerk = abs(mag - lastMagnitude)
            if (jerk > TAP_JERK) {
                val dt = now - lastTapMillis
                if (dt in 120L..700L) { gestureSnooze(); return }
                if (dt > 120L) lastTapMillis = now
            }
        }
        lastMagnitude = mag
    }

    private fun gestureSnooze() {
        if (!ringing) return
        scope.launch { snooze() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Notifications ----------------------------------------------------------------------

    private fun enterForeground(p: AlarmPlan?) {
        val notification = buildRingNotification(p)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_RING, notification, type)
    }

    private fun buildRingNotification(p: AlarmPlan?): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, SleepTrackerApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.alarm_ringing_title))
            .setContentText(getString(R.string.alarm_ringing_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, getString(R.string.alarm_stop), servicePendingIntent(this, ACTION_DISMISS))
        if (p != null && nextSnoozeMinutes(p) != null) {
            builder.addAction(0, getString(R.string.alarm_snooze), servicePendingIntent(this, ACTION_SNOOZE))
        }
        return builder.build()
    }

    private fun showSnoozedNotification(p: AlarmPlan) {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(p.nextFireMillis))
        val n = NotificationCompat.Builder(this, SleepTrackerApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.alarm_snoozed_until, time))
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, getString(R.string.alarm_stop), servicePendingIntent(this, ACTION_DISMISS))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_SNOOZED, n)
    }

    override fun onDestroy() {
        if (ringing) stopRinging()
        AlarmRingState.mutable.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "de.sleeptracker.app.RING"
        const val ACTION_SNOOZE = "de.sleeptracker.app.SNOOZE"
        const val ACTION_DISMISS = "de.sleeptracker.app.DISMISS"
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_REASON = "reason"
        const val REASON_DEADLINE = "deadline"
        const val REASON_SMART = "smart"
        const val NOTIFICATION_RING = 2001
        const val NOTIFICATION_SNOOZED = 2002
        private const val AUTO_SILENCE_MILLIS = 30 * 60_000L
        private const val RAMP_MILLIS = 60_000L
        private const val RAMP_STEPS = 30
        private const val PICKUP_ANGLE_DEGREES = 35.0
        private const val TAP_JERK = 2.5f

        fun servicePendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context, action.hashCode(),
                Intent(context, AlarmRingService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Sends snooze/dismiss from the alarm screen. */
        fun send(context: Context, action: String) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, AlarmRingService::class.java).setAction(action),
            )
        }
    }
}
