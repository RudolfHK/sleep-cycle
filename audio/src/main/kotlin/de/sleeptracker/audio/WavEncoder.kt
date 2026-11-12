package de.sleeptracker.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes/decodes 16-bit mono PCM WAV in memory. */
object WavEncoder {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    /** Returns the PCM samples, or null when the data is not a valid 16-bit mono WAV. */
    fun decode(bytes: ByteArray): Pair<ShortArray, Int>? {
        if (bytes.size < 44) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val channels = buf.getShort(22).toInt()
        val sampleRate = buf.getInt(24)
        val bits = buf.getShort(34).toInt()
        if (channels != 1 || bits != 16) return null
        val dataSize = buf.getInt(40).coerceAtMost(bytes.size - 44)
        val out = ShortArray(dataSize / 2)
        buf.position(44)
        for (i in out.indices) out[i] = buf.getShort()
        return out to sampleRate
    }
}
