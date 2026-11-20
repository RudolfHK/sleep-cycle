package de.sleeptracker.importer

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup container, portable between devices.
 *
 * Layout: "SLPENC1" | iterations (int) | salt (16) | iv (12) | AES-256-GCM ciphertext + tag.
 * Key: PBKDF2-HMAC-SHA256(password, salt, iterations). The header is authenticated as AAD.
 */
object BackupCrypto {
    private val MAGIC = "SLPENC1".toByteArray(Charsets.US_ASCII)
    const val DEFAULT_ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 8

    class WrongPasswordException : Exception("Wrong password or damaged file")

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password too short" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + salt.size + iv.size)
            .put(MAGIC).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(data)) { "Not an encrypted backup" }
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val iterations = buf.int
        require(iterations in 10_000..10_000_000) { "Invalid header" }
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        val headerLen = buf.position()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(data, 0, headerLen)
        return try {
            cipher.doFinal(data, headerLen, data.size - headerLen)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
