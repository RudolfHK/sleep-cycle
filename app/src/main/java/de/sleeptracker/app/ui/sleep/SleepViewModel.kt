package de.sleeptracker.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.sleeptracker.app.AppContainer
import de.sleeptracker.app.data.db.AlarmSettings
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.tracking.SleepTrackingService
import de.sleeptracker.app.tracking.StartResult
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SleepUiState(
    val user: UserSettings = UserSettings(),
    val alarm: AlarmSettings = AlarmSettings(),
    val lastSession: SleepSession? = null,
    val interrupted: List<SleepSession> = emptyList(),
    val tracking: Boolean = false,
)

class SleepViewModel(private val c: AppContainer) : ViewModel() {

    val state: StateFlow<SleepUiState> = combine(
        c.settings.userSettings,
        c.settings.alarmSettings,
        c.sleep.observeCompleted().map { it.firstOrNull() },
        c.sleep.observeUnfinished(),
        SleepTrackingService.liveStatus,
    ) { user, alarm, last, unfinished, live ->
        SleepUiState(
            user = user,
            alarm = alarm,
            lastSession = last,
            interrupted = unfinished.filter { it.sessionStatus == SessionStatus.INTERRUPTED || (live == null && it.sessionStatus == SessionStatus.ACTIVE && !SleepTrackingService.isRunning) },
            tracking = live != null,
        )
    }.combine(c.tracking.finalizing) { st, fin ->
        // A night that is being saved right now is not "interrupted".
        if (fin == null) st else st.copy(interrupted = st.interrupted.filter { it.id != fin })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState())

    fun setMode(mode: TrackingMode) = viewModelScope.launch {
        c.settings.updateAlarm { it.copy(alarmMode = mode, enabled = mode.hasAlarm) }
        c.settings.updateUser { it.copy(defaultTrackingMode = mode) }
    }

    fun setTarget(minuteOfDay: Int) = viewModelScope.launch { c.settings.updateAlarm { it.copy(targetWakeTime = minuteOfDay) } }
    fun setWindow(minutes: Int) = viewModelScope.launch {
        c.settings.updateAlarm {
            if (minutes == 0) it.copy(wakeupWindowMinutes = 0, alarmMode = TrackingMode.REGULAR_ALARM)
            else it.copy(wakeupWindowMinutes = minutes)
        }
    }

    fun setSensors(sound: Boolean, motion: Boolean) = viewModelScope.launch {
        if (!sound && !motion) return@launch
        c.settings.updateUser { it.copy(soundTrackingEnabled = sound, motionTrackingEnabled = motion) }
    }

    fun setSound(uri: String) = viewModelScope.launch { c.settings.updateAlarm { it.copy(alarmSound = uri) } }

    suspend fun start(): StartResult {
        val mode = state.value.alarm.alarmMode
        return c.tracking.start(mode)
    }

    fun resume(id: Long) = viewModelScope.launch { c.tracking.resume(id) }
    fun finish(id: Long) = viewModelScope.launch { c.tracking.finishInterrupted(id) }
    fun discard(id: Long) = viewModelScope.launch { c.tracking.discard(id) }
}
