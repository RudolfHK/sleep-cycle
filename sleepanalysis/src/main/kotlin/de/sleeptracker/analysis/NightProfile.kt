package de.sleeptracker.analysis

import de.sleeptracker.core.SleepStage
import kotlin.math.log10
import kotlin.math.pow

/** Input for the typical-night profile: one recorded night. */
data class ProfileNight(
    val zoneOffsetMinutes: Int,
    /** Stage runs: start, duration (ms), stage. */
    val runs: List<Triple<Long, Long, SleepStage>>,
    /** Per-epoch samples: start, noise dBFS (NaN = none), lux (NaN = none). */
    val samples: List<Triple<Long, Float, Float>>,
)

/** One clock-time slot of the profile. Minutes are counted from 18:00 local time. */
data class ProfileSlot(
    val startMinute: Int,
    /** Nights with any stage data in this slot. */
    val nightsInBed: Int,
    /** Share (0..1) of in-bed nights per stage; sums to 1 when [nightsInBed] > 0. */
    val share: Map<SleepStage, Float>,
    /** Energy mean of noise over nights (dBFS), null without data. */
    val noiseDb: Float?,
    /** Mean lux, null without data. */
    val lux: Float?,
    /** Smoothed share (0..1) of all nights that were in bed in this slot; used for drawing. */
    val coverage: Float = 0f,
)

data class NightProfile(val slotMinutes: Int, val totalNights: Int, val slots: List<ProfileSlot>)

/**
 * "Hypnodensity" style average night: for each clock-time slot, how often each stage occurred
 * across nights, plus average noise and light. Clock time is used (not time since bedtime) so the
 * chart answers "when am I usually in deep sleep / when is it loud or bright".
 */
object NightProfileCalculator {

    fun minutesSinceEvening(millis: Long, zoneOffsetMinutes: Int): Int =
        Math.floorMod(Math.floorMod(millis / 60_000L + zoneOffsetMinutes, 1440L).toInt() - 18 * 60, 1440)

    /** Main sleep only: ≥ 3 h in bed and starting between 18:00 and 06:00 local time. */
    fun isMainSleep(startMillis: Long, timeInBedMinutes: Int, zoneOffsetMinutes: Int): Boolean {
        if (timeInBedMinutes < 180) return false
        val minuteOfDay = Math.floorMod(startMillis / 60_000L + zoneOffsetMinutes, 1440L).toInt()
        return minuteOfDay >= 18 * 60 || minuteOfDay < 6 * 60
    }

    /**
     * @param minCoverage leading/trailing slots with fewer than this share of nights in bed are cut.
     * @param smooth half-width (slots) of the light temporal smoothing of the stage shares.
     */
    fun compute(nights: List<ProfileNight>, slotMinutes: Int = 15, minCoverage: Float = 0.15f, smooth: Int = 1): NightProfile {
        val slots = 1440 / slotMinutes
        // Per slot: majority stage per night → counts.
        val stageCounts = Array(slots) { IntArray(SleepStage.entries.size) }
        val inBed = IntArray(slots)
        val noiseEnergy = DoubleArray(slots)
        val noiseN = IntArray(slots)
        val luxSum = DoubleArray(slots)
        val luxN = IntArray(slots)
        for (night in nights) {
            val perSlot = Array(slots) { LongArray(SleepStage.entries.size) }
            for ((start, dur, stage) in night.runs) {
                // Walk the run minute by minute (runs are a few hundred minutes at most).
                var t = start
                val end = start + dur
                while (t < end) {
                    val step = minOf(60_000L, end - t)
                    val slot = minutesSinceEvening(t, night.zoneOffsetMinutes) / slotMinutes
                    perSlot[slot][stage.ordinal] += step
                    t += step
                }
            }
            for (s in 0 until slots) {
                val row = perSlot[s]
                if (row.sum() <= 0L) continue
                inBed[s]++
                stageCounts[s][row.indices.maxBy { row[it] }]++
            }
            // Average per night first, so long nights do not dominate.
            val nNoise = DoubleArray(slots); val cNoise = IntArray(slots)
            val nLux = DoubleArray(slots); val cLux = IntArray(slots)
            for ((t, db, lux) in night.samples) {
                val slot = minutesSinceEvening(t, night.zoneOffsetMinutes) / slotMinutes
                if (!db.isNaN()) { nNoise[slot] += 10.0.pow(db / 10.0); cNoise[slot]++ }
                if (!lux.isNaN()) { nLux[slot] += lux; cLux[slot]++ }
            }
            for (s in 0 until slots) {
                if (cNoise[s] > 0) { noiseEnergy[s] += nNoise[s] / cNoise[s]; noiseN[s]++ }
                if (cLux[s] > 0) { luxSum[s] += nLux[s] / cLux[s]; luxN[s]++ }
            }
        }
        val total = nights.size.coerceAtLeast(1)
        val out = (0 until slots).map { s ->
            // Light smoothing over neighbouring slots, weighted by how many nights were in bed.
            val window = (s - smooth..s + smooth).filter { it in 0 until slots }
            val bedSum = window.sumOf { inBed[it] }
            ProfileSlot(
                startMinute = s * slotMinutes,
                nightsInBed = inBed[s],
                share = SleepStage.entries.associateWith { st ->
                    if (bedSum == 0 || inBed[s] == 0) 0f else window.sumOf { stageCounts[it][st.ordinal] }.toFloat() / bedSum
                },
                noiseDb = if (noiseN[s] > 0) (10 * log10(noiseEnergy[s] / noiseN[s])).toFloat() else null,
                lux = if (luxN[s] > 0) (luxSum[s] / luxN[s]).toFloat() else null,
                coverage = if (inBed[s] == 0) 0f else bedSum.toFloat() / window.size / total,
            )
        }
        // Trim leading/trailing slots that only a few nights reach (stray early/late recordings).
        val needed = maxOf(1, kotlin.math.ceil(minCoverage * nights.size).toInt())
        val first = out.indexOfFirst { it.nightsInBed >= needed }
        val last = out.indexOfLast { it.nightsInBed >= needed }
        return NightProfile(slotMinutes, nights.size, if (first < 0) emptyList() else out.subList(first, last + 1))
    }
}

