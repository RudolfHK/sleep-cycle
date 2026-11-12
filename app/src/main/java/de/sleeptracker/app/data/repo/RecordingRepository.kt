package de.sleeptracker.app.data.repo

import android.content.Context
import de.sleeptracker.app.data.crypto.CryptoManager
import de.sleeptracker.app.data.db.AppDatabase
import de.sleeptracker.app.data.db.RecordingMetadata
import de.sleeptracker.audio.WavEncoder
import de.sleeptracker.core.SoundEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encrypted audio excerpts in app-private storage. Excerpts never leave the app: playback
 * decrypts into memory only.
 */
class RecordingRepository(context: Context, private val db: AppDatabase, private val crypto: CryptoManager) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val dao = db.recordingDao()

    val all: Flow<List<RecordingMetadata>> = dao.observeAll()
    val totalBytes: Flow<Long> = dao.observeTotalSize()

    fun file(relativePath: String) = File(dir, relativePath)

    /** Stop saving excerpts when less than this space is left. */
    fun hasStorageForRecordings(): Boolean = dir.usableSpace > MIN_FREE_BYTES

    suspend fun save(
        sessionId: Long,
        soundEventId: Long,
        type: SoundEventType,
        timestamp: Long,
        pcm: ShortArray,
        sampleRate: Int,
        retentionDays: Int,
    ): String? = withContext(Dispatchers.IO) {
        if (!hasStorageForRecordings()) return@withContext null
        val name = "rec_${timestamp}_${type.name.lowercase()}.wav.enc"
        val target = File(dir, name)
        return@withContext try {
            crypto.writeEncrypted(target, WavEncoder.encode(pcm, sampleRate))
            dao.insert(
                RecordingMetadata(
                    sessionId = sessionId,
                    soundEventId = soundEventId,
                    filePath = name,
                    eventType = type,
                    timestamp = timestamp,
                    durationSeconds = pcm.size.toFloat() / sampleRate,
                    fileSize = target.length(),
                    deletionDate = if (retentionDays > 0) timestamp + retentionDays * 86_400_000L else null,
                ),
            )
            name
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Decrypted WAV bytes, or null if missing/corrupted. */
    suspend fun load(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        crypto.readDecrypted(File(dir, relativePath))?.takeIf { WavEncoder.decode(it) != null }
    }

    suspend fun delete(recording: RecordingMetadata) = withContext(Dispatchers.IO) {
        File(dir, recording.filePath).delete()
        dao.delete(recording.id)
        db.signalDao().clearAudioPath(recording.filePath)
    }

    suspend fun deleteByPath(path: String) = withContext(Dispatchers.IO) {
        dao.all().filter { it.filePath == path }.forEach { delete(it) }
        File(dir, path).delete()
        db.signalDao().clearAudioPath(path)
    }

    suspend fun deleteForSession(sessionId: Long) = withContext(Dispatchers.IO) {
        dao.forSession(sessionId).forEach { delete(it) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        dao.deleteAll()
        db.signalDao().clearAllAudioPaths()
    }

    suspend fun deleteExpired(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val expired = dao.expired(now)
        expired.forEach { delete(it) }
        // Remove orphaned files (e.g. from an interrupted write).
        val known = dao.all().map { it.filePath }.toSet()
        dir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
        expired.size
    }

    /** Re-applies a new retention period to existing recordings. */
    suspend fun applyRetention(days: Int) = withContext(Dispatchers.IO) {
        val all = dao.all()
        dao.deleteAll()
        dao.insertAll(all.map { it.copy(deletionDate = if (days > 0) it.timestamp + days * 86_400_000L else null) })
    }

    companion object {
        const val MIN_FREE_BYTES = 100L * 1024 * 1024
    }
}
