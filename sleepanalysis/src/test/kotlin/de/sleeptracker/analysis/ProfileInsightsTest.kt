package de.sleeptracker.analysis

import de.sleeptracker.core.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInsightsTest {
    // 2023-11-13 00:00 UTC (Monday).
    private val monday = 1_699_833_600_000L
    private val h = 3_600_000L

    private fun night(day: Int): ProfileNight {
        val bed = monday + day * 24 * h - 1 * h // 23:00
        return ProfileNight(
            zoneOffsetMinutes = 0,
            runs = listOf(
                Triple(bed, h, SleepStage.LIGHT),                // 23–00
                Triple(bed + h, 2 * h, SleepStage.DEEP),          // 00–02
                Triple(bed + 3 * h, 4 * h, SleepStage.REM),       // 02–06
                Triple(bed + 7 * h, h / 2, SleepStage.AWAKE),     // 06–06:30
            ),
            samples = (0 until 15).map { Triple(bed + it * 30 * 60_000L, if (it < 4) -50f else -70f, if (it == 0) 20f else Float.NaN) },
        )
    }

    @Test
    fun mainSleepFilter() {
        val h = 3_600_000L
        val day = monday + 2 * 24 * h
        assertTrue(NightProfileCalculator.isMainSleep(day - h, 480, 0))        // 23:00, 8 h
        assertTrue(NightProfileCalculator.isMainSleep(day + 2 * h, 300, 0))    // 02:00
        assertTrue(!NightProfileCalculator.isMainSleep(day + 15 * h, 240, 0))  // 15:00 nap/test
        assertTrue(!NightProfileCalculator.isMainSleep(day - h, 60, 0))        // too short
        assertTrue(NightProfileCalculator.isMainSleep(day + 21 * h, 400, 60))  // 22:00 UTC = 23:00 local
    }

    @Test
    fun strayEdgeSlotsAreTrimmedAndSmoothingKeepsSums() {
        // 9 normal nights plus one that starts 2 h earlier: the extra early slots are cut.
        val nights = (0 until 9).map(::night) + night(9).let { n ->
            n.copy(runs = listOf(Triple(n.runs.first().first - 2 * h, 2 * h, SleepStage.LIGHT)) + n.runs)
        }
        val p = NightProfileCalculator.compute(nights)
        assertEquals(300, p.slots.first().startMinute)
        assertTrue(p.slots.all { s -> s.nightsInBed == 0 || kotlin.math.abs(s.share.values.sum() - 1f) < 1e-4f })
        assertTrue(p.slots.all { it.coverage in 0f..1f })
    }

    @Test
    fun splineSampling() {
        val xs = floatArrayOf(0f, 1f, 2f)
        val ys = floatArrayOf(0f, 1f, 0f)
        val at = floatArrayOf(-1f, 0f, 0.5f, 1f, 1.5f, 2f, 3f)
        val out = MonotoneSpline.sample(xs, ys, at)
        assertEquals(0f, out[0], 1e-6f); assertEquals(1f, out[3], 1e-6f); assertEquals(0f, out[6], 1e-6f)
        for (k in at.indices) assertEquals(MonotoneSpline.evaluate(xs, ys, at[k]), out[k], 1e-5f)
    }

    @Test
    fun profileSharesNoiseAndTrim() {
        val p = NightProfileCalculator.compute((0 until 4).map(::night), smooth = 0)
        assertEquals(4, p.totalNights)
        // First slot 23:00 (300 min after 18:00), last slot 06:15.
        assertEquals(300, p.slots.first().startMinute)
        assertEquals(12 * 60 + 15, p.slots.last().startMinute)
        val oneAm = p.slots.first { it.startMinute == 7 * 60 }
        assertEquals(4, oneAm.nightsInBed)
        assertEquals(1f, oneAm.share[SleepStage.DEEP]!!, 1e-6f)
        val fourAm = p.slots.first { it.startMinute == 10 * 60 }
        assertEquals(1f, fourAm.share[SleepStage.REM]!!, 1e-6f)
        assertEquals(-50f, p.slots.first().noiseDb!!, 1e-3f)
        assertEquals(20f, p.slots.first().lux!!, 1e-3f)
        assertNull(p.slots.first { it.startMinute == 9 * 60 }.lux)
        assertTrue(p.slots.all { s -> s.nightsInBed == 0 || kotlin.math.abs(s.share.values.sum() - 1f) < 1e-5f })
    }

    private fun record(day: Int, score: Int, noise: Float, snore: Int, bedHour: Int = 23) = NightRecord(
        startMillis = monday + day * 24 * h - (24 - bedHour) * h, endMillis = monday + day * 24 * h + 7 * h,
        zoneOffsetMinutes = 0, sleepMinutes = 420, timeInBedMinutes = 480, latencyMinutes = 10, efficiency = score.toFloat(),
        score = score, awakeMinutes = 0, lightMinutes = 0, deepMinutes = 0, remMinutes = 0, interruptions = 0,
        snoreEvents = 0, snoreSeconds = snore, coughEvents = 0, talkEvents = 0, noiseEvents = 0, avgNoiseDb = noise,
    )

    @Test
    fun insightsSplitAtMedian() {
        // Quiet nights score 80, loud nights 60; snoring on the loud ones.
        val nights = (0 until 8).map { d -> if (d % 2 == 0) record(d, 80, -70f, 0) else record(d, 60, -50f, 300) }
        val ins = InsightsCalculator.compute(nights)
        val noise = ins.first { it.factor == Insight.Factor.NOISE }
        assertEquals(4, noise.lowNights)
        assertEquals(80f, noise.lowScore, 1e-3f)
        assertEquals(60f, noise.highScore, 1e-3f)
        assertEquals(-20f, noise.scoreDifference, 1e-3f)
        val snore = ins.first { it.factor == Insight.Factor.SNORING }
        assertEquals(80f, snore.lowScore, 1e-3f)
        assertTrue(ins.none { it.factor == Insight.Factor.LIGHT }) // no light data
        assertTrue(InsightsCalculator.compute(nights.take(5)).isEmpty())
    }
}
