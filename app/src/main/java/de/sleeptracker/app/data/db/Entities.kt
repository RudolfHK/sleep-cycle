package de.sleeptracker.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.sleeptracker.core.MovementType
import de.sleeptracker.core.SensorSource
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.SleepStage
import de.sleeptracker.core.SnoozeMode
import de.sleeptracker.core.SoundEventType
import de.sleeptracker.core.TrackingMode
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class VolumeButtonAction { NONE, SNOOZE, STOP }

@Serializable
@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    /** BCP-47 tag, empty = system default. */
    val language: String = "",
    val theme: ThemeMode = ThemeMode.DARK,
    val sleepGoalMinutes: Int = 480,
    val defaultTrackingMode: TrackingMode = TrackingMode.SMART_ALARM,
    val soundTrackingEnabled: Boolean = true,
    val motionTrackingEnabled: Boolean = true,
    val audioRecordingEnabled: Boolean = true,
    val automaticRecordingDeletionDays: Int = 30,
    val motionSensitivity: Float = 0.5f,
    val soundSensitivity: Float = 0.5f,
    val detectSnoring: Boolean = true,
    val detectCoughing: Boolean = true,
    val detectTalking: Boolean = true,
    val detectNoise: Boolean = true,
    val weightDuration: Int = 30,
    val weightEfficiency: Int = 25,
    val weightInterruptions: Int = 20,
    val weightPhases: Int = 15,
    val weightRegularity: Int = 10,
    val createdAt: Long = System.currentTimeMillis(),
    val lightTrackingEnabled: Boolean = true,
    // v6
    val bedtimeReminderEnabled: Boolean = false,
    /** Reminder this many minutes before the calculated bedtime. */
    val bedtimeReminderLeadMinutes: Int = 30,
    val positionCheckEnabled: Boolean = true,
    /** Added to dBFS to show an (uncalibrated) estimate of the sound level in dB. */
    val noiseCalibrationDb: Int = 90,
)

fun UserSettings.sensorSource(): SensorSource = when {
    soundTrackingEnabled && motionTrackingEnabled -> SensorSource.COMBINED
    soundTrackingEnabled -> SensorSource.SOUND
    else -> SensorSource.MOTION
}

@Serializable
@Entity(tableName = "alarm_settings")
data class AlarmSettings(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = true,
    val alarmMode: TrackingMode = TrackingMode.SMART_ALARM,
    /** Minute of day (0..1439) of the desired / latest wake-up time. */
    val targetWakeTime: Int = 7 * 60,
    val wakeupWindowMinutes: Int = 30,
    /** Ringtone URI, empty = system default alarm sound. */
    val alarmSound: String = "",
    /** 0..100 */
    val alarmVolume: Int = 80,
    val gradualVolumeEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val snoozeMode: SnoozeMode = SnoozeMode.FIXED,
    val snoozeDurationMinutes: Int = 10,
    val maximumSnoozes: Int = 3,
    val doubleTapSnoozeEnabled: Boolean = false,
    val pickupSnoozeEnabled: Boolean = false,
    val volumeButtonAction: VolumeButtonAction = VolumeButtonAction.SNOOZE,
    /** Stopping requires a long press, to avoid dismissing the alarm half asleep. */
    val confirmationRequired: Boolean = false,
)

