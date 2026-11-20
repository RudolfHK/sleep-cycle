package de.sleeptracker.analysis

import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Breathing rate per night
// ---------------------------------------------------------------------------------------------

data class BreathingSummary(
    val averageRate: Float,
    val sd: Float,
    /** Minutes with a reliable estimate. */
    val minutesMeasured: Int,
    val flags: Set<Flag>,
) {
    enum class Flag { LOW, HIGH, IRREGULAR, DIFFERENT_FROM_USUAL }
}

object BreathingAnalyzer {
    const val MIN_MINUTES = 20

    /**
     * @param perEpoch breaths/min per 30-s epoch (≤ 0 or NaN = no estimate).
     * @param usualRates average rates of previous nights (for "different from usual").
     */
    fun summarize(perEpoch: List<Float>, usualRates: List<Float> = emptyList()): BreathingSummary? {
        val v = perEpoch.filter { !it.isNaN() && it > 0f }
        val minutes = v.size / 2
        if (minutes < MIN_MINUTES) return null
        val avg = v.average().toFloat()
        val sd = sqrt(v.sumOf { ((it - avg) * (it - avg)).toDouble() } / v.size).toFloat()
        val flags = buildSet {
            if (avg < 10f) add(BreathingSummary.Flag.LOW)
            if (avg > 24f) add(BreathingSummary.Flag.HIGH)
            if (sd / avg > 0.20f) add(BreathingSummary.Flag.IRREGULAR)
            if (usualRates.size >= 3) {
                val median = usualRates.sorted()[usualRates.size / 2]
                if (abs(avg - median) > 3f) add(BreathingSummary.Flag.DIFFERENT_FROM_USUAL)
            }
        }
        return BreathingSummary(avg, sd, minutes, flags)
    }
}

// ---------------------------------------------------------------------------------------------
// Tag impact
// ---------------------------------------------------------------------------------------------

data class TagImpact(
    val tag: String,
    val withNights: Int,
    val withoutNights: Int,
    /** with − without */
    val scoreDiff: Float,
    /** Deep-sleep minutes with − without (only nights with phase data), null if too few. */
    val deepDiff: Float?,
    /** Minutes to fall asleep with − without. */
    val latencyDiff: Float,
)

object TagImpactCalculator {
    const val MIN_NIGHTS = 2

    fun compute(nights: List<Pair<NightRecord, Set<String>>>, tags: Collection<String>): List<TagImpact> =
        tags.mapNotNull { tag ->
            val with = nights.filter { tag in it.second }.map { it.first }
            val without = nights.filter { tag !in it.second }.map { it.first }
            if (with.size < MIN_NIGHTS || without.size < MIN_NIGHTS) return@mapNotNull null
            val wp = with.filter { it.hasPhases }
            val op = without.filter { it.hasPhases }
            TagImpact(
                tag = tag,
                withNights = with.size,
                withoutNights = without.size,
                scoreDiff = (with.map { it.score }.average() - without.map { it.score }.average()).toFloat(),
                deepDiff = if (wp.size >= MIN_NIGHTS && op.size >= MIN_NIGHTS) (wp.map { it.deepMinutes }.average() - op.map { it.deepMinutes }.average()).toFloat() else null,
                latencyDiff = (with.map { it.latencyMinutes }.average() - without.map { it.latencyMinutes }.average()).toFloat(),
            )
        }.sortedByDescending { abs(it.scoreDiff) }
}

// ---------------------------------------------------------------------------------------------
// Trend alerts
// ---------------------------------------------------------------------------------------------

data class TrendAlert(
    val metric: Metric,
    val before: Float,
    val after: Float,
    /** True when the change goes in the unfavourable direction. */
    val concern: Boolean,
    val nightsBefore: Int,
    val nightsAfter: Int,
) {
    enum class Metric { SNORING, BEDTIME, EFFICIENCY, DURATION, SCORE, LATENCY, BREATHING }

    val change: Float get() = after - before
}

object TrendAlertCalculator {
    const val WINDOW_DAYS = 30
    const val MIN_NIGHTS = 5

