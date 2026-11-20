package de.sleeptracker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class BreathingRateTest {
    private val frameSec = 1024f / 16_000f

    private fun feed(bpm: Float, amplitudeDb: Float, seconds: Float, noiseDb: Float, seed: Int = 1): BreathingRateEstimator {
        val e = BreathingRateEstimator(frameSec)
        val rnd = Random(seed)
        val frames = (seconds / frameSec).toInt()
        for (i in 0 until frames) {
            val t = i * frameSec
            // Breath sound: a bump in level during part of each breath cycle.
            val phase = (t * bpm / 60f) % 1f
            val breath = if (phase < 0.4f) amplitudeDb * sin(PI * phase / 0.4).toFloat() else 0f
            e.add(breath + rnd.nextFloat() * noiseDb)
        }
        return e
    }

    @Test
    fun detectsTypicalBreathingRates() {
        for (bpm in listOf(10f, 14f, 18f, 24f)) {
            val est = feed(bpm, 6f, 60f, 2f).estimate()
            assertNotNull("bpm $bpm", est)
            assertEquals("bpm $bpm", bpm, est!!.breathsPerMinute, 1f)
        }
    }

    @Test
    fun snoringEveryFewSeconds() {
        val est = feed(17f, 15f, 60f, 3f).estimate()
        assertEquals(17f, est!!.breathsPerMinute, 1f)
    }

    @Test
    fun noRhythmGivesNull() {
        val e = BreathingRateEstimator(frameSec)
        val rnd = Random(4)
        repeat((60f / frameSec).toInt()) { e.add(rnd.nextFloat() * 4f) }
        assertNull(e.estimate())
        // Too little data.
        assertNull(feed(15f, 6f, 8f, 1f).estimate())
    }
}
