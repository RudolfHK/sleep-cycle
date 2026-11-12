package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import kotlin.math.log10
import kotlin.math.pow

/**
 * Collects motion events, audio frame levels and sound events into fixed 30-second epochs.
 * Not thread-safe; callers must serialise access.
 */
class EpochAggregator(
    sessionStartMillis: Long,
    private val motionEnabled: Boolean,
    private val soundEnabled: Boolean,
    private val lightEnabled: Boolean = false,
) {
    private var currentStart = sessionStartMillis
    private var motionCount = 0
    private var motionIntensity = 0f
    private var frames = 0
    private var activeFrames = 0
    private var energySum = 0.0
    private var events = 0
    private var snoreSeconds = 0f
    private var peakDb = -100f
    // Light is an on-change sensor: the last reading is carried into following epochs.
    private var lastLux = -1f
    private var luxSum = 0.0
    private var luxCount = 0
    private var luxMax = -1f
    private var motionSamplesSeen = false

    /** Completed epochs not yet collected by [drain]. */
    private val completed = ArrayList<Epoch>()

    fun onMotionEvent(timestampMillis: Long, intensity: Float) {
        advanceTo(timestampMillis)
        motionCount++
        motionIntensity += intensity
    }

    /** Call for every accelerometer sample (even when no motion is detected) to mark signal presence. */
    fun onMotionSample(timestampMillis: Long) {
        advanceTo(timestampMillis)
        motionSamplesSeen = true
    }

    fun onAudioFrame(timestampMillis: Long, levelDb: Float, active: Boolean) {
        advanceTo(timestampMillis)
        frames++
        if (active) activeFrames++
        energySum += 10.0.pow(levelDb / 10.0)
        if (levelDb > peakDb) peakDb = levelDb
    }

    fun onLight(timestampMillis: Long, lux: Float) {
        advanceTo(timestampMillis)
        val v = lux.coerceAtLeast(0f)
        lastLux = v
        luxSum += v
        luxCount++
        if (v > luxMax) luxMax = v
    }

    fun onSoundEvent(timestampMillis: Long, isSnore: Boolean = false, durationMillis: Long = 0) {
        advanceTo(timestampMillis)
        events++
        if (isSnore) snoreSeconds += durationMillis / 1000f
    }

    /** Closes all epochs that end before [nowMillis] (also produces empty epochs for gaps). */
    fun advanceTo(nowMillis: Long) {
        while (nowMillis >= currentStart + EPOCH_MILLIS) {
            completed += buildCurrent()
            currentStart += EPOCH_MILLIS
            resetCounters()
        }
    }

    /** Returns and removes completed epochs. */
    fun drain(): List<Epoch> {
        val out = completed.toList()
        completed.clear()
        return out
    }

    /** Closes the running (partial) epoch, e.g. when tracking stops. */
    fun finish(nowMillis: Long): List<Epoch> {
        advanceTo(nowMillis)
        if (nowMillis - currentStart >= EPOCH_MILLIS / 3) {
            completed += buildCurrent()
            currentStart += EPOCH_MILLIS
            resetCounters()
        }
        return drain()
    }

    private fun buildCurrent(): Epoch {
        val hasSound = soundEnabled && frames > 0
        return Epoch(
            startMillis = currentStart,
            motionCount = motionCount,
            motionIntensity = motionIntensity,
            soundActivity = if (frames > 0) activeFrames.toFloat() / frames else 0f,
            soundLevelDb = if (frames > 0) (10 * log10(energySum / frames)).toFloat() else -100f,
            soundEventCount = events,
            snoreSeconds = snoreSeconds.coerceAtMost(30f),
            hasMotion = motionEnabled && (motionSamplesSeen || motionCount > 0),
            hasSound = hasSound,
            soundPeakDb = if (frames > 0) peakDb else -100f,
            lightLux = if (luxCount > 0) (luxSum / luxCount).toFloat() else -1f,
            lightMaxLux = if (luxCount > 0) luxMax else -1f,
            hasLight = lightEnabled && luxCount > 0,
        )
    }

    private fun resetCounters() {
        motionCount = 0
        motionIntensity = 0f
        frames = 0
        activeFrames = 0
        energySum = 0.0
        events = 0
        snoreSeconds = 0f
        peakDb = -100f
        motionSamplesSeen = false
        // Start the next epoch with the carried-over light value.
        if (lastLux >= 0f) {
            luxSum = lastLux.toDouble(); luxCount = 1; luxMax = lastLux
        } else {
            luxSum = 0.0; luxCount = 0; luxMax = -1f
        }
    }
}
