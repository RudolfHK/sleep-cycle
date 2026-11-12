package de.sleeptracker.app.data.repo

import androidx.room.withTransaction
import de.sleeptracker.analysis.EnvironmentAnalyzer
import de.sleeptracker.analysis.EnvironmentSummary
import de.sleeptracker.analysis.NightRecord
import de.sleeptracker.analysis.SessionAnalyzer
import de.sleeptracker.analysis.SleepScoreCalculator
import de.sleeptracker.analysis.SleepStager
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.db.MovementEventRecord
import de.sleeptracker.app.data.db.SleepEpochRecord
import de.sleeptracker.app.data.db.SleepJournal
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.SleepStageRecord
import de.sleeptracker.app.data.db.SoundEventRecord
import de.sleeptracker.core.EPOCH_MILLIS
import de.sleeptracker.core.Epoch
import de.sleeptracker.core.SensorSource
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.SoundEventType
import de.sleeptracker.core.StagedEpoch
import de.sleeptracker.core.TrackingMode
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/** Everything shown in a nightly report. */
data class SessionDetails(
    val session: SleepSession,
    val epochs: List<Epoch>,
    val stages: List<SleepStageRecord>,
    val soundEvents: List<SoundEventRecord>,
    val movements: List<MovementEventRecord>,
    val journal: SleepJournal?,
)

class SleepRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val recordings: RecordingRepository,
) {
    private val sessions = db.sessionDao()
    private val signals = db.signalDao()
    private val journals = db.journalDao()

    fun observeCompleted(): Flow<List<SleepSession>> = sessions.observeCompleted()
    fun observeUnfinished(): Flow<List<SleepSession>> = sessions.observeUnfinished()
    fun observeSession(id: Long): Flow<SleepSession?> = sessions.observe(id)
    fun observeJournal(sessionId: Long): Flow<SleepJournal?> = journals.observe(sessionId)

    suspend fun session(id: Long): SleepSession? = sessions.get(id)
    suspend fun unfinished(): List<SleepSession> = sessions.unfinished()
    suspend fun completedBetween(from: Long, to: Long) = sessions.completedBetween(from, to)
    suspend fun allCompleted() = sessions.allCompleted()
    suspend fun completedBefore(before: Long, limit: Int) = sessions.completedBefore(before, limit)

    suspend fun createSession(
        startMillis: Long,
        mode: TrackingMode,
        source: SensorSource,
        alarmTargetMillis: Long?,
        windowMinutes: Int,
    ): Long {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        // A night belongs to the date of the following morning (sessions started after 6 pm).
        val date = if (start.hour >= 18) start.toLocalDate().plusDays(1) else start.toLocalDate()
        return sessions.insert(
            SleepSession(
                sessionDate = date.toString(),
                startTimestamp = startMillis,
                zoneOffsetMinutes = start.offset.totalSeconds / 60,
                trackingMode = mode,
                sensorSource = source,
                sessionStatus = SessionStatus.ACTIVE,
                alarmTargetMillis = alarmTargetMillis,
                alarmWindowMinutes = windowMinutes,
            ),
        )
    }

    suspend fun updateSession(session: SleepSession) = sessions.upsert(session)

    suspend fun appendEpochs(sessionId: Long, epochs: List<Epoch>) {
        if (epochs.isEmpty()) return
        signals.insertEpochs(epochs.map { it.toRecord(sessionId) })
    }

    suspend fun appendMovements(movements: List<MovementEventRecord>) {
        if (movements.isNotEmpty()) signals.insertMovements(movements)
    }

    suspend fun addSoundEvent(event: SoundEventRecord): Long = signals.insertSoundEvent(event)

    suspend fun epochs(sessionId: Long): List<Epoch> = signals.epochs(sessionId).map { it.toEpoch() }

    suspend fun markInterrupted(sessionId: Long) {
        val s = sessions.get(sessionId) ?: return
        if (s.sessionStatus == SessionStatus.ACTIVE) sessions.upsert(s.copy(sessionStatus = SessionStatus.INTERRUPTED))
    }

    suspend fun markAlarmRang(sessionId: Long, atMillis: Long) {
        val s = sessions.get(sessionId) ?: return
        if (s.alarmRangMillis == null) sessions.upsert(s.copy(alarmRangMillis = atMillis))
    }

    /**
     * Analyses all stored epochs of a session and stores the results. A session is never
     * discarded here: even a very short or empty one is kept as COMPLETED so nothing is lost.
     */
    suspend fun finalizeSession(sessionId: Long, endMillis: Long): SleepSession? {
        val session = sessions.get(sessionId) ?: return null
        val user = settings.user()
        val epochs = epochs(sessionId)
        val end = maxOf(endMillis, session.startTimestamp, (epochs.lastOrNull()?.startMillis ?: 0L) + EPOCH_MILLIS)
            .let { if (session.endTimestamp != null && session.sessionStatus == SessionStatus.COMPLETED) session.endTimestamp else it }
        val summary = SessionAnalyzer(SleepStager()).analyze(epochs, session.startTimestamp, end)
        val previous = sessions.completedBefore(session.startTimestamp, 7)
        val previousBedtimes = previous.map { SleepScoreCalculator.minutesSinceEvening(it.startTimestamp, it.zoneOffsetMinutes) }
        val score = SleepScoreCalculator(user.scoreWeights())
            .score(summary, user.sleepGoalMinutes, previousBedtimes, session.zoneOffsetMinutes)
        val events = signals.soundEvents(sessionId)
        val env = EnvironmentAnalyzer.analyze(epochs, summary.sleepOnsetMillis)
        val updated = session.withEnvironment(env).copy(
            endTimestamp = end,
            timeInBedMinutes = summary.timeInBedMinutes,
            estimatedSleepMinutes = summary.sleepMinutes,
            sleepLatencyMinutes = summary.sleepLatencyMinutes,
            sleepEfficiency = summary.efficiency,
            sleepQualityScore = score.score,
            sessionStatus = SessionStatus.COMPLETED,
            awakeMinutes = summary.awakeMinutes,
            lightMinutes = summary.lightMinutes,
            deepMinutes = summary.deepMinutes,
            remMinutes = summary.remMinutes,
            interruptions = summary.interruptions.size,
            stability = summary.stability,
            movementsPerHour = summary.movementsPerHour,
            snoreEvents = events.count { it.eventType == SoundEventType.SNORE },
            snoreSeconds = events.filter { it.eventType == SoundEventType.SNORE }.sumOf { it.durationSeconds.toDouble() }.toInt(),
            coughEvents = events.count { it.eventType == SoundEventType.COUGH },
            talkEvents = events.count { it.eventType == SoundEventType.TALK },
            noiseEvents = events.count { it.eventType.isNoise },
        )
        db.withTransaction {
            sessions.upsert(updated)
            signals.replaceStages(sessionId, compressStages(sessionId, summary.stages))
        }
        return updated
    }

    /** Fills environment figures for nights recorded before they existed (once, cheap). */
    suspend fun backfillEnvironment() {
        for (s in sessions.allCompleted()) {
            if (s.hasNoiseData || s.hasLightData) continue
            val epochs = epochs(s.id)
            if (epochs.none { it.hasSound || it.hasLight }) continue
            val onset = s.startTimestamp + s.sleepLatencyMinutes * 60_000L
            sessions.upsert(s.withEnvironment(EnvironmentAnalyzer.analyze(epochs, onset)))
        }
    }

    suspend fun details(sessionId: Long): SessionDetails? {
        val s = sessions.get(sessionId) ?: return null
        return SessionDetails(
            session = s,
            epochs = epochs(sessionId),
            stages = signals.stages(sessionId),
            soundEvents = signals.soundEvents(sessionId),
            movements = signals.movements(sessionId),
            journal = journals.get(sessionId),
        )
    }

    suspend fun saveJournal(sessionId: Long, note: String, mood: Int, tags: Set<String>) {
        val existing = journals.get(sessionId)
        journals.upsert(
            (existing ?: SleepJournal(sessionId = sessionId)).copy(
                note = note.take(2000),
                mood = mood.coerceIn(0, 5),
                personalTags = tags.sorted().joinToString(","),
            ),
        )
    }

    suspend fun deleteSession(sessionId: Long, deleteRecordings: Boolean = true) {
        if (deleteRecordings) recordings.deleteForSession(sessionId)
        sessions.delete(sessionId)
    }

    /** Average of up to [count] previous completed nights for report comparison. */
    suspend fun previousAverage(beforeMillis: Long, count: Int = 7): SleepSession? {
        val prev = sessions.completedBefore(beforeMillis, count)
        if (prev.isEmpty()) return null
        fun avg(sel: (SleepSession) -> Number) = prev.sumOf { sel(it).toDouble() } / prev.size
        return prev.first().copy(
            id = -1,
            estimatedSleepMinutes = avg { it.estimatedSleepMinutes }.toInt(),
            timeInBedMinutes = avg { it.timeInBedMinutes }.toInt(),
            sleepQualityScore = avg { it.sleepQualityScore }.toInt(),
            sleepEfficiency = avg { it.sleepEfficiency }.toFloat(),
            sleepLatencyMinutes = avg { it.sleepLatencyMinutes }.toInt(),
            deepMinutes = avg { it.deepMinutes }.toInt(),
            remMinutes = avg { it.remMinutes }.toInt(),
            snoreEvents = avg { it.snoreEvents }.toInt(),
            interruptions = avg { it.interruptions }.toInt(),
        )
    }

    private fun compressStages(sessionId: Long, staged: List<StagedEpoch>): List<SleepStageRecord> {
        val out = ArrayList<SleepStageRecord>()
        var i = 0
        while (i < staged.size) {
            val s = staged[i]
            var j = i
            var confSum = 0f
            while (j < staged.size && staged[j].stage == s.stage) { confSum += staged[j].confidence; j++ }
            out += SleepStageRecord(
                sessionId = sessionId,
                timestamp = s.startMillis,
                durationSeconds = ((j - i) * EPOCH_MILLIS / 1000).toInt(),
                stageType = s.stage,
                confidence = confSum / (j - i),
            )
            i = j
        }
        return out
    }
}