/** A comparison between two halves of the nights (split at the median of a factor). */
data class Insight(
    val factor: Factor,
    /** Median used to split (unit depends on the factor; null for yes/no factors). */
    val threshold: Float?,
    val lowNights: Int,
    val highNights: Int,
    val lowScore: Float,
    val highScore: Float,
    val lowEfficiency: Float,
    val highEfficiency: Float,
) {
    enum class Factor { NOISE, LIGHT, BEDTIME, WEEKEND, SNORING }

    val scoreDifference: Float get() = highScore - lowScore
}

/** Associations (not causes) between conditions and sleep score in the user's own data. */
object InsightsCalculator {
    const val MIN_NIGHTS = 6

    fun compute(nights: List<NightRecord>): List<Insight> {
        if (nights.size < MIN_NIGHTS) return emptyList()
        val out = ArrayList<Insight>()
        fun split(factor: Insight.Factor, value: (NightRecord) -> Float?) {
            val with = nights.mapNotNull { n -> value(n)?.let { n to it } }
            if (with.size < MIN_NIGHTS) return
            val median = with.map { it.second }.sorted()[with.size / 2]
            val low = with.filter { it.second < median }.map { it.first }
            val high = with.filter { it.second >= median }.map { it.first }
            if (low.size < 3 || high.size < 3) return
            out += insight(factor, median, low, high)
        }
        split(Insight.Factor.NOISE) { it.avgNoiseDb }
        split(Insight.Factor.LIGHT) { it.avgLux }
        // Bedtime relative to the circular mean, so nights around midnight compare correctly.
        val meanBed = StatisticsCalculator.circularMeanAndSd(nights.map { StatisticsCalculator.minuteOfDay(it.startMillis, it.zoneOffsetMinutes) }).first
        if (meanBed != null) {
            split(Insight.Factor.BEDTIME) {
                val m = StatisticsCalculator.minuteOfDay(it.startMillis, it.zoneOffsetMinutes)
                (Math.floorMod(m - meanBed + 720, 1440) - 720).toFloat()
            }
        }
        val wd = nights.filter { !it.isWeekend }
        val we = nights.filter { it.isWeekend }
        if (wd.size >= 3 && we.size >= 3) out += insight(Insight.Factor.WEEKEND, null, wd, we)
        val quiet = nights.filter { it.snoreSeconds <= 0 }
        val snore = nights.filter { it.snoreSeconds > 0 }
        if (quiet.size >= 3 && snore.size >= 3) out += insight(Insight.Factor.SNORING, null, quiet, snore)
        return out
    }

    private fun insight(f: Insight.Factor, threshold: Float?, low: List<NightRecord>, high: List<NightRecord>) = Insight(
        factor = f,
        threshold = threshold,
        lowNights = low.size,
        highNights = high.size,
        lowScore = low.map { it.score }.average().toFloat(),
        highScore = high.map { it.score }.average().toFloat(),
        lowEfficiency = low.map { it.efficiency }.average().toFloat(),
        highEfficiency = high.map { it.efficiency }.average().toFloat(),
    )
}
