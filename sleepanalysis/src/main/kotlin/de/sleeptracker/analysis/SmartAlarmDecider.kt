package de.sleeptracker.analysis

import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SleepStage

/**
 * Decides when to ring inside the smart wake-up window.
 *
 * Reliability rule: this class can only make the alarm ring *earlier*. The deadline is always
 * scheduled separately with the system AlarmManager, and [evaluate] returns [Decision.WAKE_DEADLINE]
 * once the deadline is reached, independent of any analysis result.
 */
class SmartAlarmDecider(
    private val windowStartMillis: Long,
    private val deadlineMillis: Long,
    private val stager: SleepStager = SleepStager(),
) {
    enum class Decision { WAIT, WAKE_LIGHT_SLEEP, WAKE_DEADLINE }

    private var consecutiveLight = 0

    /**
     * @param epochs all epochs of the night so far (sorted).
     */
    fun evaluate(nowMillis: Long, epochs: List<Epoch>): Decision {
        if (nowMillis >= deadlineMillis) return Decision.WAKE_DEADLINE
        if (nowMillis < windowStartMillis) return Decision.WAIT
        val decision = try {
            evaluateInWindow(nowMillis, epochs)
        } catch (t: Throwable) {
            // Analysis errors never block the alarm: the deadline alarm still fires.
            Decision.WAIT
        }
        return decision
    }

    private fun evaluateInWindow(nowMillis: Long, epochs: List<Epoch>): Decision {
        val recentStart = nowMillis - 10 * 60_000L
        val recent = epochs.filter { it.startMillis >= recentStart }
        // Signal quality: need most of the last 10 minutes with sensor data.
        val withSignal = recent.count { it.hasSignal }
        if (withSignal < 12) {
            consecutiveLight = 0
            return Decision.WAIT
        }
        val windowLength = (deadlineMillis - windowStartMillis).coerceAtLeast(EPOCH_MILLIS)
        val progress = ((nowMillis - windowStartMillis).toFloat() / windowLength).coerceIn(0f, 1f)

        // A big movement burst in the last minute is a good moment, whatever the stage estimate.
        val lastMinute = recent.filter { it.startMillis >= nowMillis - 60_000L }
        val burst = lastMinute.sumOf { SleepStager.activityOf(it).let { a -> if (a.isNaN()) 0.0 else a.toDouble() } }
        val burstThreshold = 8.0 - 4.0 * progress
        if (burst >= burstThreshold) return Decision.WAKE_LIGHT_SLEEP

        val staged = stager.stage(epochs.takeLast(240)).epochs
        val current = staged.lastOrNull() ?: return Decision.WAIT
        val weighted = recent.mapIndexed { i, e ->
            val a = SleepStager.activityOf(e)
            if (a.isNaN()) 0f else a * (i + 1f) / recent.size
        }.sum()
        val activityThreshold = 3f - 2f * progress
        val lightish = current.stage == SleepStage.AWAKE || current.stage == SleepStage.LIGHT
        val confident = current.confidence >= 0.6f - 0.15f * progress
        if (lightish && confident && weighted >= activityThreshold) consecutiveLight++ else consecutiveLight = 0
        return if (consecutiveLight >= 3 || (current.stage == SleepStage.AWAKE && confident && weighted >= activityThreshold * 2)) {
            Decision.WAKE_LIGHT_SLEEP
        } else Decision.WAIT
    }
}
