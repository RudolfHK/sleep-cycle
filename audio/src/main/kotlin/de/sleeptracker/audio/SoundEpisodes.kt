package de.sleeptracker.audio

import de.sleeptracker.core.SoundCategory
import de.sleeptracker.core.SoundEvent
import de.sleeptracker.core.SoundEventType

/** A run of sound events of the same category (e.g. 20 minutes of snoring). */
data class SoundEpisode(
    val category: SoundCategory,
    /** Most frequent event type within the episode (relevant for noise sub-types). */
    val type: SoundEventType,
    val startMillis: Long,
    val endMillis: Long,
    val count: Int,
    /** Sum of the individual event durations. */
    val soundMillis: Long,
    val peakDb: Float,
    /** Indices into the input list, in time order. */
    val eventIndices: List<Int>,
)

object SoundEpisodes {
    const val SNORE_GAP_MILLIS = 60_000L
    const val OTHER_GAP_MILLIS = 30_000L

    fun gapFor(category: SoundCategory) = if (category == SoundCategory.SNORING) SNORE_GAP_MILLIS else OTHER_GAP_MILLIS

    /** Groups events per category; a new episode starts after a gap longer than [gapFor]. */
    fun group(events: List<SoundEvent>): List<SoundEpisode> {
        val out = ArrayList<SoundEpisode>()
        val order = events.indices.sortedBy { events[it].startMillis }
        for (category in SoundCategory.entries) {
            var current = ArrayList<Int>()
            var lastEnd = Long.MIN_VALUE
            fun close() {
                if (current.isEmpty()) return
                val evs = current.map { events[it] }
                out += SoundEpisode(
                    category = category,
                    type = evs.groupingBy { it.type }.eachCount().maxBy { it.value }.key,
                    startMillis = evs.first().startMillis,
                    endMillis = evs.maxOf { it.startMillis + it.durationMillis },
                    count = evs.size,
                    soundMillis = evs.sumOf { it.durationMillis },
                    peakDb = evs.maxOf { it.intensityDb },
                    eventIndices = current.toList(),
                )
                current = ArrayList()
            }
            for (i in order) {
                val e = events[i]
                if (e.type.category != category) continue
                if (current.isNotEmpty() && e.startMillis - lastEnd > gapFor(category)) close()
                current += i
                lastEnd = maxOf(lastEnd, e.startMillis + e.durationMillis)
            }
            close()
        }
        return out.sortedBy { it.startMillis }
    }

    /**
     * Whether an environmental-noise event is worth keeping. Very short or faint bursts are counted
     * for activity but not stored as events.
     */
    fun isReportable(e: SoundEvent): Boolean =
        !e.type.isNoise || (e.durationMillis >= 400 && e.intensityDb >= 12f)
}
