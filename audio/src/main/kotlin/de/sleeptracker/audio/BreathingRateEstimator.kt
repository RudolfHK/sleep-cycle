package de.sleeptracker.audio

import kotlin.math.sqrt

/**
 * Estimates breaths per minute from the audio envelope. Breathing and snoring make the level rise
 * and fall with each breath; the dominant period of that envelope between 2 and 7.5 s
 * (8–30 breaths/min) is found by normalised autocorrelation over a sliding window.
 *
 * Feed one value per audio frame (level above the noise floor in dB). Not thread-safe.
 */
class BreathingRateEstimator(
    private val frameSec: Float = 1024f / 16_000f,
    windowSec: Float = 60f,
    private val minCorrelation: Float = 0.35f,
) {
    data class Estimate(val breathsPerMinute: Float, val correlation: Float)

    private val size = (windowSec / frameSec).toInt()
    private val buf = FloatArray(size)
    private var count = 0
    private var pos = 0

    fun add(levelAboveFloorDb: Float) {
        buf[pos] = levelAboveFloorDb.coerceIn(0f, 40f)
        pos = (pos + 1) % size
        if (count < size) count++
    }

    fun reset() { count = 0; pos = 0 }

    /** Estimate over the buffered window, or null when no clear breathing rhythm is present. */
    fun estimate(): Estimate? {
        val minFrames = (15f / frameSec).toInt()
        if (count < minFrames) return null
        // Chronological copy of the buffered frames.
        val n = count
        val x = FloatArray(n) { buf[(pos - n + it + size) % size] }
        // Smooth over ~0.25 s to remove frame jitter, then remove the mean.
        val k = maxOf(1, (0.25f / frameSec).toInt())
        val s = FloatArray(n) { i ->
            var sum = 0f; var c = 0
            for (j in (i - k)..(i + k)) if (j in 0 until n) { sum += x[j]; c++ }
            sum / c
        }
        val mean = s.average().toFloat()
        var energy = 0f
        for (i in 0 until n) { s[i] -= mean; energy += s[i] * s[i] }
        // Envelope must actually move (≥ ~1 dB standard deviation).
        if (sqrt(energy / n) < 1f) return null
        val minLag = (2f / frameSec).toInt()
        val maxLag = minOf((7.5f / frameSec).toInt(), n / 2)
        if (maxLag <= minLag + 2) return null
        val r = FloatArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var acc = 0f
            for (i in 0 until n - lag) acc += s[i] * s[i + lag]
            r[lag] = acc / energy * n / (n - lag)
        }
        var best = -1
        for (lag in minLag..maxLag) {
            if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && (best < 0 || r[lag] > r[best])) best = lag
        }
        if (best < 0 || r[best] < minCorrelation) return null
        // Parabolic interpolation around the peak for sub-frame precision.
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val denom = a - 2 * b + c
        val offset = if (denom != 0f) (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f) else 0f
        val period = (best + offset) * frameSec
        return Estimate(60f / period, b)
    }
}
