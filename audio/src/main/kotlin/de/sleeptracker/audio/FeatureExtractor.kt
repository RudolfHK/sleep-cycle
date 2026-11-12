package de.sleeptracker.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Acoustic features of one audio frame. */
data class FrameFeatures(
    /** RMS level in dBFS (0 = full scale, typically −90 … −20 at night). */
    val levelDb: Float,
    val zeroCrossingRate: Float,
    /** Spectral centroid in Hz. */
    val centroidHz: Float,
    /** Spectral flatness 0 (tonal) … 1 (white noise). */
    val flatness: Float,
    /** Energy ratio below 400 Hz. */
    val lowRatio: Float,
    /** Energy ratio 300–3400 Hz (speech band). */
    val speechRatio: Float,
)

/**
 * Computes frame features from 16-bit PCM. Re-uses buffers; not thread-safe.
 */
class FeatureExtractor(val sampleRate: Int = 16_000, val frameSize: Int = 1024) {
    private val fft = Fft(frameSize)
    private val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2 * PI * it / (frameSize - 1)) }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val binHz = sampleRate.toDouble() / frameSize
    private var prevDc = 0.0

    fun extract(pcm: ShortArray, offset: Int = 0): FrameFeatures {
        var sumSq = 0.0
        var crossings = 0
        var mean = 0.0
        for (i in 0 until frameSize) mean += pcm[offset + i]
        mean /= frameSize
        prevDc = mean
        var prev = pcm[offset] - mean
        for (i in 0 until frameSize) {
            val s = (pcm[offset + i] - mean) / 32768.0
            sumSq += s * s
            if (i > 0 && (s >= 0) != (prev >= 0)) crossings++
            prev = s
            re[i] = s * window[i]
            im[i] = 0.0
        }
        val rms = sqrt(sumSq / frameSize)
        val levelDb = (20 * log10(max(rms, 1e-5))).toFloat()
        fft.transform(re, im)

        var total = 0.0
        var weighted = 0.0
        var low = 0.0
        var speech = 0.0
        var logSum = 0.0
        var count = 0
        // Ignore DC and sub-50 Hz rumble.
        val firstBin = max(1, (50 / binHz).toInt())
        for (k in firstBin until frameSize / 2) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12
            val f = k * binHz
            total += p
            weighted += p * f
            if (f < 400) low += p
            if (f in 300.0..3400.0) speech += p
            logSum += ln(p)
            count++
        }
        val flatness = if (total > 0) exp(logSum / count) / (total / count) else 1.0
        return FrameFeatures(
            levelDb = levelDb,
            zeroCrossingRate = crossings.toFloat() / frameSize,
            centroidHz = (weighted / total).toFloat(),
            flatness = flatness.toFloat().coerceIn(0f, 1f),
            lowRatio = (low / total).toFloat(),
            speechRatio = (speech / total).toFloat(),
        )
    }
}

/**
 * Adaptive noise floor: follows quiet levels quickly and loud levels very slowly, so short sound
 * events do not raise the floor but a slowly changing background (fan, heating) is absorbed.
 */
class NoiseFloorTracker(initialDb: Float = -70f) {
    var floorDb: Float = initialDb
        private set
    private var initialised = false

    fun update(levelDb: Float): Float {
        if (!initialised) {
            floorDb = levelDb
            initialised = true
        } else if (levelDb < floorDb) {
            floorDb += (levelDb - floorDb) * 0.05f
        } else {
            floorDb += minOf(levelDb - floorDb, 30f) * 0.0008f
        }
        return floorDb
    }
}
