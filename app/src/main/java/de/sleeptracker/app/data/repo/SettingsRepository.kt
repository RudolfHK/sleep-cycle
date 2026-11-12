package de.sleeptracker.app.data.repo

import de.sleeptracker.app.data.db.AlarmSettings
import de.sleeptracker.app.data.db.SettingsDao
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.analysis.ScoreWeights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsRepository(private val dao: SettingsDao) {
    private val mutex = Mutex()

    val userSettings: Flow<UserSettings> = dao.userSettings().map { it ?: UserSettings() }
    val alarmSettings: Flow<AlarmSettings> = dao.alarmSettings().map { it ?: AlarmSettings() }

    suspend fun user(): UserSettings = dao.getUserSettings() ?: UserSettings()
    suspend fun alarm(): AlarmSettings = dao.getAlarmSettings() ?: AlarmSettings()

    suspend fun updateUser(transform: (UserSettings) -> UserSettings) = mutex.withLock {
        dao.upsertUserSettings(transform(user()).copy(id = 1))
    }

    suspend fun updateAlarm(transform: (AlarmSettings) -> AlarmSettings) = mutex.withLock {
        dao.upsertAlarmSettings(transform(alarm()).copy(id = 1))
    }

    suspend fun replaceAll(user: UserSettings, alarm: AlarmSettings) = mutex.withLock {
        dao.upsertUserSettings(user.copy(id = 1))
        dao.upsertAlarmSettings(alarm.copy(id = 1))
    }
}

fun UserSettings.scoreWeights() = ScoreWeights(
    duration = weightDuration.toFloat(),
    efficiency = weightEfficiency.toFloat(),
    interruptions = weightInterruptions.toFloat(),
    phases = weightPhases.toFloat(),
    regularity = weightRegularity.toFloat(),
)
