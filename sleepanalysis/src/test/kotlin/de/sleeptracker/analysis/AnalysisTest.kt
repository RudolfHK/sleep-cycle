package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SnoozeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.PI
import kotlin.random.Random

class AnalysisTest {
    private val start = 1_700_000_000_000L - (1_700_000_000_000L % 86_400_000L) + 22 * 3_600_000L // 22:00 UTC

    /**
     * Synthetic night: 20 min awake, then sleep with movement bursts at the end of each
     * 90-minute cycle, a 10-minute wake period at 03:00, and 10 min awake at the end.
     */
    private fun night(hours: Double = 8.0, seed: Int = 1): List<Epoch> {
        val rnd = Random(seed)
        val n = (hours * 120).toInt()
        return List(n) { i ->
            val min = i * 0.5
            val count = when {
                min < 20 -> 3 + rnd.nextInt(5)
                min >= 300 && min < 310 -> 4 + rnd.nextInt(4)
                min > hours * 60 - 10 -> 4 + rnd.nextInt(4)
                else -> {
                    val phase = ((min - 20) % 90) / 90.0
                    val p = 0.03 + 0.25 * (0.5 - 0.5 * cos(2 * PI * phase)).let { it * it * it }
                    if (rnd.nextDouble() < p) 1 + rnd.nextInt(2) else 0
                }
            }
            Epoch(start + i * EPOCH_MILLIS, motionCount = count, motionIntensity = count * 0.1f, hasMotion = true)
        }
    }

    @Test
    fun stagingProducesPlausibleNight() {
        val epochs = night()
        val s = SessionAnalyzer().analyze(epochs, start, start + epochs.size * EPOCH_MILLIS)
        assertEquals(480, s.timeInBedMinutes)
        assertTrue("latency ${s.sleepLatencyMinutes}", s.sleepLatencyMinutes in 18..30)
        assertTrue("efficiency ${s.efficiency}", s.efficiency in 85f..97f)
        assertTrue("interruptions ${s.interruptions.size}", s.interruptions.size >= 1)
        assertTrue("deep ${s.deepMinutes}", s.deepMinutes in 40..140)
        assertTrue("rem ${s.remMinutes}", s.remMinutes in 50..150)
        assertTrue(s.lightMinutes > s.deepMinutes)
        // Deep sleep concentrates in the first half, REM in the second half.
        val half = start + 4 * 3_600_000L
        val deepFirst = s.stages.count { it.stage == SleepStage.DEEP && it.startMillis < half }
        val deepSecond = s.stages.count { it.stage == SleepStage.DEEP && it.startMillis >= half }
        val remFirst = s.stages.count { it.stage == SleepStage.REM && it.startMillis < half }
        val remSecond = s.stages.count { it.stage == SleepStage.REM && it.startMillis >= half }
        assertTrue(deepFirst > deepSecond)
        assertTrue(remSecond > remFirst)
        assertTrue(s.stages.all { it.confidence in 0f..1f })
    }

    @Test
    fun noSleepDetectedWhenRestless() {
        val epochs = List(240) { Epoch(start + it * EPOCH_MILLIS, motionCount = 6, hasMotion = true) }
        val s = SessionAnalyzer().analyze(epochs, start, start + 240 * EPOCH_MILLIS)
        assertEquals(0, s.sleepMinutes)
        assertNull(s.sleepOnsetMillis)
        assertEquals(1, SleepScoreCalculator().score(s, 480).score.coerceAtMost(30).let { if (it < 30) 1 else it })
    }

    @Test
    fun scoreRewardsGoodNights() {
        val good = SessionAnalyzer().analyze(night(8.0), start, start + 480 * 60_000L)
        val short = SessionAnalyzer().analyze(night(4.0), start, start + 240 * 60_000L)
        val calc = SleepScoreCalculator()
        val g = calc.score(good, 480).score
        val sh = calc.score(short, 480).score
        assertTrue("good $g short $sh", g > sh)
        assertTrue(g in 1..100 && sh in 1..100)
    }

    @Test
    fun smartAlarmWakesOnMovementInWindow() {
        val epochs = night(8.0).toMutableList()
        val deadline = start + 8 * 3_600_000L
        val windowStart = deadline - 30 * 60_000L
        // Quiet sleep in the window except a movement period 15 min before the deadline.
        for (i in epochs.indices) {
            val t = epochs[i].startMillis
            if (t >= windowStart - 10 * 60_000L) {
                val restless = t in (deadline - 16 * 60_000L) until (deadline - 13 * 60_000L)
                epochs[i] = epochs[i].copy(motionCount = if (restless) 3 else 0)
            }
        }
        val decider = SmartAlarmDecider(windowStart, deadline)
        var woke: Long? = null
        for (i in epochs.indices) {
            val now = epochs[i].startMillis + EPOCH_MILLIS
            val d = decider.evaluate(now, epochs.subList(0, i + 1))
            if (d != SmartAlarmDecider.Decision.WAIT) { woke = now; break }
        }
        assertTrue("woke at $woke", woke != null && woke in windowStart until deadline)
        assertTrue(woke!! >= deadline - 16 * 60_000L)
    }

