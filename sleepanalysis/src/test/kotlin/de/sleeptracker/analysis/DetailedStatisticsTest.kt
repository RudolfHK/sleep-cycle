package de.sleeptracker.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailedStatisticsTest {
    // 2023-11-13 was a Monday.
    private val monday = 1_699_833_600_000L

    private fun night(day: Int, sleep: Int, score: Int) = NightRecord(
        startMillis = monday + day * 86_400_000L - 2 * 3_600_000L, endMillis = monday + day * 86_400_000L + 7 * 3_600_000L,
        zoneOffsetMinutes = 0, sleepMinutes = sleep, timeInBedMinutes = 540, latencyMinutes = 10, efficiency = 85f, score = score,
        awakeMinutes = 0, lightMinutes = 0, deepMinutes = 0, remMinutes = 0, interruptions = 0, snoreEvents = 0,
        snoreSeconds = 0, coughEvents = 0, talkEvents = 0, noiseEvents = 0,
    )

    @Test
    fun goalStreakWeekdayHistogramBestWorst() {
        val sleeps = listOf(490, 500, 300, 480, 485, 490, 600)
        val nights = sleeps.mapIndexed { i, s -> night(i, s, 50 + i) }
        val d = DetailedStatisticsCalculator.compute(nights, 480)
        assertEquals(6, d.goalMetNights)
        assertEquals(4, d.longestGoalStreak)
        assertEquals(490f, d.sleepByWeekday[0]!!, 1e-3f) // Monday
        assertEquals(600f, d.sleepByWeekday[6]!!, 1e-3f) // Sunday
        assertEquals(listOf(0, 1, 0, 0, 5, 1), d.durationHistogram)
        assertEquals(6, d.bestIndex)
        assertEquals(0, d.worstIndex)
    }

    @Test
    fun emptyAndRolling() {
        val d = DetailedStatisticsCalculator.compute(emptyList(), 480)
        assertEquals(0f, d.goalMetPercent)
        assertNull(d.bestIndex)
        assertNull(d.sleepByWeekday[3])
        assertEquals(listOf(2f, 3f, 4f, 6f), DetailedStatisticsCalculator.rollingAverage(listOf(2f, 4f, 6f, 8f), 3))
    }
}
