package de.sleeptracker.audio

import de.sleeptracker.core.SoundEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class AudioTest {
    private val sr = 16_000
    private val rnd = Random(42)

    @Test
    fun fftFindsSinePeak() {
        val n = 1024
        val fft = Fft(n)
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        fft.transform(re, im)
        val mags = (0 until n / 2).map { hypot(re[it], im[it]) }
        assertEquals(64, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2.0, mags[64], 1e-6)
    }

    @Test
    fun featuresSeparateToneFromNoise() {
        val fx = FeatureExtractor(sr, 1024)
        val tone = ShortArray(1024) { (8000 * sin(2 * PI * 200 * it / sr)).toInt().toShort() }
        val noise = ShortArray(1024) { (rnd.nextDouble(-1.0, 1.0) * 8000).toInt().toShort() }
        val t = fx.extract(tone)
        val w = fx.extract(noise)
        assertTrue("tone low ratio ${t.lowRatio}", t.lowRatio > 0.9f)
        assertTrue("tone flatness ${t.flatness}", t.flatness < 0.1f)
        assertTrue("noise flatness ${w.flatness}", w.flatness > 0.4f)
        assertTrue(w.centroidHz > 3000f)
        assertTrue(abs(t.levelDb - (-15.2f)) < 1.5f)
    }

    @Test
    fun wavRoundTrip() {
        val pcm = ShortArray(500) { (it * 37).toShort() }
        val (decoded, rate) = WavEncoder.decode(WavEncoder.encode(pcm, sr))!!
        assertEquals(sr, rate)
        assertTrue(pcm.contentEquals(decoded))
    }

    // --- Synthetic signals ---------------------------------------------------------------

    private fun background(seconds: Double) = DoubleArray((seconds * sr).toInt()) { rnd.nextDouble(-1.0, 1.0) * 12 }

    private fun snore(seconds: Double = 1.6) = DoubleArray((seconds * sr).toInt()) { i ->
        val t = i.toDouble() / sr
        val env = sin(PI * t / seconds).let { it * it }
        val s = sin(2 * PI * 90 * t) + 0.7 * sin(2 * PI * 180 * t) + 0.4 * sin(2 * PI * 270 * t) + 0.15 * rnd.nextDouble(-1.0, 1.0)
        4000 * env * s
    }

    private fun cough(seconds: Double = 0.35) = DoubleArray((seconds * sr).toInt()) { i ->
        val t = i.toDouble() / sr
        9000 * exp(-t / 0.12) * rnd.nextDouble(-1.0, 1.0)
    }

    private fun talk(seconds: Double = 2.4) = DoubleArray((seconds * sr).toInt()) { i ->
        val t = i.toDouble() / sr
        val f0 = 170 + 20 * sin(2 * PI * 0.7 * t)
        var s = 0.0
        for (h in 1..18) {
            val f = h * f0
            val formant = exp(-((f - 700) / 350).let { it * it }) + 0.7 * exp(-((f - 1600) / 500).let { it * it })
            s += formant * sin(2 * PI * f * t)
        }
        val syllables = (0.5 + 0.5 * sin(2 * PI * 4 * t)).let { it * it }
        val env = (t / 0.05).coerceAtMost(1.0) * ((seconds - t) / 0.05).coerceIn(0.0, 1.0)
        3000 * syllables * env * s
    }

    private fun concat(vararg parts: DoubleArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) for (v in part) out[p++] = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
        return out
    }

    private fun mix(base: DoubleArray, add: DoubleArray, at: Int): DoubleArray {
        for (i in add.indices) if (at + i < base.size) base[at + i] += add[i]
        return base
    }

    private fun run(pcm: ShortArray): List<SoundEventDetector.DetectedEvent> {
        val d = SoundEventDetector(sr, 1024)
        val out = ArrayList<SoundEventDetector.DetectedEvent>()
        var i = 0
        while (i + 1024 <= pcm.size) {
            d.process(pcm.copyOfRange(i, i + 1024), i * 1000L / sr).event?.let { out += it }
            i += 1024
        }
        d.flush()?.let { out += it }
        return out
    }

    @Test
    fun detectsPeriodicSnoring() {
        val base = background(30.0)
        for (k in 0 until 5) mix(base, snore(), ((5 + k * 4.0) * sr).toInt())
        val events = run(concat(base))
        assertEquals(events.joinToString { it.event.type.name }, 5, events.size)
        assertTrue(events.all { it.event.type == SoundEventType.SNORE })
        assertTrue("later snores are periodic", events.last().event.confidence > 0.8f)
        assertNotNull("excerpt captured", events.first().pcm)
    }

    @Test
    fun detectsCough() {
        val base = background(12.0)
        mix(base, cough(), 8 * sr)
        val events = run(concat(base))
        assertEquals(1, events.size)
        assertEquals(SoundEventType.COUGH, events[0].event.type)
    }

    @Test
    fun detectsTalking() {
        val base = background(15.0)
        mix(base, talk(), 8 * sr)
        val events = run(concat(base))
        assertEquals(events.joinToString { it.event.type.name }, 1, events.size)
        assertEquals(SoundEventType.TALK, events[0].event.type)
    }

    @Test
    fun silenceProducesNoEvents() {
        assertTrue(run(concat(background(60.0))).isEmpty())
    }
}

