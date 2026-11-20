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
    version = 6,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /** v6: breathing rate, bedtime reminder and position-check settings. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE sleep_epoch ADD COLUMN breathsPerMinute REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_session ADD COLUMN avgBreathingRate REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_session ADD COLUMN breathingSd REAL NOT NULL DEFAULT -1",
                    "ALTER TABLE sleep_session ADD COLUMN breathingMinutes INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE sleep_session ADD COLUMN breathingFlags TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE user_settings ADD COLUMN bedtimeReminderEnabled INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE user_settings ADD COLUMN bedtimeReminderLeadMinutes INTEGER NOT NULL DEFAULT 30",
                    "ALTER TABLE user_settings ADD COLUMN positionCheckEnabled INTEGER NOT NULL DEFAULT 1",
                ).forEach(db::execSQL)
            }
        }

        /** v5: sound events become episodes (count and sound time per stored event). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sound_event ADD COLUMN eventCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sound_event ADD COLUMN soundSeconds REAL NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sound_event SET soundSeconds = durationSeconds")
            }
        }

        /** v4: number of light-sensor readings per night (diagnostics). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_session ADD COLUMN lightReadings INTEGER NOT NULL DEFAULT -1")
            }
        }

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
