package de.sleeptracker.analysis

/** Additional, more detailed period figures. */
data class DetailedStatistics(
    /** Nights reaching the sleep goal. */
    val goalMetNights: Int,
    val goalMetPercent: Float,
    /** Longest run of consecutive nights (in recorded order) reaching the goal. */
    val longestGoalStreak: Int,
    /** Average sleep in minutes per ISO weekday of the morning (index 0 = Monday), null = no nights. */
    val sleepByWeekday: List<Float?>,
    /** Counts per sleep-duration bucket: < 5 h, 5–6, 6–7, 7–8, 8–9, ≥ 9 h. */
    val durationHistogram: List<Int>,
    /** Index (into the input list) of the best/worst scored night. */
    val bestIndex: Int?,
    val worstIndex: Int?,
)

object DetailedStatisticsCalculator {
    val HISTOGRAM_EDGES_HOURS = listOf(5, 6, 7, 8, 9)

    /** @param nights in chronological order. */
    fun compute(nights: List<NightRecord>, goalMinutes: Int): DetailedStatistics {
        val met = nights.map { it.sleepMinutes >= goalMinutes }
        var streak = 0
        var best = 0
        for (m in met) { streak = if (m) streak + 1 else 0; if (streak > best) best = streak }
        val byDay = (1..7).map { d ->
            val list = nights.filter { isoDayOfWeek(it) == d }
            if (list.isEmpty()) null else list.sumOf { it.sleepMinutes }.toFloat() / list.size
        }
        val hist = IntArray(HISTOGRAM_EDGES_HOURS.size + 1)
        for (n in nights) {
            val h = n.sleepMinutes / 60f
            hist[HISTOGRAM_EDGES_HOURS.indexOfFirst { h < it }.let { if (it < 0) HISTOGRAM_EDGES_HOURS.size else it }]++
        }
        return DetailedStatistics(
            goalMetNights = met.count { it },
            goalMetPercent = if (nights.isEmpty()) 0f else met.count { it } * 100f / nights.size,
            longestGoalStreak = best,
            sleepByWeekday = byDay,
            durationHistogram = hist.toList(),
            bestIndex = nights.indices.maxByOrNull { nights[it].score },
            worstIndex = nights.indices.minByOrNull { nights[it].score },
        )
    }

    /** ISO day of week (1 = Monday … 7 = Sunday) of the morning the night ended. */
    fun isoDayOfWeek(r: NightRecord): Int {
        val days = Math.floorDiv(r.endMillis / 60_000L + r.zoneOffsetMinutes, 1440L)
        return (Math.floorMod(days + 3, 7L) + 1).toInt()
    }

    /** Trailing rolling mean over [window] values (shorter at the start). */
    fun rollingAverage(values: List<Float>, window: Int): List<Float> {
        val out = ArrayList<Float>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= window) sum -= values[i - window]
            out += (sum / minOf(i + 1, window)).toFloat()
        }
        return out
    }
}
