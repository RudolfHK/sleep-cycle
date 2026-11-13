package de.sleeptracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** v3: imported nights (source app, breathing disruptions, external regularity). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_session ADD COLUMN source TEXT NOT NULL DEFAULT 'app'")
                db.execSQL("ALTER TABLE sleep_session ADD COLUMN breathingDisruptionsPerHour REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE sleep_session ADD COLUMN externalRegularity INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** v2: bedroom environment (noise peak, light) per epoch and per night. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE sleep_epoch ADD COLUMN soundPeakDb REAL NOT NULL DEFAULT -100",
                    "ALTER TABLE sleep_epoch ADD COLUMN lightLux REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_epoch ADD COLUMN lightMaxLux REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_epoch ADD COLUMN hasLight INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN hasNoiseData INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN avgNoiseDb REAL NOT NULL DEFAULT -100",
                    "ALTER TABLE sleep_session ADD COLUMN peakNoiseDb REAL NOT NULL DEFAULT -100",
                    "ALTER TABLE sleep_session ADD COLUMN loudMinutes INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN hasLightData INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN avgLux REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_session ADD COLUMN maxLux REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_session ADD COLUMN darkPercent REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN brightMinutes INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN luxAtOnset REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE user_settings ADD COLUMN lightTrackingEnabled INTEGER NOT NULL DEFAULT 1",
                    "ALTER TABLE user_settings ADD COLUMN noiseCalibrationDb INTEGER NOT NULL DEFAULT 90",
                ).forEach(db::execSQL)
                // Night-level environment of older nights is back-filled in Kotlin
                // (SleepRepository.backfillEnvironment), as SQLite math functions are not guaranteed.
            }
        }
    }
}
