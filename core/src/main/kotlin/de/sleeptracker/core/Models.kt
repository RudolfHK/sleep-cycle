package de.sleeptracker.core

/** Length of one analysis epoch. All staging operates on 30-second epochs. */
const val EPOCH_MILLIS: Long = 30_000L

/** Estimated sleep stage. These are estimates from phone sensors, never clinical measurements. */
enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

enum class TrackingMode {
    /** Smart alarm inside a wake-up window, ending at the target time at the latest. */
    SMART_ALARM,
    /** Conventional alarm at exactly the target time. */
    REGULAR_ALARM,
    /** Track only, no alarm. */
    TRACKING_ONLY;

    val hasAlarm: Boolean get() = this != TRACKING_ONLY
}

enum class SensorSource {
    MOTION, SOUND, COMBINED;

    val usesMotion: Boolean get() = this != SOUND
    val usesSound: Boolean get() = this != MOTION
}

enum class SnoozeMode { OFF, FIXED, INTELLIGENT }

enum class SessionStatus { ACTIVE, COMPLETED, INTERRUPTED }

enum class MovementType { SMALL, LARGE, SOUND_DERIVED }

enum class SoundEventType(val isNoise: Boolean) {
    SNORE(false),
    COUGH(false),
    TALK(false),
    NOISE_TRAFFIC(true),
    NOISE_DOOR(true),
    NOISE_TV(true),
    NOISE_HOUSEHOLD(true),
    NOISE_OTHER(true);

    /** Category toggle group used by the settings (all environmental noises share one toggle). */
    val category: SoundCategory
        get() = when (this) {
            SNORE -> SoundCategory.SNORING
            COUGH -> SoundCategory.COUGHING
            TALK -> SoundCategory.TALKING
            else -> SoundCategory.NOISE
        }
}

enum class SoundCategory { SNORING, COUGHING, TALKING, NOISE }

/**
 * Aggregated sensor data for one 30-second epoch. This is the only per-night signal that is
 * persisted (raw sensor samples are never stored).
 */
data class Epoch(
    val startMillis: Long,
    /** Number of discrete motion events detected by the accelerometer. */
    val motionCount: Int = 0,
    /** Sum of motion excess above the adaptive threshold (arbitrary units, m/s²). */
    val motionIntensity: Float = 0f,
    /** Fraction of audio frames (0..1) that were clearly above the noise floor. */
    val soundActivity: Float = 0f,
    /** Mean audio level in dBFS (−100 when no audio). */
    val soundLevelDb: Float = -100f,
    /** Number of classified sound events that started inside this epoch. */
    val soundEventCount: Int = 0,
    /** Seconds of snoring inside this epoch (snoring is a sleep sign, not movement). */
    val snoreSeconds: Float = 0f,
    val hasMotion: Boolean = false,
    val hasSound: Boolean = false,
) {
    val hasSignal: Boolean get() = hasMotion || hasSound
}

data class StagedEpoch(
    val startMillis: Long,
    val stage: SleepStage,
    /** Confidence of the estimate in 0..1. */
    val confidence: Float,
    /** Activity value used for the decision (for graphs). */
    val activity: Float,
)

/** A detected sound event (snore, cough, speech, environmental noise). */
data class SoundEvent(
    val startMillis: Long,
    val durationMillis: Long,
    val type: SoundEventType,
    val confidence: Float,
    /** Peak level above the noise floor in dB. */
    val intensityDb: Float,
)

data class TimeInterval(val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}
