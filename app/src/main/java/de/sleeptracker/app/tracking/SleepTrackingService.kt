package de.sleeptracker.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.sleeptracker.analysis.EpochAggregator
import de.sleeptracker.analysis.MotionProcessor
import de.sleeptracker.analysis.SmartAlarmDecider
import de.sleeptracker.app.R
import de.sleeptracker.app.SleepTrackerApp
import de.sleeptracker.app.container
import de.sleeptracker.app.data.db.MovementEventRecord
import de.sleeptracker.app.data.db.SoundEventRecord
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.ui.MainActivity
import de.sleeptracker.audio.SoundEpisodes
import de.sleeptracker.audio.SoundEventDetector
import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.MovementType
import de.sleeptracker.core.SensorSource
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.SoundCategory
import de.sleeptracker.core.SoundEventType
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Live status shown on the tracking screen. */
data class LiveTrackingStatus(
    val sessionId: Long,
    val startMillis: Long,
    val mode: TrackingMode,
    val source: SensorSource,
    val micActive: Boolean,
    val motionActive: Boolean,
    val alarmDeadlineMillis: Long?,
    val alarmWindowMinutes: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val epochs: Int,
    val soundEvents: Int,
    val problem: String? = null,
    val lightActive: Boolean = false,
    /** Latest ambient light in lux, −1 when unknown. */
    val currentLux: Float = -1f,
    /** Mean level of the last epoch in dBFS, null when no audio. */
    val currentNoiseDb: Float? = null,
    val lightReadings: Int = 0,
)

/**
 * Foreground service that records sleep through the night.
 *
 * * Accelerometer → [MotionProcessor] → motion events (sensor thread).
 * * Microphone → [SoundEventDetector] (audio thread). Audio stays in memory; only excerpts of
 *   detected events are stored, encrypted.
 * * Every 30 s the [EpochAggregator] closes an epoch; epochs are written to Room every 5 minutes,
 *   so a crash loses at most a few minutes.
 * * In smart-alarm mode the [SmartAlarmDecider] is consulted every epoch inside the window.
 */
