package de.sleeptracker.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private val data = ByteArray(5000) { (it * 31).toByte() }

    @Test
    fun roundTripAndWrongPassword() {
        val enc = BackupCrypto.encrypt(data, "correct horse".toCharArray(), iterations = 20_000)
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(data))
        assertArrayEquals(data, BackupCrypto.decrypt(enc, "correct horse".toCharArray()))
        try {
            BackupCrypto.decrypt(enc, "wrong password".toCharArray()); fail()
        } catch (e: BackupCrypto.WrongPasswordException) { /* expected */ }
        // Tampering with the header is detected too.
        val tampered = enc.copyOf().also { it[10] = (it[10] + 1).toByte() }
        try { BackupCrypto.decrypt(tampered, "correct horse".toCharArray()); fail() } catch (e: Exception) { /* expected */ }
    }

    @Test
    fun shortPasswordRejected() {
        try { BackupCrypto.encrypt(data, "short".toCharArray()); fail() } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
