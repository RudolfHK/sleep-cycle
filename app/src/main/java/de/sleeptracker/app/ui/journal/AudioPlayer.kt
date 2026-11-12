package de.sleeptracker.app.ui.journal

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer

/** Plays decrypted WAV bytes straight from memory (no plaintext copy on disk). */
class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(wav: ByteArray, onDone: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setDataSource(ByteDataSource(wav))
        mp.setOnCompletionListener { stop(); onDone() }
        mp.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
        mp.prepare()
        mp.start()
        player = mp
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private class ByteDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