class SleepTrackingService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val dbMutex = Mutex()
    private val stopping = AtomicBoolean(false)

    private var sessionId = -1L
    private var startMillis = 0L
    private var mode = TrackingMode.TRACKING_ONLY
    private var source = SensorSource.MOTION
    private var user = UserSettings()

    private var aggregator: EpochAggregator? = null
    private var motion: MotionProcessor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorManager: SensorManager? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var micActive = false
    @Volatile private var motionActive = false
    @Volatile private var lightActive = false
    @Volatile private var lastLux = -1f
    private var lightSensor: Sensor? = null
    private var lightRegisteredAt = 0L
    private val lightReadings = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastExcerptAt = HashMap<SoundCategory, Long>()
    private var audioEffects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickerJob: Job? = null
    private var decider: SmartAlarmDecider? = null
    private var smartTriggered = false
    private var alarmDeadline: Long? = null
    private var alarmWindow = 0

    private val nightEpochs = ArrayList<Epoch>()
    private val pendingEpochs = ArrayList<Epoch>()
    private val pendingMovements = ArrayList<MovementEventRecord>()
    private var soundEventCount = 0
    private var lastFlushMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { applicationContext.container.tracking.stopAndFinalize() }
            return START_NOT_STICKY
        }
        val wantsMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Enter the foreground right away; a restart from the background may not use the mic.
        val micAllowed = enterForeground(wantsMic && intent != null, getString(R.string.tracking_notification_starting))
        if (sessionId > 0) return START_STICKY // already running

        val requestedSession = intent?.getLongExtra(EXTRA_SESSION, -1L) ?: -1L
        scope.launch {
            val c = applicationContext.container
            val id = if (requestedSession > 0) requestedSession else c.runtime.activeSession() ?: -1L
            val session = if (id > 0) c.sleep.session(id) else null
            if (session == null || session.sessionStatus == SessionStatus.COMPLETED) {
                withContext(Dispatchers.Main) { stopSelfCompletely() }
                return@launch
            }
            if (session.sessionStatus != SessionStatus.ACTIVE) c.sleep.updateSession(session.copy(sessionStatus = SessionStatus.ACTIVE))
            sessionId = session.id
            startMillis = session.startTimestamp
            mode = session.trackingMode
            user = c.settings.user()
            source = session.sensorSource
            val useMic = source.usesSound && micAllowed
            val useMotion = source.usesMotion
            val useLight = user.lightTrackingEnabled &&
                getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

            // Restore earlier epochs (after a process restart) so smart alarm sees the whole night.
            val stored = c.sleep.epochs(session.id)
            synchronized(lock) {
                nightEpochs.clear(); nightEpochs.addAll(stored)
                val now = System.currentTimeMillis()
                val elapsedEpochs = ((now - startMillis) / EPOCH_MILLIS).coerceAtLeast(0)
                aggregator = EpochAggregator(startMillis + elapsedEpochs * EPOCH_MILLIS, useMotion, useMic, useLight)
            }
            c.runtime.plan()?.takeIf { it.sessionId == session.id }?.let {
                alarmDeadline = it.deadlineMillis
                alarmWindow = it.windowMinutes
                if (it.mode == TrackingMode.SMART_ALARM) decider = SmartAlarmDecider(it.windowStartMillis, it.deadlineMillis)
            }
            acquireWakeLock()
            withContext(Dispatchers.Main) {
                if (useMotion) startMotion()
                if (useMic) startAudio()
                if (useLight) startLight()
                if (!motionActive && !micActive) {
                    // Last resort: try whichever sensor is available so the night is not lost.
                    if (!useMotion) startMotion()
                    if (!micActive && micAllowed) startAudio()
                }
                updateNotification()
            }
            lastFlushMillis = System.currentTimeMillis()
            tickerJob = scope.launch { tickLoop() }
        }
        return START_STICKY
    }

    /** @return whether the microphone foreground type could be used. */
    private fun enterForeground(withMic: Boolean, text: String): Boolean {
        val notification = buildNotification(text)
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (withMic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            withMic
        } catch (e: Exception) {
            // e.g. mic type not allowed when restarted from the background: continue motion-only.
            val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
            false
        }
    }

    // --- Motion -----------------------------------------------------------------------------

    private fun sensorHandler(): Handler {
        val thread = sensorThread ?: HandlerThread("sensors").apply { start() }.also { sensorThread = it }
        return Handler(thread.looper)
    }

    private fun startMotion() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        motion = MotionProcessor(user.motionSensitivity)
        sensorManager = sm
        // ~20 Hz, delivered in batches of up to 2 s to let the sensor hub buffer samples.
        motionActive = sm.registerListener(this, accel, 50_000, 2_000_000, sensorHandler())
    }

    /**
     * Ambient light. It is an on-change sensor, and many devices stop reporting it while the screen
     * is off, so it is registered without batching and re-registered every few minutes (a fresh
     * registration delivers the current value on most devices).
     */
    private fun startLight() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        sensorManager = sm
        lightSensor = light
        lightActive = sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler())
        lightRegisteredAt = System.currentTimeMillis()
    }

    private fun refreshLight(now: Long) {
        val sm = sensorManager ?: return
        val light = lightSensor ?: return
        if (now - lightRegisteredAt < LIGHT_REFRESH_MILLIS) return
        sm.unregisterListener(this, light)
        lightActive = sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler())
        lightRegisteredAt = now
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Sensor timestamps are monotonic nanos; convert to wall clock.
        val ts = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            lastLux = lux
            lightReadings.incrementAndGet()
            synchronized(lock) { aggregator?.onLight(ts, lux) }
            return
        }
        val mp = motion ?: return
        val result = mp.onSample(ts, event.values[0], event.values[1], event.values[2])
        synchronized(lock) {
            val agg = aggregator ?: return
            agg.onMotionSample(ts)
            if (result != null) {
                agg.onMotionEvent(result.timestampMillis, result.intensity)
                if (result.large) {
                    pendingMovements += MovementEventRecord(
                        sessionId = sessionId,
                        timestamp = result.timestampMillis,
                        movementIntensity = result.intensity,
                        movementDuration = 1,
                        movementType = MovementType.LARGE,
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Audio ------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        // MIC has the higher, more natural gain on most phones; VOICE_RECOGNITION as fallback.
        val record = listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION).firstNotNullOfOrNull { src ->
            try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 2 * 8))
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (e: Exception) {
                null
            }
        } ?: return
        // Keep levels stable over the night: no automatic gain, noise suppression or echo cancelling.
        audioEffects = buildList<android.media.audiofx.AudioEffect> {
            runCatching {
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    android.media.audiofx.AutomaticGainControl.create(record.audioSessionId)?.let { it.enabled = false; add(it) }
                }
            }
            runCatching {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.let { it.enabled = false; add(it) }
                }
            }
            runCatching {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.let { it.enabled = false; add(it) }
                }
            }
        }
        val detector = SoundEventDetector(SAMPLE_RATE, FRAME, user.soundSensitivity, captureExcerpts = user.audioRecordingEnabled)
        audioRunning = true
        micActive = true
        audioThread = Thread({ audioLoop(record, detector) }, "audio").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun audioLoop(record: AudioRecord, detector: SoundEventDetector) {
        val buffer = ShortArray(FRAME)
        try {
            record.startRecording()
            while (audioRunning) {
                var read = 0
                while (read < FRAME && audioRunning) {
                    val n = record.read(buffer, read, FRAME - read)
                    if (n < 0) throw IllegalStateException("AudioRecord error $n")
                    read += n
                }
                if (!audioRunning) break
                val ts = System.currentTimeMillis() - FRAME * 1000L / SAMPLE_RATE
                val r = detector.process(buffer, ts)
                synchronized(lock) { aggregator?.onAudioFrame(ts, r.levelDb, r.active) }
                r.event?.let { handleSoundEvent(it) }
            }
            detector.flush()?.let { handleSoundEvent(it) }
        } catch (e: Exception) {
            // Microphone lost (revoked, taken by another app): keep tracking with motion.
            micActive = false
            scope.launch(Dispatchers.Main) { updateNotification() }
        } finally {
            runCatching { record.stop() }
            audioEffects.forEach { runCatching { it.release() } }
            audioEffects = emptyList()
            record.release()
        }
    }

    private fun categoryEnabled(type: SoundEventType) = when (type.category) {
        SoundCategory.SNORING -> user.detectSnoring
        SoundCategory.COUGHING -> user.detectCoughing
        SoundCategory.TALKING -> user.detectTalking
        SoundCategory.NOISE -> user.detectNoise
    }

    private fun handleSoundEvent(detected: SoundEventDetector.DetectedEvent) {
        val e = detected.event
        synchronized(lock) { aggregator?.onSoundEvent(e.startMillis, e.type == SoundEventType.SNORE, e.durationMillis) }
        if (!categoryEnabled(e.type)) return
        // Faint or very short environmental noise is counted as activity only, not stored.
        if (!SoundEpisodes.isReportable(e)) return
        soundEventCount++
        // At most one audio excerpt per category every few minutes (one per snoring episode, etc.).
        val keepAudio = synchronized(lastExcerptAt) {
            val last = lastExcerptAt[e.type.category] ?: Long.MIN_VALUE
            if (e.startMillis - last >= EXCERPT_INTERVAL_MILLIS) { lastExcerptAt[e.type.category] = e.startMillis; true } else false
        }
        val sid = sessionId
        scope.launch {
            val c = applicationContext.container
            dbMutex.withLock {
                val id = c.sleep.addSoundEvent(
                    SoundEventRecord(
                        sessionId = sid,
                        timestamp = e.startMillis,
                        eventType = e.type,
                        durationSeconds = e.durationMillis / 1000f,
                        confidence = e.confidence,
                        intensityDb = e.intensityDb,
                    ),
                )
                val pcm = detected.pcm
                if (user.audioRecordingEnabled && pcm != null && keepAudio) {
                    val path = c.recordings.save(sid, id, e.type, e.startMillis, pcm, SAMPLE_RATE, user.automaticRecordingDeletionDays)
                    if (path != null) c.database.signalDao().setAudioPath(id, path)
                }
            }
        }
    }

    // --- Epoch ticker -----------------------------------------------------------------------

    private suspend fun tickLoop() {
        while (scope.isActive && !stopping.get()) {
            val now = System.currentTimeMillis()
            val nextBoundary = startMillis + ((now - startMillis) / EPOCH_MILLIS + 1) * EPOCH_MILLIS
            delay((nextBoundary - now).coerceAtLeast(1_000L))
            if (stopping.get()) break
            tick(System.currentTimeMillis())
        }
    }

    private suspend fun tick(now: Long) {
        val closed: List<Epoch>
        val snapshot: List<Epoch>
        synchronized(lock) {
            val agg = aggregator ?: return
            agg.advanceTo(now)
            closed = agg.drain()
            nightEpochs += closed
            pendingEpochs += closed
            snapshot = if (decider != null) nightEpochs.toList() else emptyList()
        }
        refreshLight(now)
        if (now - lastFlushMillis >= FLUSH_INTERVAL_MILLIS) flush()
        evaluateSmartAlarm(now, snapshot)
        if (closed.isNotEmpty()) withContext(Dispatchers.Main) { updateNotification() }
    }

    private suspend fun evaluateSmartAlarm(now: Long, epochs: List<Epoch>) {
        val d = decider ?: return
        if (smartTriggered) return
        val decision = try {
            d.evaluate(now, epochs)
        } catch (t: Throwable) {
            SmartAlarmDecider.Decision.WAIT // the system deadline alarm still fires
        }
        if (decision == SmartAlarmDecider.Decision.WAKE_LIGHT_SLEEP) {
            val c = applicationContext.container
            val plan = c.runtime.plan() ?: return
            if (plan.sessionId != sessionId || plan.ringing || plan.snoozeCount > 0) return
            smartTriggered = true
            c.alarmScheduler.triggerSoon(plan, now)
        }
        // WAKE_DEADLINE is handled by the system alarm scheduled at start.
    }

    private suspend fun flush() {
        val epochs: List<Epoch>
        val moves: List<MovementEventRecord>
        synchronized(lock) {
            epochs = pendingEpochs.toList(); pendingEpochs.clear()
            moves = pendingMovements.toList(); pendingMovements.clear()
        }
        if (epochs.isEmpty() && moves.isEmpty()) return
        val c = applicationContext.container
        try {
            dbMutex.withLock {
                c.sleep.appendEpochs(sessionId, epochs)
                c.sleep.appendMovements(moves)
                if (lightSensor != null) c.database.sessionDao().setLightReadings(sessionId, lightReadings.get())
            }
            lastFlushMillis = System.currentTimeMillis()
        } catch (e: Exception) {
            // Database error: keep data in memory and retry with the next flush.
            synchronized(lock) { pendingEpochs.addAll(0, epochs); pendingMovements.addAll(0, moves) }
        }
    }

    /** Stops sensors and writes everything to the database. Safe to call more than once. */
    suspend fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        tickerJob?.cancel()
        withContext(Dispatchers.Main) { stopSensors() }
        audioThread?.join(2_000)
        withContext(NonCancellable) {
            synchronized(lock) {
                aggregator?.let { agg ->
                    val rest = agg.finish(System.currentTimeMillis())
                    nightEpochs += rest; pendingEpochs += rest
                }
            }
            flush()
            // Wait briefly for pending sound-event writes.
            dbMutex.withLock { }
        }
        withContext(Dispatchers.Main) { stopSelfCompletely() }
    }

    private fun stopSensors() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        sensorThread?.quitSafely()
        sensorThread = null
        motionActive = false
        lightActive = false
        audioRunning = false
    }

    private fun stopSelfCompletely() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        status.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping.get() && sessionId > 0) {
            // Killed without an orderly stop: persist what we have (bounded time).
            stopping.set(true)
            stopSensors()
            runBlocking {
                withTimeoutOrNull(3_000) {
                    synchronized(lock) {
                        aggregator?.let { val rest = it.finish(System.currentTimeMillis()); pendingEpochs += rest }
                    }
                    flush()
                }
            }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        status.value = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Misc -------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleeptracker:tracking").apply {
            setReferenceCounted(false)
            acquire(MAX_TRACKING_MILLIS)
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to plugged
    }

    private fun updateNotification() {
        if (sessionId <= 0) return
        val (pct, charging) = battery()
        status.value = LiveTrackingStatus(
            sessionId = sessionId,
            startMillis = startMillis,
            mode = mode,
            source = source,
            micActive = micActive,
            motionActive = motionActive,
            alarmDeadlineMillis = alarmDeadline,
            alarmWindowMinutes = alarmWindow,
            batteryPercent = pct,
            charging = charging,
            epochs = synchronized(lock) { nightEpochs.size },
            soundEvents = soundEventCount,
            problem = if (!micActive && !motionActive) getString(R.string.tracking_no_sensor) else null,
            lightActive = lightActive,
            currentLux = lastLux,
            lightReadings = lightReadings.get(),
            currentNoiseDb = synchronized(lock) { nightEpochs.lastOrNull()?.takeIf { it.hasSound }?.soundLevelDb },
        )
        val text = buildString {
            append(getString(R.string.tracking_notification_text))
            if (!micActive && source.usesSound) append(" · ").append(getString(R.string.tracking_mic_off))
        }
        val nm = getSystemService(android.app.NotificationManager::class.java)
        try { nm.notify(NOTIFICATION_ID, buildNotification(text)) } catch (e: SecurityException) { }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TRACKING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SleepTrackerApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.tracking_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "de.sleeptracker.app.TRACK_START"
        const val ACTION_STOP = "de.sleeptracker.app.TRACK_STOP"
        const val EXTRA_SESSION = "session"
        const val NOTIFICATION_ID = 1001
        const val SAMPLE_RATE = 16_000
        const val FRAME = 1024
        private const val FLUSH_INTERVAL_MILLIS = 5 * 60_000L
        private const val LIGHT_REFRESH_MILLIS = 3 * 60_000L
        private const val EXCERPT_INTERVAL_MILLIS = 3 * 60_000L
        private const val MAX_TRACKING_MILLIS = 16 * 3_600_000L

        @Volatile
        var instance: SleepTrackingService? = null
            private set

        val isRunning: Boolean get() = instance?.let { !it.stopping.get() && it.sessionId > 0 } ?: false

        internal val status = MutableStateFlow<LiveTrackingStatus?>(null)
        val liveStatus: StateFlow<LiveTrackingStatus?> = status
    }
}