    fun compute(nights: List<NightRecord>, nowMillis: Long): List<TrendAlert> {
        val day = 86_400_000L
        val recent = nights.filter { it.endMillis in (nowMillis - WINDOW_DAYS * day)..nowMillis }
        val before = nights.filter { it.endMillis in (nowMillis - 2 * WINDOW_DAYS * day) until (nowMillis - WINDOW_DAYS * day) }
        if (recent.size < MIN_NIGHTS || before.size < MIN_NIGHTS) return emptyList()
        val out = ArrayList<TrendAlert>()
        fun check(m: TrendAlert.Metric, value: (NightRecord) -> Float?, threshold: (Float, Float) -> Boolean, higherIsWorse: Boolean) {
            val a = before.mapNotNull(value)
            val b = recent.mapNotNull(value)
            if (a.size < MIN_NIGHTS || b.size < MIN_NIGHTS) return
            val x = a.average().toFloat()
            val y = b.average().toFloat()
            if (threshold(x, y)) out += TrendAlert(m, x, y, if (higherIsWorse) y > x else y < x, a.size, b.size)
        }
        check(TrendAlert.Metric.SNORING, { it.snoreSeconds / 60f }, { x, y -> abs(y - x) >= 5f && abs(y - x) >= 0.5f * maxOf(x, 1f) }, true)
        check(TrendAlert.Metric.EFFICIENCY, { it.efficiency }, { x, y -> abs(y - x) >= 3f }, false)
        check(TrendAlert.Metric.DURATION, { it.sleepMinutes.toFloat() }, { x, y -> abs(y - x) >= 20f }, false)
        check(TrendAlert.Metric.SCORE, { it.score.toFloat() }, { x, y -> abs(y - x) >= 5f }, false)
        check(TrendAlert.Metric.LATENCY, { it.latencyMinutes.toFloat() }, { x, y -> abs(y - x) >= 5f }, true)
        check(TrendAlert.Metric.BREATHING, { it.avgBreathingRate }, { x, y -> abs(y - x) >= 1.5f }, true)
        // Bedtime drift (circular): positive = later.
        val bedBefore = StatisticsCalculator.circularMeanAndSd(before.map { StatisticsCalculator.minuteOfDay(it.startMillis, it.zoneOffsetMinutes) }).first
        val bedAfter = StatisticsCalculator.circularMeanAndSd(recent.map { StatisticsCalculator.minuteOfDay(it.startMillis, it.zoneOffsetMinutes) }).first
        if (bedBefore != null && bedAfter != null) {
            val drift = (Math.floorMod(bedAfter - bedBefore + 720, 1440) - 720).toFloat()
            if (abs(drift) >= 30f) out += TrendAlert(TrendAlert.Metric.BEDTIME, bedBefore.toFloat(), bedBefore + drift, drift > 0, before.size, recent.size)
        }
        return out.sortedByDescending { it.concern }
    }
}

// ---------------------------------------------------------------------------------------------
// Chronotype (MCTQ-style)
// ---------------------------------------------------------------------------------------------

data class Chronotype(
    /** Corrected mid-sleep on free days, minutes after midnight (may be negative = before midnight). */
    val msfScMinutes: Int,
    val type: Type,
    val freeNights: Int,
    val workNights: Int,
) {
    enum class Type { EXTREME_EARLY, MODERATE_EARLY, SLIGHT_EARLY, INTERMEDIATE, SLIGHT_LATE, MODERATE_LATE, EXTREME_LATE }
}

object ChronotypeCalculator {
    const val MIN_NIGHTS = 2

    /** Minutes relative to midnight in −6 h … +18 h. */
    private fun relMidnight(minuteOfDay: Int) = if (minuteOfDay >= 18 * 60) minuteOfDay - 1440 else minuteOfDay

    private fun midSleep(r: NightRecord): Int {
        val onset = r.sleepOnsetMillis
        val mid = onset + (r.endMillis - onset).coerceAtLeast(0) / 2
        return relMidnight(StatisticsCalculator.minuteOfDay(mid, r.zoneOffsetMinutes))
    }

    fun compute(nights: List<NightRecord>): Chronotype? {
        val free = nights.filter { it.isWeekend }
        val work = nights.filter { !it.isWeekend }
        if (free.size < MIN_NIGHTS || work.size < MIN_NIGHTS) return null
        val msf = free.map(::midSleep).average()
        val sdFree = free.map { it.sleepMinutes }.average()
        val sdWork = work.map { it.sleepMinutes }.average()
        // Oversleep on free days compensates sleep debt; correct for it (MCTQ).
        val msfSc = if (sdFree > sdWork) msf - (sdFree - sdWork) / 2 else msf
        val h = msfSc / 60.0
        val type = when {
            h < 2.0 -> Chronotype.Type.EXTREME_EARLY
            h < 3.0 -> Chronotype.Type.MODERATE_EARLY
            h < 4.0 -> Chronotype.Type.SLIGHT_EARLY
            h < 5.0 -> Chronotype.Type.INTERMEDIATE
            h < 6.0 -> Chronotype.Type.SLIGHT_LATE
            h < 7.0 -> Chronotype.Type.MODERATE_LATE
            else -> Chronotype.Type.EXTREME_LATE
        }
        return Chronotype(msfSc.toInt(), type, free.size, work.size)
    }
}

// ---------------------------------------------------------------------------------------------
// Sleep Regularity Index
// ---------------------------------------------------------------------------------------------

/** One recorded night: asleep intervals (absolute millis) and the local zone offset. */
data class SriNight(val zoneOffsetMinutes: Int, val asleep: List<Pair<Long, Long>>)

