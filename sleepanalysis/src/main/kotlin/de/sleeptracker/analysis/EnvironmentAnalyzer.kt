package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import kotlin.math.log10
import kotlin.math.pow

/** Bedroom environment of one night. Noise values are dBFS (uncalibrated), light in lux. */
data class EnvironmentSummary(
    val hasNoise: Boolean,
    /** Energy-averaged level over the night, dBFS. */
    val avgNoiseDb: Float,
    val peakNoiseDb: Float,
    /** Quiet baseline (10th percentile of epoch levels), dBFS. */
    val quietBaselineDb: Float,
    /** Minutes with an epoch mean ≥ 10 dB above the quiet baseline. */
    val loudMinutes: Int,
    val hasLight: Boolean,
    val avgLux: Float,
    val maxLux: Float,
    /** Share of epochs (0..100) darker than [DARK_LUX]. */
    val darkPercent: Float,
    /** Minutes at or above [BRIGHT_LUX]. */
    val brightMinutes: Int,
    /** Light at sleep onset, −1 when unknown. */
    val luxAtOnset: Float,
) {
    companion object {
        const val DARK_LUX = 3f
        const val BRIGHT_LUX = 30f
        const val LOUD_ABOVE_BASELINE_DB = 10f
        val NONE = EnvironmentSummary(false, -100f, -100f, -100f, 0, false, -1f, -1f, 0f, 0, -1f)
    }
}

object EnvironmentAnalyzer {

    fun analyze(epochs: List<Epoch>, sleepOnsetMillis: Long? = null): EnvironmentSummary {
        val epochMin = EPOCH_MILLIS / 60_000f
        val sound = epochs.filter { it.hasSound && it.soundLevelDb > -100f }
        val light = epochs.filter { it.hasLight && it.lightLux >= 0f }

        var avgDb = -100f
        var peakDb = -100f
        var baseline = -100f
        var loud = 0
        if (sound.isNotEmpty()) {
            avgDb = (10 * log10(sound.sumOf { 10.0.pow(it.soundLevelDb / 10.0) } / sound.size)).toFloat()
            peakDb = sound.maxOf { maxOf(it.soundPeakDb, it.soundLevelDb) }
            val sorted = sound.map { it.soundLevelDb }.sorted()
            baseline = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
            loud = (sound.count { it.soundLevelDb >= baseline + EnvironmentSummary.LOUD_ABOVE_BASELINE_DB } * epochMin).toInt()
        }

        var avgLux = -1f
        var maxLux = -1f
        var dark = 0f
        var bright = 0
        var atOnset = -1f
        if (light.isNotEmpty()) {
            avgLux = light.sumOf { it.lightLux.toDouble() }.toFloat() / light.size
            maxLux = light.maxOf { maxOf(it.lightMaxLux, it.lightLux) }
            dark = light.count { it.lightLux < EnvironmentSummary.DARK_LUX } * 100f / light.size
            bright = (light.count { it.lightLux >= EnvironmentSummary.BRIGHT_LUX } * epochMin).toInt()
            if (sleepOnsetMillis != null) {
                atOnset = light.minByOrNull { kotlin.math.abs(it.startMillis - sleepOnsetMillis) }?.lightLux ?: -1f
            }
        }
        return EnvironmentSummary(
            hasNoise = sound.isNotEmpty(),
            avgNoiseDb = avgDb,
            peakNoiseDb = peakDb,
            quietBaselineDb = baseline,
            loudMinutes = loud,
            hasLight = light.isNotEmpty(),
            avgLux = avgLux,
            maxLux = maxLux,
            darkPercent = dark,
            brightMinutes = bright,
            luxAtOnset = atOnset,
        )
    }
}
