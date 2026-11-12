package de.sleeptracker.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Weights of the score components. They are normalised, so they need not sum to 1. */
data class ScoreWeights(
    val duration: Float = 0.30f,
    val efficiency: Float = 0.25f,
    val interruptions: Float = 0.20f,
    val phases: Float = 0.15f,
    val regularity: Float = 0.10f,
)

data class ScoreBreakdown(
    val score: Int,
    val duration: Float,
    val efficiency: Float,
    val interruptions: Float,
    val phases: Float,
    val regularity: Float,
)

/**
 * Sleep quality indicator between 1 and 100. Not a medical assessment.
 */
class SleepScoreCalculator(private val weights: ScoreWeights = ScoreWeights()) {

    /**
     * @param previousBedtimes minutes since 18:00 of previous nights' bedtimes (for regularity).
     * @param zoneOffsetMinutes local UTC offset at bedtime.
     */
    fun score(
        summary: SessionSummary,
        goalMinutes: Int,
        previousBedtimes: List<Int> = emptyList(),
        zoneOffsetMinutes: Int = 0,
    ): ScoreBreakdown {
        if (summary.timeInBedMinutes < 10) return ScoreBreakdown(1, 0f, 0f, 0f, 0f, 0f)
        val goal = goalMinutes.coerceAtLeast(60).toFloat()
        val sleep = summary.sleepMinutes.toFloat()
        val duration = when {
            sleep >= goal -> (1f - ((sleep - goal - 120f).coerceAtLeast(0f) / 240f)).coerceIn(0.7f, 1f)
            else -> (sleep / goal).toDouble().pow(1.5).toFloat()
        }
        val efficiency = ((summary.efficiency - 70f) / (95f - 70f)).coerceIn(0f, 1f)
        val awakeAfterOnset = summary.interruptions.sumOf { it.durationMillis } / 60_000f
        val interruptions = (exp(-0.35 * summary.interruptions.size).toFloat() - awakeAfterOnset / 240f).coerceIn(0f, 1f)
        val phases = if (sleep <= 0f) 0f else {
            val deep = summary.deepMinutes / sleep
            val rem = summary.remMinutes / sleep
            (1f - (abs(deep - 0.18f) + abs(rem - 0.22f)) / 0.4f).coerceIn(0f, 1f)
        }
        val regularity = if (previousBedtimes.isEmpty()) 0.8f else {
            val bed = minutesSinceEvening(summary.startMillis, zoneOffsetMinutes)
            val mean = previousBedtimes.average().toFloat()
            (1f - abs(bed - mean) / 120f).coerceIn(0f, 1f)
        }
        val wSum = weights.duration + weights.efficiency + weights.interruptions + weights.phases + weights.regularity
        val total = (weights.duration * duration + weights.efficiency * efficiency +
            weights.interruptions * interruptions + weights.phases * phases + weights.regularity * regularity) / wSum
        return ScoreBreakdown(
            score = (total * 100f).roundToInt().coerceIn(1, 100),
            duration = duration,
            efficiency = efficiency,
            interruptions = interruptions,
            phases = phases,
            regularity = regularity,
        )
    }

    companion object {
        /** Minutes since 18:00 local time, 0..1439 (so that times around midnight are continuous). */
        fun minutesSinceEvening(epochMillis: Long, zoneOffsetMinutes: Int): Int {
            val minuteOfDay = Math.floorMod(epochMillis / 60_000L + zoneOffsetMinutes, 1440L).toInt()
            return Math.floorMod(minuteOfDay - 18 * 60, 1440)
        }
    }
}

