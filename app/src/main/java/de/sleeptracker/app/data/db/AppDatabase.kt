package de.sleeptracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserSettings::class,
        AlarmSettings::class,
        SleepSession::class,
        SleepStageRecord::class,
        SleepEpochRecord::class,
        MovementEventRecord::class,
        SoundEventRecord::class,
        SleepJournal::class,
        SleepStatistics::class,
        RecordingMetadata::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun sessionDao(): SessionDao
    abstract fun signalDao(): SignalDao
    abstract fun journalDao(): JournalDao
    abstract fun statisticsDao(): StatisticsDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sleep.db")
                // WAL keeps writes during the night cheap and crash-safe.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
