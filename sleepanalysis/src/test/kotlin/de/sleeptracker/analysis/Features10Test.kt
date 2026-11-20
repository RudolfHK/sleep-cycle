package de.sleeptracker.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Features10Test {
    private val monday = 1_699_833_600_000L // 2023-11-13 00:00 UTC, Monday
    private val h = 3_600_000L
    private val day = 24 * h

    private fun rec(
        d: Int, bedHour: Double = 23.0, wakeHour: Double = 7.0, sleep: Int = 420, score: Int = 70,
        latency: Int = 15, eff: Float = 88f, deep: Int = 90, snore: Int = 0, breathing: Float? = null,
    ): NightRecord {
        val bed = monday + d * day + ((bedHour - 24) * h).toLong()
        return NightRecord(
            startMillis = bed, endMillis = monday + d * day + (wakeHour * h).toLong(), zoneOffsetMinutes = 0,
            sleepMinutes = sleep, timeInBedMinutes = 480, latencyMinutes = latency, efficiency = eff, score = score,
            awakeMinutes = 60, lightMinutes = 200, deepMinutes = deep, remMinutes = 100, interruptions = 0,
            snoreEvents = 0, snoreSeconds = snore, coughEvents = 0, talkEvents = 0, noiseEvents = 0, avgBreathingRate = breathing,
        )
    }

    @Test
    fun breathingSummaryAndFlags() {
        val normal = List(240) { 14f + (it % 3) * 0.5f } + List(100) { Float.NaN }
        val s = BreathingAnalyzer.summarize(normal, listOf(14f, 14.5f, 15f))!!
        assertEquals(14.5f, s.averageRate, 0.3f)
        assertEquals(120, s.minutesMeasured)
        assertTrue(s.flags.isEmpty())
        val fast = BreathingAnalyzer.summarize(List(100) { 26f }, listOf(14f, 15f, 14f))!!
        assertTrue(BreathingSummary.Flag.HIGH in fast.flags && BreathingSummary.Flag.DIFFERENT_FROM_USUAL in fast.flags)
        val irregular = BreathingAnalyzer.summarize(List(100) { if (it % 2 == 0) 9f else 19f })!!
        assertTrue(BreathingSummary.Flag.IRREGULAR in irregular.flags)
        assertNull(BreathingAnalyzer.summarize(List(10) { 14f }))
    }

    @Test
    fun tagImpact() {
        val nights = (0 until 6).map { d ->
            val coffee = d % 2 == 0
            rec(d, score = if (coffee) 60 else 80, latency = if (coffee) 30 else 10, deep = if (coffee) 60 else 100) to
                (if (coffee) setOf("coffee") else setOf("exercise"))
        }
        val impacts = TagImpactCalculator.compute(nights, listOf("coffee", "exercise", "alcohol"))
        val coffee = impacts.first { it.tag == "coffee" }
        assertEquals(-20f, coffee.scoreDiff, 1e-3f)
        assertEquals(20f, coffee.latencyDiff, 1e-3f)
        assertEquals(-40f, coffee.deepDiff!!, 1e-3f)
        assertTrue(impacts.none { it.tag == "alcohol" })
    }

    @Test
    fun trendAlerts() {
        val now = monday + 60 * day + 8 * h
        val before = (1..29).map { rec(it, bedHour = 23.0, snore = 60, eff = 90f) }
        val after = (31..59).map { rec(it, bedHour = 24.0, snore = 900, eff = 84f) }
        val alerts = TrendAlertCalculator.compute(before + after, now)
        val metrics = alerts.map { it.metric }.toSet()
        assertTrue(TrendAlert.Metric.SNORING in metrics)
        assertTrue(TrendAlert.Metric.BEDTIME in metrics)
        assertTrue(TrendAlert.Metric.EFFICIENCY in metrics)
        assertTrue(alerts.first { it.metric == TrendAlert.Metric.BEDTIME }.change in 55f..65f)
        assertTrue(alerts.all { it.concern })
        assertTrue(TrendAlertCalculator.compute(after.take(3), now).isEmpty())
    }

    @Test
    fun chronotype() {
        // Weekdays 23:00–07:00 (mid 03:00), weekends 01:00–10:00 (mid 05:30 incl. 15 min latency).
        val nights = (0 until 14).map { d ->
            val dow = d % 7 // 0 = Monday morning
            if (dow == 5 || dow == 6) rec(d, bedHour = 25.0, wakeHour = 10.0, sleep = 510) else rec(d, sleep = 450)
        }
        val c = ChronotypeCalculator.compute(nights)!!
        // MSF ≈ 05:37, corrected by (510 − 450)/2 = 30 min → ≈ 05:07 → slightly late.
        assertEquals(Chronotype.Type.SLIGHT_LATE, c.type)
        assertTrue(c.msfScMinutes in 300..320)
        assertNull(ChronotypeCalculator.compute(nights.take(4)))
    }

    @Test
    fun sleepRegularityIndex() {
        fun night(d: Int, startH: Double, endH: Double) = SriNight(0, listOf((monday + d * day + (startH * h).toLong()) to (monday + d * day + (endH * h).toLong())))
        val identical = (0 until 5).map { night(it, -1.0, 7.0) }
        assertEquals(100f, SleepRegularityIndex.compute(identical)!!, 1e-3f)
        val shifted = listOf(night(0, -1.0, 7.0), night(1, 3.0, 11.0))
        assertEquals(33.33f, SleepRegularityIndex.compute(shifted)!!, 0.1f)
        assertNull(SleepRegularityIndex.compute(listOf(night(0, -1.0, 7.0), night(3, -1.0, 7.0))))
    }

    @Test
    fun positionAssessment() {
        val good = PositionAssessment.assess(PositionInput(true, true, -70f, 14f, false, 9.7f, 0.01f, 1f))
        assertEquals(PositionCheck.Status.OK, PositionAssessment.worst(good))
        val bad = PositionAssessment.assess(PositionInput(true, true, -120f, null, true, -9.7f, 0.8f, 50f)).map { it.item }
        assertTrue(PositionCheck.Item.MIC_BLOCKED in bad && PositionCheck.Item.COVERED in bad && PositionCheck.Item.FACE_DOWN in bad && PositionCheck.Item.HELD in bad)
        val table = PositionAssessment.assess(PositionInput(true, true, -30f, null, null, 9.8f, 0.001f, null)).map { it.item }
        assertTrue(PositionCheck.Item.ROOM_LOUD in table && PositionCheck.Item.HARD_SURFACE in table)
        assertNotNull(table)
    }
}