fun SleepSession.withEnvironment(env: EnvironmentSummary) = copy(
    hasNoiseData = env.hasNoise,
    avgNoiseDb = env.avgNoiseDb,
    peakNoiseDb = env.peakNoiseDb,
    loudMinutes = env.loudMinutes,
    hasLightData = env.hasLight,
    avgLux = env.avgLux,
    maxLux = env.maxLux,
    darkPercent = env.darkPercent,
    brightMinutes = env.brightMinutes,
    luxAtOnset = env.luxAtOnset,
)

fun SleepSession.toNightRecord() = NightRecord(
    startMillis = startTimestamp,
    endMillis = endTimestamp ?: startTimestamp,
    zoneOffsetMinutes = zoneOffsetMinutes,
    sleepMinutes = estimatedSleepMinutes,
    timeInBedMinutes = timeInBedMinutes,
    latencyMinutes = sleepLatencyMinutes,
    efficiency = sleepEfficiency,
    score = sleepQualityScore,
    awakeMinutes = awakeMinutes,
    lightMinutes = lightMinutes,
    deepMinutes = deepMinutes,
    remMinutes = remMinutes,
    interruptions = interruptions,
    snoreEvents = snoreEvents,
    snoreSeconds = snoreSeconds,
    coughEvents = coughEvents,
    talkEvents = talkEvents,
    noiseEvents = noiseEvents,
    avgNoiseDb = avgNoiseDb.takeIf { hasNoiseData },
    avgLux = avgLux.takeIf { hasLightData },
    darkPercent = darkPercent.takeIf { hasLightData },
)

fun Epoch.toRecord(sessionId: Long) = SleepEpochRecord(
    sessionId = sessionId,
    startMillis = startMillis,
    motionCount = motionCount,
    motionIntensity = motionIntensity,
    soundActivity = soundActivity,
    soundLevelDb = soundLevelDb,
    soundEventCount = soundEventCount,
    snoreSeconds = snoreSeconds,
    hasMotion = hasMotion,
    hasSound = hasSound,
    soundPeakDb = soundPeakDb,
    lightLux = lightLux,
    lightMaxLux = lightMaxLux,
    hasLight = hasLight,
)

fun SleepEpochRecord.toEpoch() = Epoch(
    startMillis = startMillis,
    motionCount = motionCount,
    motionIntensity = motionIntensity,
    soundActivity = soundActivity,
    soundLevelDb = soundLevelDb,
    soundEventCount = soundEventCount,
    snoreSeconds = snoreSeconds,
    hasMotion = hasMotion,
    hasSound = hasSound,
    soundPeakDb = soundPeakDb,
    lightLux = lightLux,
    lightMaxLux = lightMaxLux,
    hasLight = hasLight,
)
