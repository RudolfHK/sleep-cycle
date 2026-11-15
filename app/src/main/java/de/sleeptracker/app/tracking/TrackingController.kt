package de.sleeptracker.app.tracking

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.R
import de.sleeptracker.app.SleepTrackerApp
import de.sleeptracker.app.alarm.AlarmScheduler
import de.sleeptracker.app.data.db.sensorSource
import de.sleeptracker.app.data.repo.AlarmPlan
import de.sleeptracker.app.ui.MainActivity
import de.sleeptracker.core.SensorSource
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progress of ending a night, observed by the tracking screen. */
sealed interface StopState {
    data object Idle : StopState
    data class Stopping(val sessionId: Long?) : StopState
    data class Done(val sessionId: Long?) : StopState
    data class Failed(val sessionId: Long?) : StopState
}

sealed interface StartResult {
    data class Started(val sessionId: Long, val source: SensorSource, val degraded: Boolean) : StartResult
    data object AlreadyTracking : StartResult
    data object NoSensorAvailable : StartResult
}

/** Starts and stops overnight tracking. All entry points are safe to call from any thread. */
class TrackingController(private val context: Context, private val container: AppContainer) {
    private val mutex = Mutex()

    private val _stopState = MutableStateFlow<StopState>(StopState.Idle)
    val stopState: StateFlow<StopState> = _stopState

    /** Night currently being finalised (hidden from the "interrupted" card meanwhile). */
    private val _finalizing = MutableStateFlow<Long?>(null)
    val finalizing: StateFlow<Long?> = _finalizing

    /**
     * Ends the night from the UI or the notification. Runs once, in the application scope and off
     * the main thread, so leaving the screen or the service shutting down cannot cancel it.
     */
    fun requestStop() {
        if (_stopState.value is StopState.Stopping) return
        _stopState.value = StopState.Stopping(null)
        container.appScope.launch {
            val result = runCatching { stopAndFinalize() }
            _stopState.value = result.fold(
                onSuccess = { id -> StopState.Done(id).also { if (id != null) notifyReportReady(id) } },
                onFailure = { StopState.Failed(container.runtime.activeSession()) },
            )
        }
    }

    /** Called by the screen after it reacted to Done/Failed. */
    fun acknowledgeStop() {
        if (_stopState.value !is StopState.Stopping) _stopState.value = StopState.Idle
    }

    fun hasMicPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasAccelerometer(): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /** The sensor source that can actually be used, falling back when a sensor/permission is missing. */
    fun effectiveSource(requested: SensorSource): SensorSource? {
        val mic = requested.usesSound && hasMicPermission()
        val motion = requested.usesMotion && hasAccelerometer()
        return when {
            mic && motion -> SensorSource.COMBINED
            mic -> SensorSource.SOUND
            motion -> SensorSource.MOTION
            // Requested sensors unavailable: offer whatever else works.
            hasAccelerometer() -> SensorSource.MOTION
            hasMicPermission() -> SensorSource.SOUND
            else -> null
        }
    }

    suspend fun start(mode: TrackingMode): StartResult = mutex.withLock {
        // A finished stop from an earlier night must not end the new tracking screen.
        if (_stopState.value !is StopState.Stopping) _stopState.value = StopState.Idle
        val active = container.runtime.activeSession()
        if (active != null && container.sleep.session(active)?.sessionStatus == SessionStatus.ACTIVE && SleepTrackingService.isRunning) {
            return StartResult.AlreadyTracking
        }
        // A stale active session (process died) is kept as interrupted, never discarded.
        if (active != null) container.sleep.markInterrupted(active)

        val user = container.settings.user()
        val alarm = container.settings.alarm()
        val requested = user.sensorSource()
        val source = effectiveSource(requested) ?: return StartResult.NoSensorAvailable
        val now = System.currentTimeMillis()
        val deadline = if (mode.hasAlarm) AlarmScheduler.resolveTarget(alarm.targetWakeTime, now) else null
        val window = if (mode == TrackingMode.SMART_ALARM) alarm.wakeupWindowMinutes else 0
        val sessionId = container.sleep.createSession(now, mode, source, deadline, window)
        container.runtime.setActiveSession(sessionId)

        // The deadline alarm is scheduled BEFORE tracking starts and independently of analysis.
        if (deadline != null) {
            val plan = AlarmPlan(
                instanceId = now,
                sessionId = sessionId,
                mode = mode,
                targetMinuteOfDay = alarm.targetWakeTime,
                deadlineMillis = deadline,
                windowMinutes = window,
                nextFireMillis = deadline,
            )
            container.runtime.setPlan(plan)
            container.alarmScheduler.schedule(plan)
        } else {
            container.runtime.setPlan(null)
            container.alarmScheduler.cancel()
        }
        startService(sessionId)
        StartResult.Started(sessionId, source, degraded = source != requested)
    }

