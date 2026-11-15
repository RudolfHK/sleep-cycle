package de.sleeptracker.audio

import de.sleeptracker.core.SoundCategory
import de.sleeptracker.core.SoundEvent
import de.sleeptracker.core.SoundEventType

/** A stored sound moment: one episode of the same category. */
class EpisodeRecord(
    /** Start, length (whole episode), majority type, best confidence, peak dB above background. */
    val event: SoundEvent,
    /** Number of individual sounds (e.g. snores) in the episode. */
    val count: Int,
    /** Sum of the individual sound durations. */
    val soundMillis: Long,
    /** Excerpt of the loudest sound in the episode, if captured. */
    val pcm: ShortArray?,
)

/**
 * Decides which episodes are "real moments" worth storing. [sensitivity] 0..1 shifts every
 * level threshold by ±6 dB (0.5 = default, tuned to give Sleep Cycle-like counts).
 */
class EventRules(sensitivity: Float = 0.5f) {
    private val shift = (0.5f - sensitivity.coerceIn(0f, 1f)) * 12f

    val coughPeakDb = 18f + shift
    val talkPeakDb = 12f + shift
    val noisePeakDb = 22f + shift
    val longNoisePeakDb = 16f + shift

    fun accept(r: EpisodeRecord): Boolean {
        val e = r.event
        return when (e.type.category) {
            SoundCategory.SNORING -> r.count >= 3 || r.soundMillis >= 10_000
            SoundCategory.COUGHING -> e.intensityDb >= coughPeakDb && e.confidence >= 0.6f
            SoundCategory.TALKING -> r.soundMillis >= 1_500 && e.intensityDb >= talkPeakDb
            SoundCategory.NOISE -> e.intensityDb >= noisePeakDb || (r.soundMillis >= 3_000 && e.intensityDb >= longNoisePeakDb)
        }
    }
}

/**
 * Groups detected sounds into episodes online (per category) and emits each episode once it has
 * been quiet for longer than [SoundEpisodes.gapFor]. Not thread-safe.
 */
class EpisodeAccumulator(private val rules: EventRules = EventRules()) {

    private class Open(
        val category: SoundCategory,
        val start: Long,
        var end: Long,
        var count: Int,
        var soundMillis: Long,
        var peak: Float,
        var confidence: Float,
        val types: MutableMap<SoundEventType, Int>,
        var pcm: ShortArray?,
        var pcmPeak: Float,
    )

    private val open = HashMap<SoundCategory, Open>()

    /** Adds a detected sound; returns episodes that closed because this sound came after their gap. */
    fun add(e: SoundEvent, pcm: ShortArray?): List<EpisodeRecord> {
        val out = closeIdle(e.startMillis)
        val c = e.type.category
        val end = e.startMillis + e.durationMillis
        val o = open[c]
        if (o == null) {
            open[c] = Open(c, e.startMillis, end, 1, e.durationMillis, e.intensityDb, e.confidence, mutableMapOf(e.type to 1), pcm, if (pcm != null) e.intensityDb else Float.NEGATIVE_INFINITY)
        } else {
            o.end = maxOf(o.end, end)
            o.count++
            o.soundMillis += e.durationMillis
            o.peak = maxOf(o.peak, e.intensityDb)
            o.confidence = maxOf(o.confidence, e.confidence)
            o.types[e.type] = (o.types[e.type] ?: 0) + 1
            if (pcm != null && e.intensityDb > o.pcmPeak) { o.pcm = pcm; o.pcmPeak = e.intensityDb }
        }
        return out
    }

    /** Closes episodes whose gap has passed at [nowMillis]; returns the accepted ones. */
    fun closeIdle(nowMillis: Long): List<EpisodeRecord> {
        val done = open.values.filter { nowMillis - it.end > SoundEpisodes.gapFor(it.category) }
        done.forEach { open.remove(it.category) }
        return done.map(::toRecord).filter(rules::accept)
    }

    /** Closes everything (end of night). */
    fun flushAll(): List<EpisodeRecord> {
        val all = open.values.toList()
        open.clear()
        return all.map(::toRecord).filter(rules::accept)
    }

    private fun toRecord(o: Open) = EpisodeRecord(
        event = SoundEvent(
            startMillis = o.start,
            durationMillis = (o.end - o.start).coerceAtLeast(1),
            type = o.types.maxBy { it.value }.key,
            confidence = o.confidence,
            intensityDb = o.peak,
        ),
        count = o.count,
        soundMillis = o.soundMillis,
        pcm = o.pcm,
    )

    companion object {
        /**
         * Applies the same grouping and rules to already stored single events (consolidation of
         * nights recorded before episodes existed). Returns accepted episodes with the indices of
         * their member events.
         */
        fun consolidate(
            events: List<SoundEvent>,
            rules: EventRules = EventRules(),
            /** Individual sounds per stored event (1 for single events). */
            counts: List<Int> = List(events.size) { 1 },
        ): List<Pair<EpisodeRecord, List<Int>>> =
            SoundEpisodes.group(events).mapNotNull { ep ->
                val members = ep.eventIndices.map { events[it] }
                val rec = EpisodeRecord(
                    event = SoundEvent(ep.startMillis, (ep.endMillis - ep.startMillis).coerceAtLeast(1), ep.type, members.maxOf { it.confidence }, ep.peakDb),
                    count = ep.eventIndices.sumOf { counts[it] },
                    soundMillis = ep.soundMillis,
                    pcm = null,
                )
                if (rules.accept(rec)) rec to ep.eventIndices else null
            }
    }
}