class EpisodeAndSensitivityTest {
    private fun ev(t: Long, type: SoundEventType, dur: Long = 1500, db: Float = 20f) =
        de.sleeptracker.core.SoundEvent(t, dur, type, 0.8f, db)

    @Test
    fun groupsSnoresIntoEpisodes() {
        val events = (0 until 30).map { ev(it * 4_000L, SoundEventType.SNORE) } +
            (0 until 10).map { ev(3_600_000L + it * 5_000L, SoundEventType.SNORE) } +
            listOf(ev(60_000L, SoundEventType.COUGH, 400), ev(70_000L, SoundEventType.COUGH, 400), ev(200_000L, SoundEventType.COUGH, 400))
        val eps = SoundEpisodes.group(events)
        val snore = eps.filter { it.category == de.sleeptracker.core.SoundCategory.SNORING }
        assertEquals(2, snore.size)
        assertEquals(30, snore[0].count)
        assertEquals(45_000L, snore[0].soundMillis)
        val cough = eps.filter { it.category == de.sleeptracker.core.SoundCategory.COUGHING }
        assertEquals(listOf(2, 1), cough.map { it.count })
        assertTrue(eps.zipWithNext().all { (a, b) -> a.startMillis <= b.startMillis })
    }

    @Test
    fun faintShortNoiseIsNotReportable() {
        assertTrue(!SoundEpisodes.isReportable(ev(0, SoundEventType.NOISE_OTHER, 300, 20f)))
        assertTrue(!SoundEpisodes.isReportable(ev(0, SoundEventType.NOISE_HOUSEHOLD, 1500, 8f)))
        assertTrue(SoundEpisodes.isReportable(ev(0, SoundEventType.NOISE_DOOR, 600, 25f)))
        assertTrue(SoundEpisodes.isReportable(ev(0, SoundEventType.SNORE, 300, 5f)))
    }

    @Test
    fun quietRoomEventsAreDetected() {
        // Room floor around −95 dBFS (well below the old −80 dBFS gate); snoring 15 dB above it.
        val sr = 16_000
        val rnd = kotlin.random.Random(7)
        val pcm = ShortArray(20 * sr) { i ->
            val t = i.toDouble() / sr
            val inSnore = (t % 4.0) in 1.0..2.6
            val env = if (inSnore) kotlin.math.sin(Math.PI * ((t % 4.0) - 1.0) / 1.6).let { it * it } else 0.0
            val s = rnd.nextDouble(-1.0, 1.0) * 0.6 + env * 3.5 * (kotlin.math.sin(2 * Math.PI * 90 * t) + 0.6 * kotlin.math.sin(2 * Math.PI * 180 * t))
            s.toInt().toShort()
        }
        val d = SoundEventDetector(sr, 1024)
        var n = 0
        var i = 0
        while (i + 1024 <= pcm.size) { if (d.process(pcm.copyOfRange(i, i + 1024), i * 1000L / sr).event != null) n++; i += 1024 }
        assertTrue("events $n", n >= 3)
    }

    @Test
    fun rustleWithShortGapsIsOneEvent() {
        val sr = 16_000
        val rnd = kotlin.random.Random(9)
        // Background, then 3 noise bursts of 0.3 s separated by 0.3 s gaps.
        val pcm = ShortArray(10 * sr) { i ->
            val t = i.toDouble() / sr
            val burst = t in 5.0..6.5 && ((t - 5.0) % 0.6) < 0.3
            (rnd.nextDouble(-1.0, 1.0) * (if (burst) 2500 else 12)).toInt().toShort()
        }
        val d = SoundEventDetector(sr, 1024)
        val events = ArrayList<SoundEventDetector.DetectedEvent>()
        var i = 0
        while (i + 1024 <= pcm.size) { d.process(pcm.copyOfRange(i, i + 1024), i * 1000L / sr).event?.let { events += it }; i += 1024 }
        d.flush()?.let { events += it }
        assertEquals(1, events.size)
    }
}