    private fun startService(sessionId: Long) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, SleepTrackingService::class.java)
                .setAction(SleepTrackingService.ACTION_START)
                .putExtra(SleepTrackingService.EXTRA_SESSION, sessionId),
        )
    }

    /**
     * Stops tracking, persists all buffered data and analyses the night. Cancels a pending alarm
     * that has not rung yet (the user ended the night manually).
     * @return the finalised session id, or null if nothing was being tracked.
     */
    suspend fun stopAndFinalize(): Long? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val id = container.runtime.activeSession()
            _finalizing.value = id
            try {
                // Bounded: a stuck sensor or audio thread must not block the end of the night.
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { SleepTrackingService.instance?.shutdown() }
                    ?: SleepTrackingService.instance?.forceStop()
                if (id == null) return@withLock null
                val plan = container.runtime.plan()
                if (plan != null && !plan.ringing) {
                    container.alarmScheduler.cancel()
                    container.runtime.setPlan(null)
                }
                val end = System.currentTimeMillis()
                try {
                    container.sleep.finalizeSession(id, end)
                } catch (e: Exception) {
                    // Never lose the night: keep it as interrupted so it can be analysed later.
                    container.sleep.markInterrupted(id)
                    container.runtime.setActiveSession(null)
                    throw e
                }
                container.runtime.setActiveSession(null)
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_INTERRUPTED)
                id
            } finally {
                _finalizing.value = null
            }
        }
    }

    /** Continues tracking an interrupted session (data collected so far is kept). */
    suspend fun resume(sessionId: Long) = mutex.withLock {
        val s = container.sleep.session(sessionId) ?: return@withLock
        container.sleep.updateSession(s.copy(sessionStatus = SessionStatus.ACTIVE))
        container.runtime.setActiveSession(sessionId)
        container.alarmScheduler.restore()
        startService(sessionId)
    }

    /** Analyses an interrupted session with the data that was collected. */
    suspend fun finishInterrupted(sessionId: Long) = mutex.withLock {
        val epochs = container.sleep.epochs(sessionId)
        val end = epochs.lastOrNull()?.let { it.startMillis + de.sleeptracker.core.EPOCH_MILLIS }
            ?: container.sleep.session(sessionId)?.startTimestamp ?: System.currentTimeMillis()
        container.sleep.finalizeSession(sessionId, end)
        if (container.runtime.activeSession() == sessionId) container.runtime.setActiveSession(null)
    }

    suspend fun discard(sessionId: Long) = mutex.withLock {
        if (container.runtime.activeSession() == sessionId) container.runtime.setActiveSession(null)
        container.sleep.deleteSession(sessionId, deleteRecordings = true)
    }

    /**
     * Called at process start. If a session is marked active but no tracking service is running
     * (after a crash or kill), it is marked interrupted so the user can resume or finish it.
     */
    suspend fun recoverIfNeeded() {
        val id = container.runtime.activeSession() ?: return
        // A sticky service restart may still be on its way; give it time to come up.
        delay(15_000)
        if (!SleepTrackingService.isRunning && container.runtime.activeSession() == id) {
            container.sleep.markInterrupted(id)
            notifyInterrupted(id)
        }
        // Re-register the alarm in case the process died with it pending.
        container.alarmScheduler.restore()
    }

    fun notifyInterrupted(sessionId: Long) {
        val n = NotificationCompat.Builder(context, SleepTrackerApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.notif_interrupted_title))
            .setContentText(context.getString(R.string.notif_interrupted_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(null))
            .build()
        notify(NOTIFICATION_INTERRUPTED, n)
    }

    fun notifyReportReady(sessionId: Long) {
        val n = NotificationCompat.Builder(context, SleepTrackerApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.notif_report_title))
            .setContentText(context.getString(R.string.notif_report_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(sessionId))
            .build()
        notify(NOTIFICATION_REPORT, n)
    }

    private fun notify(id: Int, n: android.app.Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // Notification permission denied: nothing else to do.
        }
    }

    private fun openApp(sessionId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) intent.putExtra(MainActivity.EXTRA_REPORT_SESSION, sessionId)
        return PendingIntent.getActivity(
            context, (sessionId ?: 0L).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_MILLIS = 15_000L
        const val NOTIFICATION_INTERRUPTED = 3001
        const val NOTIFICATION_REPORT = 3002
    }
}
