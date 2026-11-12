package de.sleeptracker.app.data.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import de.sleeptracker.app.data.crypto.CryptoManager
import de.sleeptracker.app.data.db.AlarmSettings
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.db.MovementEventRecord
import de.sleeptracker.app.data.db.RecordingMetadata
import de.sleeptracker.app.data.db.SleepEpochRecord
import de.sleeptracker.app.data.db.SleepJournal
import de.sleeptracker.app.data.db.SleepSession
import de.sleeptracker.app.data.db.SleepStageRecord
import de.sleeptracker.app.data.db.SoundEventRecord
import de.sleeptracker.app.data.db.UserSettings
import de.sleeptracker.app.data.repo.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupData(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val userSettings: UserSettings? = null,
    val alarmSettings: AlarmSettings? = null,
    val sessions: List<SleepSession> = emptyList(),
    val stages: List<SleepStageRecord> = emptyList(),
    val epochs: List<SleepEpochRecord> = emptyList(),
    val movements: List<MovementEventRecord> = emptyList(),
    val soundEvents: List<SoundEventRecord> = emptyList(),
    val journals: List<SleepJournal> = emptyList(),
    val recordings: List<RecordingMetadata> = emptyList(),
)

/**
 * Local export, backup and restore through the Storage Access Framework. Nothing is uploaded:
 * the user picks the destination file, which may be on the device or a removable medium.
 */
class DataExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val recordings: RecordingRepository,
    private val crypto: CryptoManager,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun collect(includeSignals: Boolean): BackupData {
        val signals = db.signalDao()
        return BackupData(
            exportedAt = System.currentTimeMillis(),
            userSettings = db.settingsDao().getUserSettings(),
            alarmSettings = db.settingsDao().getAlarmSettings(),
            sessions = db.sessionDao().all(),
            stages = signals.allStages(),
            epochs = if (includeSignals) signals.allEpochs() else emptyList(),
            movements = if (includeSignals) signals.allMovements() else emptyList(),
            soundEvents = signals.allSoundEvents(),
            journals = db.journalDao().all(),
            recordings = db.recordingDao().all(),
        )
    }

    suspend fun exportJson(uri: Uri) = withContext(Dispatchers.IO) {
        val data = collect(includeSignals = false)
        write(uri, json.encodeToString(BackupData.serializer(), data).toByteArray())
    }

    suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append(
            "date,start_utc,end_utc,time_in_bed_min,sleep_min,latency_min,efficiency_pct,score,awake_min,light_min,deep_min,rem_min," +
                "interruptions,snore_events,snore_seconds,cough_events,talk_events,noise_events,tracking_mode,sensors,status,note,tags\n",
        )
        val journals = db.journalDao().all().associateBy { it.sessionId }
        for (s in db.sessionDao().all()) {
            val j = journals[s.id]
            sb.append(
                listOf(
                    s.sessionDate,
                    Instant.ofEpochMilli(s.startTimestamp).atOffset(ZoneOffset.UTC).toString(),
                    s.endTimestamp?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toString() } ?: "",
                    s.timeInBedMinutes, s.estimatedSleepMinutes, s.sleepLatencyMinutes,
                    "%.1f".format(java.util.Locale.ROOT, s.sleepEfficiency), s.sleepQualityScore,
                    s.awakeMinutes, s.lightMinutes, s.deepMinutes, s.remMinutes, s.interruptions,
                    s.snoreEvents, s.snoreSeconds, s.coughEvents, s.talkEvents, s.noiseEvents,
                    s.trackingMode, s.sensorSource, s.sessionStatus,
                    csv(j?.note ?: ""), csv(j?.personalTags ?: ""),
                ).joinToString(","),
            ).append('\n')
        }
        write(uri, sb.toString().toByteArray())
    }

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"").replace('\n', ' ') + "\""

    /** ZIP with all data and decrypted excerpts (so it can be restored on another device). */
    suspend fun createBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val data = collect(includeSignals = true)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON))
            zip.write(json.encodeToString(BackupData.serializer(), data).toByteArray())
            zip.closeEntry()
            for (r in data.recordings) {
                val bytes = crypto.readDecrypted(recordings.file(r.filePath)) ?: continue
                zip.putNextEntry(ZipEntry("recordings/" + r.filePath.removeSuffix(".enc")))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Replaces all local data with the backup content. */
    suspend fun restoreBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        var data: BackupData? = null
        val audio = HashMap<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..")) { entry = zip.nextEntry; continue }
                val bytes = readLimited(zip)
                if (name == BACKUP_JSON) data = json.decodeFromString(BackupData.serializer(), bytes.decodeToString())
                else if (name.startsWith("recordings/")) audio[name.removePrefix("recordings/")] = bytes
                entry = zip.nextEntry
            }
        }
        val backup = data ?: error("No backup data found")
        recordings.deleteAll()
        db.clearAllTables() // must not run inside a transaction
        db.withTransaction {
            backup.userSettings?.let { db.settingsDao().upsertUserSettings(it) }
            backup.alarmSettings?.let { db.settingsDao().upsertAlarmSettings(it) }
            db.sessionDao().insertAll(backup.sessions)
            if (backup.stages.isNotEmpty()) db.signalDao().insertStages(backup.stages)
            if (backup.epochs.isNotEmpty()) db.signalDao().insertEpochs(backup.epochs)
            if (backup.movements.isNotEmpty()) db.signalDao().insertMovements(backup.movements)
            if (backup.soundEvents.isNotEmpty()) db.signalDao().insertSoundEvents(backup.soundEvents)
            if (backup.journals.isNotEmpty()) db.journalDao().insertAll(backup.journals)
            val restored = backup.recordings.mapNotNull { r ->
                val wav = audio[r.filePath.removeSuffix(".enc")] ?: return@mapNotNull null
                crypto.writeEncrypted(recordings.file(r.filePath), wav)
                r.copy(fileSize = recordings.file(r.filePath).length())
            }
            if (restored.isNotEmpty()) db.recordingDao().insertAll(restored)
        }
        // Sound events whose audio was not in the backup lose their audio link.
        val available = db.recordingDao().all().map { it.filePath }.toSet()
        backup.soundEvents.mapNotNull { it.audioFilePath }.filter { it !in available }.forEach { db.signalDao().clearAudioPath(it) }
        backup.sessions.size
    }

    private fun readLimited(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            require(total < MAX_ENTRY_BYTES) { "Backup entry too large" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
        out.use { it.write(bytes) }
    }

    /** Deletes every local record, recording and setting. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        recordings.deleteAll()
        db.clearAllTables()
    }

    companion object {
        private const val BACKUP_JSON = "backup.json"
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    }
}
