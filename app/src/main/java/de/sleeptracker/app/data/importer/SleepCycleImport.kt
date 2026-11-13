package de.sleeptracker.app.data.importer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.db.SleepJournal
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.repo.SettingsRepository
import de.sleeptracker.core.SensorSource
import de.sleeptracker.core.SessionStatus
import de.sleeptracker.core.TrackingMode
import de.sleeptracker.importer.ImportedNight
import de.sleeptracker.importer.ParseResult
import de.sleeptracker.importer.SleepCycleParser
import de.sleeptracker.importer.TableReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** What an import would do; shown to the user before anything is written. */
data class ImportPreview(
    val format: ParseResult.Format,
    val rowsInFile: Int,
    val newNights: List<SleepSession>,
    val journals: Map<Long, SleepJournal>,
    val duplicates: Int,
    val invalidRows: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

/** Imports Sleep Cycle CSV/XLSX exports into the local database. Entirely offline. */
class SleepCycleImport(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {

    suspend fun preview(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                require(buf.size() <= TableReader.MAX_BYTES) { "File too large" }
            }
            buf.toByteArray()
        } ?: error("Cannot open file")
        preview(SleepCycleParser.parse(bytes))
    }

    suspend fun preview(parsed: ParseResult, zone: ZoneId = ZoneId.systemDefault()): ImportPreview {
        val calibration = settings.user().noiseCalibrationDb
        val goal = settings.user().sleepGoalMinutes
        val existingStarts = db.sessionDao().all().map { it.startTimestamp }.sorted()
        // A light column that is 0 everywhere means "no sensor", not "pitch dark".
        val lightMeasured = parsed.nights.any { (it.lightLux ?: 0f) > 0f }

        val accepted = ArrayList<SleepSession>()
        val journals = HashMap<Long, SleepJournal>()
        var duplicates = 0
        for (n in parsed.nights) {
            val session = toSession(n, zone, calibration, goal, lightMeasured)
            val start = session.startTimestamp
            val dupExisting = existingStarts.any { kotlin.math.abs(it - start) <= DUPLICATE_TOLERANCE_MILLIS }
            val dupInFile = accepted.any { kotlin.math.abs(it.startTimestamp - start) <= DUPLICATE_TOLERANCE_MILLIS }
            if (dupExisting || dupInFile) { duplicates++; continue }
            accepted += session
            toJournal(n)?.let { journals[start] = it }
        }
        val dates = accepted.mapNotNull { runCatching { LocalDate.parse(it.sessionDate) }.getOrNull() }
        return ImportPreview(
            format = parsed.format,
            rowsInFile = parsed.rowCount,
            newNights = accepted,
            journals = journals,
            duplicates = duplicates,
            invalidRows = parsed.problems.size,
            firstDate = dates.minOrNull(),
            lastDate = dates.maxOrNull(),
        )
    }

    /** Writes the previewed nights in one transaction. @return number of nights imported. */
    suspend fun commit(preview: ImportPreview): Int = db.withTransaction {
        var count = 0
        for (s in preview.newNights) {
            val id = db.sessionDao().insert(s)
            preview.journals[s.startTimestamp]?.let { db.journalDao().upsert(it.copy(sessionId = id)) }
            count++
        }
        db.statisticsDao().clear()
        count
    }

    private fun toSession(n: ImportedNight, zone: ZoneId, calibration: Int, goalMinutes: Int, lightMeasured: Boolean): SleepSession {
        val start = n.start.atZone(zone)
        val end = n.end.atZone(zone)
        val inBedMin = (n.timeInBedSeconds / 60).toInt()
        val latencyMin = ((n.asleepAfterSeconds ?: 0) / 60).toInt().coerceAtMost(inBedMin)
        val asleepMin = ((n.timeAsleepSeconds ?: ((n.timeInBedSeconds - (n.asleepAfterSeconds ?: 0)).coerceAtLeast(0))) / 60)
            .toInt().coerceIn(0, inBedMin)
        val efficiency = if (inBedMin > 0) asleepMin * 100f / inBedMin else 0f
        // Sleep Cycle's own quality is used as the score. Without it, a simple duration/efficiency estimate.
        val score = n.qualityPercent?.coerceIn(1, 100)
            ?: ((asleepMin.toFloat() / goalMinutes).coerceAtMost(1f) * 60f + efficiency * 0.4f).roundToInt().coerceIn(1, 100)
        val snoreSeconds = if (n.didSnore == false) 0 else (n.snoreSeconds ?: 0).toInt()
        val windowMinutes = if (n.windowStart != null && n.windowStop != null) {
            Duration.between(n.windowStart, n.windowStop).toMinutes().toInt().coerceIn(0, 180)
        } else 0
        return SleepSession(
            sessionDate = end.toLocalDate().toString(),
            startTimestamp = start.toInstant().toEpochMilli(),
            endTimestamp = end.toInstant().toEpochMilli(),
            zoneOffsetMinutes = start.offset.totalSeconds / 60,
            timeInBedMinutes = inBedMin,
            estimatedSleepMinutes = asleepMin,
            sleepLatencyMinutes = latencyMin,
            sleepEfficiency = efficiency,
            sleepQualityScore = score,
            trackingMode = if (n.windowStop != null) TrackingMode.SMART_ALARM else TrackingMode.TRACKING_ONLY,
            sensorSource = SensorSource.SOUND,
            sessionStatus = SessionStatus.COMPLETED,
            awakeMinutes = inBedMin - asleepMin,
            movementsPerHour = n.movementsPerHour ?: 0f,
            // Our snore events are single snores (~one per breath, ≈ 4 s): estimated from snore time.
            snoreEvents = (snoreSeconds / 4.0).roundToInt(),
            snoreSeconds = snoreSeconds,
            coughEvents = ((n.coughsPerHour ?: 0f) * asleepMin / 60f).roundToInt(),
            alarmTargetMillis = n.windowStop?.atZone(zone)?.toInstant()?.toEpochMilli(),
            alarmWindowMinutes = windowMinutes,
            hasNoiseData = n.noiseDb != null,
            avgNoiseDb = n.noiseDb?.let { it - calibration } ?: -100f,
            hasLightData = lightMeasured && n.lightLux != null,
            avgLux = if (lightMeasured) n.lightLux ?: -1f else -1f,
            maxLux = -1f,
            darkPercent = -1f,
            source = SleepSession.SOURCE_SLEEP_CYCLE,
            breathingDisruptionsPerHour = n.breathingDisruptionsPerHour ?: -1f,
            externalRegularity = n.regularityPercent ?: -1,
        )
    }

    private fun toJournal(n: ImportedNight): SleepJournal? {
        if (n.notes.isEmpty() && n.mood == null && n.heartRateBpm == null && n.steps == null) return null
        val tags = n.notes.mapNotNull(::tagFor).toSet()
        val free = n.notes.filter { tagFor(it) == null }.toMutableList()
        n.heartRateBpm?.let { free += "Heart rate %.0f bpm".format(it) }
        n.steps?.let { free += "$it steps" }
        return SleepJournal(sessionId = 0, note = free.joinToString(", "), mood = n.mood ?: 0, personalTags = tags.sorted().joinToString(","))
    }

    companion object {
        const val DUPLICATE_TOLERANCE_MILLIS = 5 * 60_000L

        /** Maps Sleep Cycle sleep-note texts (English/German) onto this app's journal tags. */
        fun tagFor(note: String): String? {
            val t = note.lowercase()
            return when {
                "coffee" in t || "caffeine" in t || "kaffee" in t || "koffein" in t -> "coffee"
                "alcohol" in t || "alkohol" in t || "drank" in t && "coffee" !in t || "wine" in t || "beer" in t -> "alcohol"
                "stress" in t -> "stress"
                "work" in t && "out" in t || "exercise" in t || "sport" in t || "training" in t -> "exercise"
                "ate late" in t || "late meal" in t || "spät gegessen" in t || "spätes essen" in t -> "late_meal"
                "travel" in t || "reise" in t || "jet" in t -> "travel"
                "sick" in t || Regex("\\bill\\b|illness").containsMatchIn(t) || "krank" in t || "erkält" in t -> "illness"
                "new place" in t || "hotel" in t || "unusual" in t || "fremd" in t -> "unusual_place"
                else -> null
            }
        }
    }
}
