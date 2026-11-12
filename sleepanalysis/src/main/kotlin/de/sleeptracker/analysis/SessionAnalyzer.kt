package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.StagedEpoch
import de.sleeptracker.core.TimeInterval

/** Summary figures of one night. All durations in minutes. */
data class SessionSummary(
    val startMillis: Long,
    val endMillis: Long,
    val timeInBedMinutes: Int,
    val sleepMinutes: Int,
    val sleepLatencyMinutes: Int,
    /** Sleep efficiency in percent (0..100). */
    val efficiency: Float,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val interruptions: List<TimeInterval>,
    /** 0..1, 1 = few stage changes and wake periods. */
    val stability: Float,
    val movementsPerHour: Float,
    val sleepOnsetMillis: Long?,
    val finalWakeMillis: Long?,
    val stages: List<StagedEpoch>,
)

/** Combines staging and summary statistics of a session. */
class SessionAnalyzer(private val stager: SleepStager = SleepStager()) {

    fun analyze(epochs: List<Epoch>, startMillis: Long, endMillis: Long): SessionSummary {
        val sorted = epochs.sortedBy { it.startMillis }
        val result = stager.stage(sorted)
        val staged = result.epochs
        val epochMin = EPOCH_MILLIS / 60_000f
        val timeInBed = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(0)

        fun minutesOf(stage: SleepStage) = (staged.count { it.stage == stage } * epochMin).toInt()
        val light = minutesOf(SleepStage.LIGHT)
        val deep = minutesOf(SleepStage.DEEP)
        val rem = minutesOf(SleepStage.REM)
        val sleep = (light + deep + rem).coerceAtMost(timeInBed)

        val onsetMillis = if (result.onsetIndex >= 0) staged[result.onsetIndex].startMillis else null
        val lastSleep = staged.indexOfLast { it.stage != SleepStage.AWAKE }
        val finalWake = if (lastSleep >= 0) staged[lastSleep].startMillis + EPOCH_MILLIS else null
        val latency = if (onsetMillis != null) ((onsetMillis - startMillis) / 60_000L).toInt().coerceAtLeast(0) else timeInBed

        // Interruptions: wake runs ≥ 5 min between onset and final wake.
        val interruptions = ArrayList<TimeInterval>()
        if (result.onsetIndex >= 0 && lastSleep > result.onsetIndex) {
            var i = result.onsetIndex
            while (i <= lastSleep) {
                if (staged[i].stage == SleepStage.AWAKE) {
                    val s = i
                    while (i <= lastSleep && staged[i].stage == SleepStage.AWAKE) i++
                    if (i - s >= 10) interruptions += TimeInterval(staged[s].startMillis, staged[i - 1].startMillis + EPOCH_MILLIS)
                } else i++
            }
        }
        val transitions = staged.zipWithNext().count { (a, b) -> a.stage != b.stage }
        val hours = (timeInBed / 60f).coerceAtLeast(0.25f)
        val stability = (1f - (transitions / hours) / 14f - interruptions.size * 0.05f).coerceIn(0f, 1f)
        val movements = sorted.sumOf { it.motionCount }

        return SessionSummary(
            startMillis = startMillis,
            endMillis = endMillis,
            timeInBedMinutes = timeInBed,
            sleepMinutes = sleep,
            sleepLatencyMinutes = latency,
            efficiency = if (timeInBed > 0) (sleep * 100f / timeInBed).coerceIn(0f, 100f) else 0f,
            awakeMinutes = (timeInBed - sleep).coerceAtLeast(0),
            lightMinutes = light,
            deepMinutes = deep,
            remMinutes = rem,
            interruptions = interruptions,
            stability = stability,
            movementsPerHour = movements / hours,
            sleepOnsetMillis = onsetMillis,
            finalWakeMillis = finalWake,
            stages = staged,
        )
    }
}
