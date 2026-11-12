package de.sleeptracker.analysis

import de.sleeptracker.core.SnoozeMode

/** Computes the next snooze duration. */
object SnoozeCalculator {

    /** Intelligent snooze: durations shrink towards the final wake-up time. */
    val INTELLIGENT_SEQUENCE = intArrayOf(10, 7, 5, 3, 2, 1)

    /**
     * @param snoozeCount number of snoozes already used.
     * @param finalDeadlineMillis latest allowed wake time for intelligent snooze.
     * @return the snooze duration in minutes, or null if snoozing is not allowed any more.
     */
    fun nextSnoozeMinutes(
        mode: SnoozeMode,
        snoozeCount: Int,
        fixedMinutes: Int,
        maxSnoozes: Int,
        nowMillis: Long,
        finalDeadlineMillis: Long,
    ): Int? = when (mode) {
        SnoozeMode.OFF -> null
        SnoozeMode.FIXED -> if (snoozeCount < maxSnoozes) fixedMinutes.coerceAtLeast(1) else null
        SnoozeMode.INTELLIGENT -> {
            val remaining = ((finalDeadlineMillis - nowMillis) / 60_000L).toInt()
            val planned = INTELLIGENT_SEQUENCE[snoozeCount.coerceIn(0, INTELLIGENT_SEQUENCE.size - 1)]
            val minutes = minOf(planned, remaining)
            if (minutes >= 1 && snoozeCount < maxOf(maxSnoozes, INTELLIGENT_SEQUENCE.size)) minutes else null
        }
    }
}
