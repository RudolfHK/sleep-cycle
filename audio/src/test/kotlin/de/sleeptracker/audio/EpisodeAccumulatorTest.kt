package de.sleeptracker.audio

import de.sleeptracker.core.SoundEvent
import de.sleeptracker.core.SoundEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EpisodeAccumulatorTest {
    private fun ev(t: Long, type: SoundEventType, dur: Long = 1500, db: Float = 15f, conf: Float = 0.8f) = SoundEvent(t, dur, type, conf, db)

    private fun runAll(acc: EpisodeAccumulator, events: List<SoundEvent>): List<EpisodeRecord> {
        val out = ArrayList<EpisodeRecord>()
        for (e in events.sortedBy { it.startMillis }) out += acc.add(e, null)
        out += acc.flushAll()
        return out
    }

    @Test
    fun hundredsOfSnoresBecomeAFewEpisodes() {
        // Three snoring periods of 100 snores each (every 4 s), hours apart.
        val events = (0 until 3).flatMap { p -> (0 until 100).map { ev(p * 3_600_000L + it * 4_000L, SoundEventType.SNORE) } }
        val out = runAll(EpisodeAccumulator(), events)
        assertEquals(3, out.size)
        assertTrue(out.all { it.count == 100 })
        assertEquals(396_000L + 1_500L, out[0].event.durationMillis)
    }

    @Test
    fun faintNoiseRejectedLoudKept() {
        val rnd = Random(1)
        // 400 faint rustles (8–15 dB) spread over the night, one loud door bang, one real cough.
        val faint = (0 until 400).map { ev(it * 70_000L + rnd.nextLong(0, 5_000), SoundEventType.NOISE_HOUSEHOLD, 600, 8f + rnd.nextFloat() * 7f) }
        val door = ev(10_000_000L, SoundEventType.NOISE_DOOR, 400, 28f)
        val cough = ev(20_000_000L + 15_000, SoundEventType.COUGH, 350, 24f)
        val out = runAll(EpisodeAccumulator(), faint + door + cough)
        assertEquals(out.joinToString { "${it.event.type}/${it.event.intensityDb}" }, 2, out.size)
        assertTrue(out.any { it.event.type == SoundEventType.NOISE_DOOR })
        assertTrue(out.any { it.event.type == SoundEventType.COUGH })
    }

    @Test
    fun isolatedSnoreAndShortTalkRejected() {
        val out = runAll(EpisodeAccumulator(), listOf(ev(0, SoundEventType.SNORE), ev(500_000, SoundEventType.TALK, 700, 20f)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun sensitivityShiftsThresholds() {
        val e = listOf(ev(0, SoundEventType.NOISE_DOOR, 500, 19f))
        assertTrue(runAll(EpisodeAccumulator(EventRules(0.5f)), e).isEmpty())
        assertEquals(1, runAll(EpisodeAccumulator(EventRules(1f)), e).size)
    }

    @Test
    fun noisyNightStaysBelowForty() {
        val rnd = Random(5)
        val events = ArrayList<SoundEvent>()
        // 600 detections: snoring bouts, random household noise, a few loud bangs and coughs.
        repeat(5) { b -> repeat(80) { events += ev(b * 5_000_000L + it * 3_500L, SoundEventType.SNORE, 1_200, 10f + rnd.nextFloat() * 10f) } }
        repeat(180) { events += ev(rnd.nextLong(0, 28_800_000L), SoundEventType.NOISE_OTHER, rnd.nextLong(300, 2_000), 9f + rnd.nextFloat() * 10f) }
        repeat(8) { events += ev(rnd.nextLong(0, 28_800_000L), SoundEventType.NOISE_DOOR, 500, 26f) }
        repeat(12) { events += ev(rnd.nextLong(0, 28_800_000L), SoundEventType.COUGH, 300, 15f + rnd.nextFloat() * 10f) }
        val out = runAll(EpisodeAccumulator(), events)
        assertTrue("stored ${out.size}", out.size in 5..40)
    }

    @Test
    fun consolidationOfStoredEvents() {
        val stored = (0 until 50).map { ev(it * 4_000L, SoundEventType.SNORE) } + listOf(ev(1_000_000L, SoundEventType.NOISE_OTHER, 500, 10f))
        val out = EpisodeAccumulator.consolidate(stored)
        assertEquals(1, out.size)
        assertEquals(50, out[0].first.count)
        assertEquals(50, out[0].second.size)
    }
}
