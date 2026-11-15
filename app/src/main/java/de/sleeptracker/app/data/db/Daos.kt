package de.sleeptracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun userSettings(): Flow<UserSettings?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getUserSettings(): UserSettings?

    @Upsert
    suspend fun upsertUserSettings(settings: UserSettings)

    @Query("SELECT * FROM alarm_settings WHERE id = 1")
    fun alarmSettings(): Flow<AlarmSettings?>

    @Query("SELECT * FROM alarm_settings WHERE id = 1")
    suspend fun getAlarmSettings(): AlarmSettings?

    @Upsert
    suspend fun upsertAlarmSettings(settings: AlarmSettings)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SleepSession): Long

    @Upsert
    suspend fun upsert(session: SleepSession)

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    suspend fun get(id: Long): SleepSession?

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    fun observe(id: Long): Flow<SleepSession?>

    @Query("SELECT * FROM sleep_session WHERE sessionStatus = 'COMPLETED' ORDER BY startTimestamp DESC")
    fun observeCompleted(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_session WHERE sessionStatus = 'COMPLETED' AND startTimestamp >= :from AND startTimestamp < :to ORDER BY startTimestamp")
    suspend fun completedBetween(from: Long, to: Long): List<SleepSession>

    @Query("SELECT * FROM sleep_session WHERE sessionStatus = 'COMPLETED' ORDER BY startTimestamp")
    suspend fun allCompleted(): List<SleepSession>

    @Query("SELECT * FROM sleep_session WHERE sessionStatus IN ('ACTIVE','INTERRUPTED') ORDER BY startTimestamp DESC")
    suspend fun unfinished(): List<SleepSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SleepSession>)

    @Query("SELECT * FROM sleep_session WHERE sessionStatus IN ('ACTIVE','INTERRUPTED') ORDER BY startTimestamp DESC")
    fun observeUnfinished(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_session WHERE sessionStatus = 'COMPLETED' AND startTimestamp < :before ORDER BY startTimestamp DESC LIMIT :limit")
    suspend fun completedBefore(before: Long, limit: Int): List<SleepSession>

    @Query("SELECT * FROM sleep_session ORDER BY startTimestamp")
    suspend fun all(): List<SleepSession>

    @Query("DELETE FROM sleep_session WHERE id = :id")
    suspend fun delete(id: Long)

    /** Keeps the highest count (the service restarts its counter after a process restart). */
    @Query("UPDATE sleep_session SET lightReadings = MAX(lightReadings, :count) WHERE id = :id")
    suspend fun setLightReadings(id: Long, count: Int)

    @Query("DELETE FROM sleep_session")
    suspend fun deleteAll()
}

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpochs(epochs: List<SleepEpochRecord>)

    @Query("SELECT * FROM sleep_epoch WHERE sessionId = :sessionId ORDER BY startMillis")
    suspend fun epochs(sessionId: Long): List<SleepEpochRecord>

    @Query("SELECT * FROM sleep_epoch ORDER BY sessionId, startMillis")
    suspend fun allEpochs(): List<SleepEpochRecord>

    @Insert
    suspend fun insertStages(stages: List<SleepStageRecord>)

    @Query("DELETE FROM sleep_stage WHERE sessionId = :sessionId")
    suspend fun deleteStages(sessionId: Long)

    @Query("SELECT * FROM sleep_stage WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun stages(sessionId: Long): List<SleepStageRecord>

    @Query("SELECT * FROM sleep_stage ORDER BY sessionId, timestamp")
    suspend fun allStages(): List<SleepStageRecord>

    @Transaction
    suspend fun replaceStages(sessionId: Long, stages: List<SleepStageRecord>) {
        deleteStages(sessionId)
        insertStages(stages)
    }

    @Insert
    suspend fun insertMovements(events: List<MovementEventRecord>)

    @Query("SELECT * FROM movement_event WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun movements(sessionId: Long): List<MovementEventRecord>

    @Query("SELECT * FROM movement_event ORDER BY sessionId, timestamp")
    suspend fun allMovements(): List<MovementEventRecord>

    @Insert
    suspend fun insertSoundEvent(event: SoundEventRecord): Long

    @Insert
    suspend fun insertSoundEvents(events: List<SoundEventRecord>)

    @Query("SELECT * FROM sound_event WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun soundEvents(sessionId: Long): List<SoundEventRecord>

    @Query("SELECT * FROM sound_event WHERE sessionId = :sessionId ORDER BY timestamp")
    fun observeSoundEvents(sessionId: Long): Flow<List<SoundEventRecord>>

    @Query("SELECT * FROM sound_event ORDER BY sessionId, timestamp")
    suspend fun allSoundEvents(): List<SoundEventRecord>

    @Query("DELETE FROM sound_event WHERE sessionId = :sessionId")
    suspend fun deleteSoundEvents(sessionId: Long)

    @Query("UPDATE sound_event SET audioFilePath = :path WHERE id = :id")
    suspend fun setAudioPath(id: Long, path: String)

    @Query("UPDATE sound_event SET audioFilePath = NULL WHERE audioFilePath = :path")
    suspend fun clearAudioPath(path: String)

    @Query("UPDATE sound_event SET audioFilePath = NULL")
    suspend fun clearAllAudioPaths()
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM sleep_journal WHERE sessionId = :sessionId")
    fun observe(sessionId: Long): Flow<SleepJournal?>

    @Query("SELECT * FROM sleep_journal WHERE sessionId = :sessionId")
    suspend fun get(sessionId: Long): SleepJournal?

    @Query("SELECT * FROM sleep_journal")
    suspend fun all(): List<SleepJournal>

    @Upsert
    suspend fun upsert(journal: SleepJournal)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SleepJournal>)
}

@Dao
interface StatisticsDao {
    @Query("SELECT * FROM sleep_statistics WHERE periodStart = :start AND periodEnd = :end")
    suspend fun get(start: Long, end: Long): SleepStatistics?

    @Upsert
    suspend fun upsert(stats: SleepStatistics)

    /** Inserts or replaces the cached row for the same period. */
    @Transaction
    suspend fun upsertKeyed(stats: SleepStatistics) {
        val existing = get(stats.periodStart, stats.periodEnd)
        upsert(stats.copy(id = existing?.id ?: 0))
    }

    @Query("DELETE FROM sleep_statistics")
    suspend fun clear()
}

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: RecordingMetadata): Long

    @Insert
    suspend fun insertAll(recordings: List<RecordingMetadata>)

    @Query("SELECT * FROM recording_metadata ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RecordingMetadata>>

    @Query("SELECT * FROM recording_metadata ORDER BY timestamp")
    suspend fun all(): List<RecordingMetadata>

    @Query("SELECT * FROM recording_metadata WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun forSession(sessionId: Long): List<RecordingMetadata>

    @Query("SELECT * FROM recording_metadata WHERE deletionDate IS NOT NULL AND deletionDate <= :now")
    suspend fun expired(now: Long): List<RecordingMetadata>

    @Query("SELECT * FROM recording_metadata WHERE id = :id")
    suspend fun get(id: Long): RecordingMetadata?

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM recording_metadata")
    fun observeTotalSize(): Flow<Long>

    @Query("DELETE FROM recording_metadata WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recording_metadata")
    suspend fun deleteAll()
}
