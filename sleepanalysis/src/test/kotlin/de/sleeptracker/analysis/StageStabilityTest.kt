package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SleepStage
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StageStabilityTest {
    private val start = 1_700_000_000_000L

    private fun runs(stages: List<SleepStage>): List<Pair<SleepStage, Int>> {
        val out = ArrayList<Pair<SleepStage, Int>>()
        var i = 0
        while (i < stages.size) {
            var j = i
            while (j < stages.size && stages[j] == stages[i]) j++
            out += stages[i] to (j - i); i = j
        }
        return out
    }

    /** Sensitive sensor: random single twitches all night, 15 min awake at start and end. */
    private fun noisyNight(seed: Int): List<Epoch> {
        val rnd = Random(seed)
        return List(8 * 120) { i ->
            val min = i / 2.0
            val c = when {
                min < 15 || min > 465 -> 3 + rnd.nextInt(4)
                rnd.nextDouble() < 0.18 -> 1 + rnd.nextInt(3) // frequent small twitches
                else -> 0
            }
            Epoch(start + i * EPOCH_MILLIS, motionCount = c, hasMotion = true)
        }
    }

    @Test
    fun phasesAreStableAndRespectMinimumLengths() {
        for (seed in 1..5) {
            val staged = SleepStager().stage(noisyNight(seed)).epochs.map { it.stage }
            val r = runs(staged)
            assertTrue("seed $seed: ${r.size} runs", r.size < 40)
            // Inner runs (between first and last sleep) respect the minimums.
            val inner = r.drop(1).dropLast(1)
            val min = mapOf(SleepStage.DEEP to 20, SleepStage.REM to 10, SleepStage.LIGHT to 6, SleepStage.AWAKE to 4)
            assertTrue("seed $seed short run: $inner", inner.all { (st, len) -> len >= min.getValue(st) })
            val deep = staged.indices.filter { staged[it] == SleepStage.DEEP }
            assertTrue("seed $seed deep ${deep.size}", deep.size in 60..300)
            assertTrue("deep early", deep.average() < staged.size / 2.0)
        }
    }

    @Test
    fun constantBreathingSoundIsNotWake() {
        // Sound-only tracking: steady 20 % active frames (breathing), occasional rustle epochs.
        val rnd = Random(3)
        val epochs = List(7 * 120) { i ->
            val rustle = rnd.nextDouble() < 0.03
            Epoch(start + i * EPOCH_MILLIS, soundActivity = if (rustle) 0.6f else 0.18f + rnd.nextFloat() * 0.04f, soundLevelDb = -60f, hasSound = true)
        }
        val s = SessionAnalyzer().analyze(epochs, start, start + epochs.size * EPOCH_MILLIS)
        assertTrue("efficiency ${s.efficiency}", s.efficiency > 80f)
        assertTrue("interruptions ${s.interruptions.size}", s.interruptions.size <= 2)
    }
}