@Serializable
@Entity(tableName = "sleep_session", indices = [Index("startTimestamp")])
data class SleepSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local date (ISO yyyy-MM-dd) of the morning the session ended (or will end). */
    val sessionDate: String,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val zoneOffsetMinutes: Int = 0,
    val timeInBedMinutes: Int = 0,
    val estimatedSleepMinutes: Int = 0,
    val sleepLatencyMinutes: Int = 0,
    val sleepEfficiency: Float = 0f,
    val sleepQualityScore: Int = 0,
    val trackingMode: TrackingMode,
    val sensorSource: SensorSource,
    val sessionStatus: SessionStatus,
    val awakeMinutes: Int = 0,
    val lightMinutes: Int = 0,
    val deepMinutes: Int = 0,
    val remMinutes: Int = 0,
    val interruptions: Int = 0,
    val stability: Float = 0f,
    val movementsPerHour: Float = 0f,
    val snoreEvents: Int = 0,
    val snoreSeconds: Int = 0,
    val coughEvents: Int = 0,
    val talkEvents: Int = 0,
    val noiseEvents: Int = 0,
    val alarmTargetMillis: Long? = null,
    val alarmWindowMinutes: Int = 0,
    val alarmRangMillis: Long? = null,
    // Bedroom environment (v2). Noise in dBFS, light in lux.
    val hasNoiseData: Boolean = false,
    val avgNoiseDb: Float = -100f,
    val peakNoiseDb: Float = -100f,
    val loudMinutes: Int = 0,
    val hasLightData: Boolean = false,
    val avgLux: Float = -1f,
    val maxLux: Float = -1f,
    val darkPercent: Float = 0f,
    val brightMinutes: Int = 0,
    val luxAtOnset: Float = -1f,
    // v3: imported nights.
    /** [SOURCE_APP] for nights recorded here, [SOURCE_SLEEP_CYCLE] for imported ones. */
    val source: String = SOURCE_APP,
    val breathingDisruptionsPerHour: Float = -1f,
    /** Regularity as reported by the source app (0..100), −1 if unknown. */
    val externalRegularity: Int = -1,
    /** v4: light-sensor readings received during the night; −1 = light not tracked. */
    val lightReadings: Int = -1,
    // v6: breathing rate (estimate)
    val avgBreathingRate: Float = -1f,
    val breathingSd: Float = -1f,
    val breathingMinutes: Int = 0,
    /** Comma-separated BreathingSummary.Flag names. */
    val breathingFlags: String = "",
) {
    /** Recorded by this app, so a hypnogram and 30-s epochs exist. */
    val hasPhaseData: Boolean get() = source == SOURCE_APP

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_SLEEP_CYCLE = "sleep_cycle"
    }
}

/** Compressed hypnogram: one row per run of identical stage. */
@Serializable
@Entity(
    tableName = "sleep_stage",
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SleepStageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val durationSeconds: Int,
    val stageType: SleepStage,
    val confidence: Float,
)

/** Aggregated 30-second epoch (no raw sensor data is stored). */
@Serializable
@Entity(
    tableName = "sleep_epoch",
    primaryKeys = ["sessionId", "startMillis"],
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SleepEpochRecord(
    val sessionId: Long,
    val startMillis: Long,
    val motionCount: Int,
    val motionIntensity: Float,
    val soundActivity: Float,
    val soundLevelDb: Float,
    val soundEventCount: Int,
    val snoreSeconds: Float,
    val hasMotion: Boolean,
    val hasSound: Boolean,
    val soundPeakDb: Float = -100f,
    val lightLux: Float = -1f,
    val lightMaxLux: Float = -1f,
    val hasLight: Boolean = false,
    val breathsPerMinute: Float = -1f,
)

@Serializable
@Entity(
    tableName = "movement_event",
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class MovementEventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val movementIntensity: Float,
    val movementDuration: Int,
    val movementType: MovementType,
)

@Serializable
@Entity(
    tableName = "sound_event",
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SoundEventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val eventType: SoundEventType,
    val durationSeconds: Float,
    val confidence: Float,
    val intensityDb: Float,
    val audioFilePath: String? = null,
    /** v5: number of individual sounds in this episode (e.g. snores). */
    val eventCount: Int = 1,
    /** v5: total sound time of the episode in seconds (≤ duration). */
    val soundSeconds: Float = 0f,
)

@Serializable
@Entity(
    tableName = "sleep_journal",
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", unique = true)],
)
data class SleepJournal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val note: String = "",
    /** 0 = not set, 1..5 */
    val mood: Int = 0,
    /** Comma separated tag keys, see [JournalTags]. */
    val personalTags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

object JournalTags {
    val ALL = listOf("stress", "coffee", "alcohol", "exercise", "late_meal", "travel", "illness", "unusual_place")
}

/** Cached aggregate statistics for a period (recomputed from sessions on demand). */
@Serializable
@Entity(tableName = "sleep_statistics", indices = [Index(value = ["periodStart", "periodEnd"], unique = true)])
data class SleepStatistics(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodStart: Long,
    val periodEnd: Long,
    val averageSleepDuration: Float,
    val averageSleepQuality: Float,
    val averageBedtime: Int?,
    val averageWakeTime: Int?,
    val averageEfficiency: Float,
    val averageSnoringEvents: Float,
    val averageCoughingEvents: Float,
)

@Serializable
@Entity(
    tableName = "recording_metadata",
    foreignKeys = [ForeignKey(entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("sessionId")],
)
data class RecordingMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null when the session was deleted but the user chose to keep its recordings. */
    val sessionId: Long?,
    val soundEventId: Long? = null,
    /** Path relative to the app's private recordings directory. */
    val filePath: String,
    val eventType: SoundEventType,
    val timestamp: Long,
    val durationSeconds: Float,
    val fileSize: Long,
    val deletionDate: Long?,
)
