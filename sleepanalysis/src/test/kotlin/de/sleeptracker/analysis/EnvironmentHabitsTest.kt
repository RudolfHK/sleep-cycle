package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentHabitsTest {
    private val start = 1_700_000_000_000L

    @Test
    fun lightIsCarriedForwardAndPeakNoiseKept() {
        val agg = EpochAggregator(start, motionEnabled = false, soundEnabled = true, lightEnabled = true)
        agg.onLight(start + 1_000, 20f)
        agg.onLight(start + 2_000, 40f)
        agg.onAudioFrame(start + 3_000, -60f, false)
        agg.onAudioFrame(start + 4_000, -30f, true)
        agg.advanceTo(start + 3 * EPOCH_MILLIS + 1)
        val e = agg.drain()
        assertEquals(3, e.size)
        assertEquals(30f, e[0].lightLux, 1e-3f)
        assertEquals(40f, e[0].lightMaxLux, 1e-3f)
        assertEquals(-30f, e[0].soundPeakDb, 1e-3f)
        // No new reading: the last value (40 lx) carries over.
        assertTrue(e[1].hasLight)
        assertEquals(40f, e[1].lightLux, 1e-3f)
        assertEquals(-100f, e[1].soundPeakDb, 1e-3f)
    }

    @Test
    fun noLightSensorMeansNoLightData() {
        val agg = EpochAggregator(start, motionEnabled = true, soundEnabled = false, lightEnabled = false)
        agg.advanceTo(start + EPOCH_MILLIS + 1)
        val e = agg.drain().single()
        assertFalse(e.hasLight)
        assertEquals(-1f, e.lightLux)
        assertFalse(EnvironmentAnalyzer.analyze(listOf(e)).hasLight)
    }

    @Test
    fun environmentSummary() {
        // 60 epochs (30 min): quiet −70 dBFS with 10 loud epochs at −50, dark except 4 bright epochs.
        val epochs = List(60) { i ->
            Epoch(
                startMillis = start + i * EPOCH_MILLIS,
                hasSound = true,
                soundLevelDb = if (i in 20 until 30) -50f else -70f,
                soundPeakDb = if (i == 25) -20f else -45f,
                hasLight = true,
                lightLux = if (i < 4) 50f else 1f,
                lightMaxLux = if (i < 4) 80f else 1f,
            )
        }
        val s = EnvironmentAnalyzer.analyze(epochs, sleepOnsetMillis = start + 10 * EPOCH_MILLIS)
        assertTrue(s.hasNoise && s.hasLight)
        assertEquals(-20f, s.peakNoiseDb, 1e-3f)
        assertEquals(-70f, s.quietBaselineDb, 1e-3f)
        assertEquals(5, s.loudMinutes)
        assertTrue("avg ${s.avgNoiseDb}", s.avgNoiseDb > -60f && s.avgNoiseDb < -55f)
        assertEquals(80f, s.maxLux, 1e-3f)
        assertEquals(56 * 100f / 60, s.darkPercent, 1e-3f)
        assertEquals(2, s.brightMinutes)
        assertEquals(1f, s.luxAtOnset, 1e-3f)
    }

    private fun night(dayOffset: Int, bedHour: Int, wakeHour: Int, sleep: Int) = run {
        // Base: Monday 2023-11-13 00:00 UTC.
        val monday = 1_699_833_600_000L
        val dayStart = monday + dayOffset * 86_400_000L
        val bed = dayStart - (24 - bedHour) * 3_600_000L
        val wake = dayStart + wakeHour * 3_600_000L
        NightRecord(
            startMillis = bed, endMillis = wake, zoneOffsetMinutes = 0,
            sleepMinutes = sleep, timeInBedMinutes = ((wake - bed) / 60_000L).toInt(), latencyMinutes = 0,
            efficiency = 90f, score = 80, awakeMinutes = 0, lightMinutes = 0, deepMinutes = 0, remMinutes = 0,
            interruptions = 0, snoreEvents = 0, snoreSeconds = 0, coughEvents = 0, talkEvents = 0, noiseEvents = 0,
            avgNoiseDb = -60f, avgLux = 2f, darkPercent = 90f,
        )
    }

    @Test
    fun weekdayWeekendSplitAndSocialJetLag() {
        // Mon–Fri mornings: 23:00 → 07:00. Sat/Sun mornings: 01:00 → 10:00.
        val nights = (0..4).map { night(it, 23, 7, 450) } + (5..6).map { night(it, 25, 10, 510) }
        assertTrue(nights[5].isWeekend && nights[6].isWeekend && !nights[4].isWeekend)
        val st = StatisticsCalculator.compute(nights, 480)
        assertEquals(5, st.weekdays!!.nights)
        assertEquals(2, st.weekends!!.nights)
        assertEquals(23 * 60, st.weekdays!!.avgBedtimeMinuteOfDay)
        assertEquals(60, st.weekends!!.avgBedtimeMinuteOfDay)
        assertEquals(10 * 60, st.weekends!!.avgWakeMinuteOfDay)
        // Mid-sleep 03:00 vs 05:30 → 150 min.
        assertEquals(150, st.socialJetLagMinutes)
        assertEquals(-60f, st.avgNoiseDb!!, 1e-3f)
        assertEquals(2f, st.avgLux!!, 1e-3f)
        assertNotNull(st.avgSleepOnsetMinuteOfDay)
        assertTrue(st.avgTimeInBedMinutes > 480f)
    }

    @Test
    fun socialJetLagNeedsBothGroups() {
        assertNull(StatisticsCalculator.compute((0..4).map { night(it, 23, 7, 450) }, 480).socialJetLagMinutes)
    }
}

class ImportedPhasesTest {
    @Test
    fun phaseAveragesIgnoreNightsWithoutPhases() {
        fun rec(deep: Int, has: Boolean) = NightRecord(
            0L, 8 * 3_600_000L, 0, 420, 480, 10, 87f, 70, 60, 230, deep, 100, 0, 0, 0, 0, 0, 0, hasPhases = has,
        )
        val st = StatisticsCalculator.compute(listOf(rec(90, true), rec(0, false)), 480)
        assertEquals(90f, st.avgDeepMinutes, 1e-3f)
        assertEquals(420f, st.avgSleepMinutes, 1e-3f)
        assertEquals(2, st.nights)
    }
}
