package de.sleeptracker.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.runtimeDataStore: DataStore<Preferences> by preferencesDataStore(name = "runtime")

/** The alarm planned for the current night. Persisted so it survives process death and reboot. */
@Serializable
data class AlarmPlan(
    /** Unique per night; used to ignore stale or duplicate alarm broadcasts. */
    val instanceId: Long,
    val sessionId: Long,
    val mode: TrackingMode,
    /** Minute of day of the configured wake-up time (re-resolved on time zone changes). */
    val targetMinuteOfDay: Int,
    /** Final wake-up time (absolute). */
    val deadlineMillis: Long,
    val windowMinutes: Int,
    /** Next time the system alarm will fire (deadline or end of snooze). */
    val nextFireMillis: Long,
    val snoozeCount: Int = 0,
    val ringing: Boolean = false,
    val firstRangAtMillis: Long? = null,
) {
    val windowStartMillis: Long
        get() = if (mode == TrackingMode.SMART_ALARM) deadlineMillis - windowMinutes * 60_000L else deadlineMillis
}

/** Small runtime state in DataStore (not part of the relational data). */
class RuntimeStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyActiveSession = longPreferencesKey("active_session")
    private val keyAlarmPlan = stringPreferencesKey("alarm_plan")
    private val keyOnboarding = booleanPreferencesKey("onboarding_done")
    private val keyEnvBackfill = booleanPreferencesKey("environment_backfill_v2")
    private val keyAnalysisVersion = androidx.datastore.preferences.core.intPreferencesKey("analysis_version")

    val activeSessionId: Flow<Long?> = context.runtimeDataStore.data.map { it[keyActiveSession]?.takeIf { id -> id > 0 } }
    val alarmPlan: Flow<AlarmPlan?> = context.runtimeDataStore.data.map { p -> p[keyAlarmPlan]?.let(::decode) }
    val onboardingDone: Flow<Boolean> = context.runtimeDataStore.data.map { it[keyOnboarding] ?: false }

    suspend fun activeSession(): Long? = activeSessionId.first()
    suspend fun plan(): AlarmPlan? = alarmPlan.first()

    suspend fun setActiveSession(id: Long?) {
        context.runtimeDataStore.edit { if (id == null) it.remove(keyActiveSession) else it[keyActiveSession] = id }
    }

    suspend fun setPlan(plan: AlarmPlan?) {
        context.runtimeDataStore.edit {
            if (plan == null) it.remove(keyAlarmPlan) else it[keyAlarmPlan] = json.encodeToString(AlarmPlan.serializer(), plan)
        }
    }

    /** Atomic read-modify-write of the alarm plan. Returns the new plan. */
    suspend fun updatePlan(transform: (AlarmPlan) -> AlarmPlan?): AlarmPlan? {
        var result: AlarmPlan? = null
        context.runtimeDataStore.edit { prefs ->
            val current = prefs[keyAlarmPlan]?.let(::decode) ?: return@edit
            val next = transform(current)
            result = next
            if (next == null) prefs.remove(keyAlarmPlan) else prefs[keyAlarmPlan] = json.encodeToString(AlarmPlan.serializer(), next)
        }
        return result
    }

    suspend fun environmentBackfillDone(): Boolean = context.runtimeDataStore.data.first()[keyEnvBackfill] ?: false

    suspend fun setEnvironmentBackfillDone() {
        context.runtimeDataStore.edit { it[keyEnvBackfill] = true }
    }

    suspend fun analysisVersion(): Int = context.runtimeDataStore.data.first()[keyAnalysisVersion] ?: 0

    suspend fun setAnalysisVersion(v: Int) {
        context.runtimeDataStore.edit { it[keyAnalysisVersion] = v }
    }

    suspend fun setOnboardingDone() {
        context.runtimeDataStore.edit { it[keyOnboarding] = true }
    }

    suspend fun clear() {
        context.runtimeDataStore.edit { it.clear() }
    }

    private fun decode(s: String): AlarmPlan? = try {
        json.decodeFromString(AlarmPlan.serializer(), s)
    } catch (e: Exception) {
        null
    }
}
