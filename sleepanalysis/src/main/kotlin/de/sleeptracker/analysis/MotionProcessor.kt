package de.sleeptracker.analysis

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns raw accelerometer samples into discrete motion events.
 *
 * Gravity is removed with a slow low-pass filter on the vector magnitude. The remaining
 * deviation is compared to an adaptive baseline (sensor noise of the specific device on its
 * specific surface), so the detector works both on a mattress and on a bedside table.
 *
 * @param sensitivity 0..1, higher detects smaller movements.
 */
class MotionProcessor(sensitivity: Float = 0.5f) {

    data class MotionEvent(val timestampMillis: Long, val intensity: Float, val large: Boolean)

    private val sensitivity = sensitivity.coerceIn(0f, 1f)
    private var gravity = Float.NaN
    private var baseline = 0.02f
    private var lastEventMillis = Long.MIN_VALUE
    private var pendingPeak = 0f
    private var pendingStart = Long.MIN_VALUE
    private var lastSampleMillis = Long.MIN_VALUE

    /** Multiplier of the baseline above which a sample counts as movement. */
    private val factor: Float get() = 6f - 4f * sensitivity
    /** Absolute minimum deviation (m/s²) to suppress pure sensor noise. */
    private val minDelta: Float get() = 0.06f - 0.045f * sensitivity

    val currentThreshold: Float get() = max(baseline * factor, minDelta)

    /**
     * Feeds one sample. Returns a motion event when a movement burst has ended.
     */
    fun onSample(timestampMillis: Long, x: Float, y: Float, z: Float): MotionEvent? {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (gravity.isNaN()) {
            gravity = magnitude
            lastSampleMillis = timestampMillis
            return null
        }
        val deviation = kotlin.math.abs(magnitude - gravity)
        val threshold = currentThreshold
        // Low-pass gravity estimate (time constant ≈ 2 s while still). During movement it adapts
        // only very slowly so that a burst does not drag the reference along.
        gravity += (magnitude - gravity) * (if (deviation > threshold) 0.002f else 0.03f)
        lastSampleMillis = timestampMillis

        if (deviation > threshold) {
            if (pendingStart == Long.MIN_VALUE) pendingStart = timestampMillis
            pendingPeak = max(pendingPeak, deviation - threshold)
            // A burst that lasts longer than 2 s is reported in chunks.
            if (timestampMillis - pendingStart > 2_000) return flush(timestampMillis)
            return null
        }
        // Only adapt the noise baseline while still.
        baseline += (deviation - baseline) * 0.002f
        baseline = baseline.coerceIn(0.002f, 0.5f)
        // The burst ended with this quiet sample.
        return if (pendingStart != Long.MIN_VALUE) flush(timestampMillis) else null
    }

    private fun flush(now: Long): MotionEvent? {
        val peak = pendingPeak
        val start = pendingStart
        pendingPeak = 0f
        pendingStart = Long.MIN_VALUE
        // Refractory period: ignore events closer than 500 ms to the previous one.
        if (lastEventMillis != Long.MIN_VALUE && start - lastEventMillis < 500) {
            lastEventMillis = now
            return null
        }
        lastEventMillis = now
        return MotionEvent(start, peak, large = peak > currentThreshold * 8 || now - start > 1_500)
    }

    fun reset() {
        gravity = Float.NaN
        baseline = 0.02f
        pendingPeak = 0f
        pendingStart = Long.MIN_VALUE
        lastEventMillis = Long.MIN_VALUE
    }
}
