package de.sleeptracker.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key in the Android Keystore.
 * File layout: 12-byte IV followed by ciphertext + 16-byte tag.
 */
class CryptoManager(private val alias: String = "sleep_recordings_v1") {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE + 16) { "Encrypted data too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    fun writeEncrypted(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypt(plain))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not write ${file.name}")
        }
    }

    /** Returns null when the file is missing or corrupted. */
    fun readDecrypted(file: File): ByteArray? = try {
        if (file.exists()) decrypt(file.readBytes()) else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
