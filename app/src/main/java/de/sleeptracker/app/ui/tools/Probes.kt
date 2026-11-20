package de.sleeptracker.app.ui.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import de.sleeptracker.audio.BreathingRateEstimator
import de.sleeptracker.audio.FeatureExtractor
import de.sleeptracker.audio.NoiseFloorTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** Live microphone level for the position check and the diagnostics screen (nothing is stored). */
class MicProbe(private val context: Context) {
    data class Reading(val levelDb: Float, val floorDb: Float, val peakDb: Float, val breathsPerMinute: Float?)

    private val _reading = MutableStateFlow<Reading?>(null)
    val reading: StateFlow<Reading?> = _reading
    @Volatile private var running = false
    private var thread: Thread? = null
    var available = true
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { available = false; return }
        val rate = 16_000
        val frame = 1024
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 8))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) { null }
        if (record == null) { available = false; return }
        running = true
        thread = Thread({
            val fx = FeatureExtractor(rate, frame)
            val floor = NoiseFloorTracker()
            val breathing = BreathingRateEstimator(frame.toFloat() / rate, windowSec = 20f)
            val buf = ShortArray(frame)
            var frames = 0
            var peak = -120f
            var bpm: Float? = null
            try {
                record.startRecording()
                while (running) {
                    var read = 0
                    while (read < frame && running) {
                        val n = record.read(buf, read, frame - read)
                        if (n < 0) throw IllegalStateException()
                        read += n
                    }
                    val f = fx.extract(buf)
                    val fl = floor.update(f.levelDb)
                    breathing.add(f.levelDb - fl)
                    peak = maxOf(peak - 0.3f, f.levelDb)
                    if (++frames % 47 == 0) bpm = breathing.estimate()?.breathsPerMinute ?: bpm
                    _reading.value = Reading(f.levelDb, fl, peak, bpm)
                }
            } catch (e: Exception) {
                available = false
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "mic-probe").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}

/** Live accelerometer / light / proximity readings. */
class SensorProbe(context: Context) : SensorEventListener {
    data class Reading(
        val hasAccelerometer: Boolean,
        val gravityZ: Float?,
        /** Standard deviation of the acceleration magnitude over the last ~3 s. */
        val accelSd: Float?,
        val hasLight: Boolean,
        val lux: Float?,
        val hasProximity: Boolean,
        val proximityNear: Boolean?,
    )

    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val light = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val prox = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val mags = ArrayDeque<Float>()
    private var zSum = 0f
    private var zN = 0
    private val _reading = MutableStateFlow(Reading(accel != null, null, null, light != null, null, prox != null, null))
    val reading: StateFlow<Reading> = _reading

    fun start() {
        accel?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        light?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        prox?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val r = _reading.value
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                mags.addLast(sqrt(x * x + y * y + z * z))
                while (mags.size > 150) mags.removeFirst()
                zSum += z; zN++
                val mean = mags.average().toFloat()
                val sd = sqrt(mags.sumOf { ((it - mean) * (it - mean)).toDouble() } / mags.size).toFloat()
                _reading.value = r.copy(gravityZ = zSum / zN, accelSd = if (mags.size >= 30) sd else null)
            }
            Sensor.TYPE_LIGHT -> _reading.value = r.copy(lux = e.values[0])
            Sensor.TYPE_PROXIMITY -> _reading.value = r.copy(proximityNear = e.values[0] < (prox?.maximumRange ?: 5f))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