    @Test
    fun smartAlarmFallsBackToDeadline() {
        val deadline = start + 8 * 3_600_000L
        val decider = SmartAlarmDecider(deadline - 30 * 60_000L, deadline)
        val still = List(960) { Epoch(start + it * EPOCH_MILLIS, hasMotion = true) }
        assertEquals(SmartAlarmDecider.Decision.WAIT, decider.evaluate(deadline - 60_000L, still))
        assertEquals(SmartAlarmDecider.Decision.WAKE_DEADLINE, decider.evaluate(deadline, still))
        // No signal at all never wakes early but the deadline still fires.
        val empty = SmartAlarmDecider(deadline - 30 * 60_000L, deadline)
        assertEquals(SmartAlarmDecider.Decision.WAIT, empty.evaluate(deadline - 60_000L, emptyList()))
        assertEquals(SmartAlarmDecider.Decision.WAKE_DEADLINE, empty.evaluate(deadline + 1, emptyList()))
    }

    @Test
    fun intelligentSnoozeShrinksAndRespectsDeadline() {
        val now = 0L
        val deadline = 60 * 60_000L
        val seq = (0..3).map { SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.INTELLIGENT, it, 9, 3, now, deadline) }
        assertEquals(listOf(10, 7, 5, 3), seq)
        assertEquals(4, SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.INTELLIGENT, 0, 9, 3, now, 4 * 60_000L))
        assertNull(SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.INTELLIGENT, 0, 9, 3, now, 30_000L))
        assertEquals(9, SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.FIXED, 2, 9, 3, now, deadline))
        assertNull(SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.FIXED, 3, 9, 3, now, deadline))
        assertNull(SnoozeCalculator.nextSnoozeMinutes(SnoozeMode.OFF, 0, 9, 3, now, deadline))
    }

    @Test
    fun circularMeanHandlesMidnight() {
        val (mean, sd) = StatisticsCalculator.circularMeanAndSd(listOf(23 * 60 + 30, 30))
        assertEquals(0, mean)
        assertTrue("sd $sd", sd in 25f..40f)
    }

    @Test
    fun statisticsAndSleepDebt() {
        val nights = (0 until 7).map { d ->
            NightRecord(
                startMillis = start + d * 86_400_000L, endMillis = start + d * 86_400_000L + 8 * 3_600_000L,
                zoneOffsetMinutes = 0, sleepMinutes = 420, timeInBedMinutes = 480, latencyMinutes = 15,
                efficiency = 87.5f, score = 70 + d, awakeMinutes = 60, lightMinutes = 230, deepMinutes = 90,
                remMinutes = 100, interruptions = 1, snoreEvents = d, snoreSeconds = 60, coughEvents = 1,
                talkEvents = 0, noiseEvents = 2,
            )
        }
        val st = StatisticsCalculator.compute(nights, goalMinutes = 480)
        assertEquals(7, st.nights)
        assertEquals(420f, st.avgSleepMinutes, 0.01f)
        assertEquals(7 * 60, st.sleepDebtMinutes)
        assertEquals(22 * 60, st.avgBedtimeMinuteOfDay)
        assertEquals(6 * 60, st.avgWakeMinuteOfDay)
        assertTrue(st.regularity > 99f)
        assertEquals(76, st.maxScore)
    }

    @Test
    fun motionProcessorDetectsBumpsNotNoise() {
        val mp = MotionProcessor(0.5f)
        val rnd = Random(3)
        var events = 0
        var t = 0L
        repeat(20 * 60) { // 60 s at 20 Hz of sensor noise
            if (mp.onSample(t, 0f, 0f, 9.81f + rnd.nextFloat() * 0.01f) != null) events++
            t += 50
        }
        assertEquals(0, events)
        repeat(3) {
            repeat(4) { mp.onSample(t, 0.8f, 0.3f, 10.6f)?.let { events++ }; t += 50 }
            repeat(40) { mp.onSample(t, 0f, 0f, 9.81f + rnd.nextFloat() * 0.01f)?.let { events++ }; t += 50 }
        }
        assertEquals(3, events)
    }

    @Test
    fun aggregatorBuildsEpochs() {
        val agg = EpochAggregator(start, motionEnabled = true, soundEnabled = true)
        agg.onMotionSample(start + 1000)
        agg.onMotionEvent(start + 2000, 0.5f)
        agg.onAudioFrame(start + 3000, -40f, true)
        agg.onAudioFrame(start + 4000, -40f, false)
        agg.onSoundEvent(start + 5000, isSnore = true, durationMillis = 2000)
        agg.advanceTo(start + 95_000)
        val e = agg.drain()
        assertEquals(3, e.size)
        assertEquals(1, e[0].motionCount)
        assertEquals(0.5f, e[0].soundActivity, 1e-6f)
        assertEquals(-40f, e[0].soundLevelDb, 0.01f)
        assertEquals(2f, e[0].snoreSeconds, 1e-6f)
        assertTrue(!e[1].hasSignal)
    }
}
