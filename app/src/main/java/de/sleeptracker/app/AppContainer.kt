package de.sleeptracker.app

import android.content.Context
import de.sleeptracker.app.alarm.AlarmScheduler
import de.sleeptracker.app.data.crypto.CryptoManager
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.export.DataExporter
import de.sleeptracker.app.data.repo.RecordingRepository
import de.sleeptracker.app.data.repo.RuntimeStore
import de.sleeptracker.app.data.repo.SettingsRepository
import de.sleeptracker.app.data.repo.SleepRepository
import de.sleeptracker.app.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection; one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase = AppDatabase.create(appContext)
    val crypto = CryptoManager()
    val settings = SettingsRepository(database.settingsDao())
    val runtime = RuntimeStore(appContext)
    val recordings = RecordingRepository(appContext, database, crypto)
    val sleep = SleepRepository(database, settings, recordings)
    val alarmScheduler = AlarmScheduler(appContext, runtime)
    val tracking = TrackingController(appContext, this)
    val exporter = DataExporter(appContext, database, recordings, crypto)
}