object SleepRegularityIndex {
    /**
     * Standard SRI: −100 + 200 × P(same sleep/wake state at minutes 24 h apart). Days run from
     * local noon to noon; only pairs of consecutive days that both have a recording are compared.
     * @return null without at least one such pair.
     */
    fun compute(nights: List<SriNight>): Float? {
        val days = HashMap<Long, BooleanArray>()
        for (n in nights) {
            if (n.asleep.isEmpty()) continue
            val firstStart = n.asleep.minOf { it.first }
            val dayIndex = Math.floorDiv(firstStart / 60_000L + n.zoneOffsetMinutes - 720, 1440L)
            val arr = days.getOrPut(dayIndex) { BooleanArray(1440) }
            val dayStartMin = dayIndex * 1440 + 720 - n.zoneOffsetMinutes
            for ((a, b) in n.asleep) {
                val from = (a / 60_000L - dayStartMin).coerceIn(0, 1440)
                val to = (b / 60_000L - dayStartMin).coerceIn(0, 1440)
                for (m in from until to) arr[m.toInt()] = true
            }
        }
        var same = 0L
        var total = 0L
        for ((d, a) in days) {
            val b = days[d + 1] ?: continue
            for (m in 0 until 1440) { if (a[m] == b[m]) same++; total++ }
        }
        if (total == 0L) return null
        return (-100.0 + 200.0 * same / total).toFloat()
    }
}

// ---------------------------------------------------------------------------------------------
// Phone position check
// ---------------------------------------------------------------------------------------------

data class PositionInput(
    val soundEnabled: Boolean,
    val motionEnabled: Boolean,
    /** Background level of the microphone (dBFS), null when the mic could not be read. */
    val micFloorDb: Float?,
    val breathsPerMinute: Float?,
    /** Proximity sensor reports "near" (phone covered), null without sensor. */
    val proximityNear: Boolean?,
    /** Average gravity z component (m/s²; +9.8 face up, −9.8 face down), null without sensor. */
    val gravityZ: Float?,
    /** Standard deviation of the acceleration magnitude (m/s²), null without sensor. */
    val accelSd: Float?,
    val lux: Float?,
)

data class PositionCheck(val item: Item, val status: Status) {
    enum class Item { MIC_BLOCKED, MIC_OK, ROOM_LOUD, BREATHING_FOUND, BREATHING_NOT_FOUND, COVERED, FACE_DOWN, HELD, HARD_SURFACE, ON_MATTRESS, ROOM_BRIGHT, NO_MOTION_SENSOR }
    enum class Status { OK, INFO, WARNING }
}

object PositionAssessment {
    fun assess(i: PositionInput): List<PositionCheck> = buildList {
        if (i.soundEnabled) {
            when {
                i.micFloorDb == null || i.micFloorDb < -100f -> add(PositionCheck(PositionCheck.Item.MIC_BLOCKED, PositionCheck.Status.WARNING))
                i.micFloorDb > -40f -> add(PositionCheck(PositionCheck.Item.ROOM_LOUD, PositionCheck.Status.WARNING))
                else -> add(PositionCheck(PositionCheck.Item.MIC_OK, PositionCheck.Status.OK))
            }
            if (i.micFloorDb != null && i.micFloorDb >= -100f) {
                add(
                    if (i.breathsPerMinute != null) PositionCheck(PositionCheck.Item.BREATHING_FOUND, PositionCheck.Status.OK)
                    else PositionCheck(PositionCheck.Item.BREATHING_NOT_FOUND, PositionCheck.Status.INFO),
                )
            }
        }
        if (i.proximityNear == true) add(PositionCheck(PositionCheck.Item.COVERED, PositionCheck.Status.WARNING))
        if (i.gravityZ != null && i.gravityZ < -6.5f) add(PositionCheck(PositionCheck.Item.FACE_DOWN, PositionCheck.Status.WARNING))
        when {
            i.accelSd == null -> if (i.motionEnabled) add(PositionCheck(PositionCheck.Item.NO_MOTION_SENSOR, PositionCheck.Status.INFO))
            i.accelSd > 0.3f -> add(PositionCheck(PositionCheck.Item.HELD, PositionCheck.Status.WARNING))
            i.motionEnabled && i.accelSd < 0.004f -> add(PositionCheck(PositionCheck.Item.HARD_SURFACE, PositionCheck.Status.INFO))
            i.motionEnabled -> add(PositionCheck(PositionCheck.Item.ON_MATTRESS, PositionCheck.Status.OK))
        }
        if (i.lux != null && i.lux >= 30f) add(PositionCheck(PositionCheck.Item.ROOM_BRIGHT, PositionCheck.Status.INFO))
    }

    fun worst(checks: List<PositionCheck>): PositionCheck.Status = checks.maxOfOrNull { it.status } ?: PositionCheck.Status.OK
}
