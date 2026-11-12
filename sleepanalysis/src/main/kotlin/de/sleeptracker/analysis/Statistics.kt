package de.sleeptracker.analysis

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Minimal per-night record used for statistics (decoupled from the database). */
data class NightRecord(
    val startMillis: Long,
    val endMillis: Long,
    /** Local UTC offset in minutes at the start of the night. */
    val zoneOffsetMinutes: Int,
    val sleepMinutes: Int,
    val timeInBedMinutes: Int,
    val latencyMinutes: Int,
    val efficiency: Float,
    val score: Int,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val interruptions: Int,
    val snoreEvents: Int,
    val snoreSeconds: Int,
    val coughEvents: Int,
    val talkEvents: Int,
    val noiseEvents: Int,
)

data class PeriodStatistics(
    val nights: Int,
    val avgSleepMinutes: Float,
    val minSleepMinutes: Int,
    val maxSleepMinutes: Int,
    val avgScore: Float,
    val minScore: Int,
    val maxScore: Int,
    val avgEfficiency: Float,
    val avgLatencyMinutes: Float,
    /** Circular mean bedtime as minute of day (0..1439), null without data. */
    val avgBedtimeMinuteOfDay: Int?,
    val avgWakeMinuteOfDay: Int?,
    /** Circular standard deviation in minutes. */
    val bedtimeSdMinutes: Float,
    val wakeSdMinutes: Float,
    /** 0..100, 100 = perfectly regular bed and wake times. */
    val regularity: Float,
    val avgAwakeMinutes: Float,
    val avgLightMinutes: Float,
    val avgDeepMinutes: Float,
    val avgRemMinutes: Float,
    val avgSnoreEvents: Float,
    val totalSnoreMinutes: Float,
    val avgCoughEvents: Float,
    val avgTalkEvents: Float,
    val avgNoiseEvents: Float,
    /** Accumulated (goal − sleep) in minutes over the period; positive = deficit. Estimate only. */
    val sleepDebtMinutes: Int,
) {
    companion object {
        val EMPTY = PeriodStatistics(
            0, 0f, 0, 0, 0f, 0, 0, 0f, 0f, null, null, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0,
        )
    }
}

object StatisticsCalculator {

    fun compute(nights: List<NightRecord>, goalMinutes: Int): PeriodStatistics {
        if (nights.isEmpty()) return PeriodStatistics.EMPTY
        val n = nights.size.toFloat()
        val bed = nights.map { minuteOfDay(it.startMillis, it.zoneOffsetMinutes) }
        val wake = nights.map { minuteOfDay(it.endMillis, it.zoneOffsetMinutes) }
        val (bedMean, bedSd) = circularMeanAndSd(bed)
        val (wakeMean, wakeSd) = circularMeanAndSd(wake)
        val regularity = (100f - (bedSd + wakeSd) / 2f / 120f * 100f).coerceIn(0f, 100f)
        return PeriodStatistics(
            nights = nights.size,
            avgSleepMinutes = nights.sumOf { it.sleepMinutes } / n,
            minSleepMinutes = nights.minOf { it.sleepMinutes },
            maxSleepMinutes = nights.maxOf { it.sleepMinutes },
            avgScore = nights.sumOf { it.score } / n,
            minScore = nights.minOf { it.score },
            maxScore = nights.maxOf { it.score },
            avgEfficiency = nights.sumOf { it.efficiency.toDouble() }.toFloat() / n,
            avgLatencyMinutes = nights.sumOf { it.latencyMinutes } / n,
            avgBedtimeMinuteOfDay = bedMean,
            avgWakeMinuteOfDay = wakeMean,
            bedtimeSdMinutes = bedSd,
            wakeSdMinutes = wakeSd,
            regularity = regularity,
            avgAwakeMinutes = nights.sumOf { it.awakeMinutes } / n,
            avgLightMinutes = nights.sumOf { it.lightMinutes } / n,
            avgDeepMinutes = nights.sumOf { it.deepMinutes } / n,
            avgRemMinutes = nights.sumOf { it.remMinutes } / n,
            avgSnoreEvents = nights.sumOf { it.snoreEvents } / n,
            totalSnoreMinutes = nights.sumOf { it.snoreSeconds } / 60f,
            avgCoughEvents = nights.sumOf { it.coughEvents } / n,
            avgTalkEvents = nights.sumOf { it.talkEvents } / n,
            avgNoiseEvents = nights.sumOf { it.noiseEvents } / n,
            sleepDebtMinutes = nights.sumOf { goalMinutes - it.sleepMinutes },
        )
    }

    fun minuteOfDay(epochMillis: Long, zoneOffsetMinutes: Int): Int =
        Math.floorMod(epochMillis / 60_000L + zoneOffsetMinutes, 1440L).toInt()

    /** Circular mean (minute of day) and circular SD in minutes. Handles midnight wrap-around. */
    fun circularMeanAndSd(minutes: List<Int>): Pair<Int?, Float> {
        if (minutes.isEmpty()) return null to 0f
        var s = 0.0
        var c = 0.0
        for (m in minutes) {
            val a = m / 1440.0 * 2 * PI
            s += sin(a); c += cos(a)
        }
        s /= minutes.size; c /= minutes.size
        val mean = atan2(s, c)
        val meanMin = Math.floorMod((mean / (2 * PI) * 1440).roundToInt(), 1440)
        val r = sqrt(s * s + c * c).coerceIn(1e-9, 1.0)
        val sdRad = sqrt(-2 * kotlin.math.ln(r))
        return meanMin to (sdRad / (2 * PI) * 1440).toFloat()
    }
}
