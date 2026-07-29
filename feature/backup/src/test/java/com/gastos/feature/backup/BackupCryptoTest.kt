package com.gastos.feature.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun `password unwraps data key and payload round trips`() {
        val password = "frase-segura-2026".toCharArray()
        val material = BackupCrypto.createKeyMaterial(password)
        val payloadIv = BackupCrypto.randomIv()
        val header = header(material, payloadIv)

        val unwrapped = BackupCrypto.unwrapDataKey(header, password)
        val plain = "contenido financiero cifrado".toByteArray()
        val encrypted = BackupCrypto.encryptionCipher(unwrapped, payloadIv).doFinal(plain)
        val decrypted = BackupCrypto.decryptionCipher(unwrapped, payloadIv).doFinal(encrypted)

        assertArrayEquals(material.dataKey, unwrapped)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun `wrong password cannot unwrap data key`() {
        val material = BackupCrypto.createKeyMaterial("contraseña-correcta".toCharArray())
        val header = header(material, BackupCrypto.randomIv())

        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.unwrapDataKey(header, "contraseña-errónea".toCharArray())
        }

        assertEquals("Contraseña de recuperación incorrecta o backup dañado.", error.message)
    }

    @Test
    fun `tampered authenticated header invalidates encrypted payload`() {
        val material = BackupCrypto.createKeyMaterial("contraseña-correcta".toCharArray())
        val iv = BackupCrypto.randomIv()
        val originalHeader = "cabecera-original".toByteArray()
        val encrypted = BackupCrypto.encryptionCipher(material.dataKey, iv, originalHeader)
            .doFinal("datos".toByteArray())

        assertThrows(Exception::class.java) {
            BackupCrypto.decryptionCipher(material.dataKey, iv, "cabecera-alterada".toByteArray())
                .doFinal(encrypted)
        }
    }

    @Test
    fun `password shorter than eight characters is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.createKeyMaterial("corta".toCharArray())
        }
    }

    private fun header(material: BackupKeyMaterial, payloadIv: ByteArray) = EncryptedBackupHeader(
        formatVersion = BACKUP_FORMAT_VERSION,
        createdAt = 1L,
        appVersionName = "test",
        appVersionCode = 1L,
        databaseVersion = 8,
        invoiceCount = 0,
        productCount = 0,
        incomeCount = 0,
        imageCount = 0,
        kdfIterations = material.iterations,
        salt = BackupCrypto.encode(material.salt),
        keyIv = BackupCrypto.encode(material.keyIv),
        wrappedKey = BackupCrypto.encode(material.wrappedKey),
        payloadIv = BackupCrypto.encode(payloadIv)
    )
}
