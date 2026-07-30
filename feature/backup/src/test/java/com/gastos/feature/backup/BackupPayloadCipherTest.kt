package com.gastos.feature.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupPayloadCipherTest {
    @Test
    fun `v2 round trip supports payloads larger than multiple segments`() {
        val dataKey: ByteArray = ByteArray(BackupCrypto.KEY_SIZE_BYTES) { index -> index.toByte() }
        val header: EncryptedBackupHeader = createHeader(BACKUP_FORMAT_VERSION)
        val headerBytes: ByteArray = "authenticated-v2-header".toByteArray()
        val plaintext: ByteArray = ByteArray(3 * 1024 * 1024 + 117) { index -> (index % 251).toByte() }

        val encrypted: ByteArray = encrypt(plaintext, header, headerBytes, dataKey)
        val decrypted: ByteArray = decrypt(encrypted, header, headerBytes, dataKey)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `v2 rejects truncated ciphertext`() {
        val dataKey: ByteArray = ByteArray(BackupCrypto.KEY_SIZE_BYTES) { 7 }
        val header: EncryptedBackupHeader = createHeader(BACKUP_FORMAT_VERSION)
        val headerBytes: ByteArray = "authenticated-v2-header".toByteArray()
        val encrypted: ByteArray = encrypt(ByteArray(2 * 1024 * 1024), header, headerBytes, dataKey)

        assertThrows(Exception::class.java) {
            decrypt(encrypted.copyOf(encrypted.size - 1), header, headerBytes, dataKey)
        }
    }

    @Test
    fun `v2 authenticates the backup header`() {
        val dataKey: ByteArray = ByteArray(BackupCrypto.KEY_SIZE_BYTES) { 11 }
        val header: EncryptedBackupHeader = createHeader(BACKUP_FORMAT_VERSION)
        val headerBytes: ByteArray = "authenticated-v2-header".toByteArray()
        val encrypted: ByteArray = encrypt("datos".toByteArray(), header, headerBytes, dataKey)

        assertThrows(Exception::class.java) {
            decrypt(encrypted, header, "altered-header".toByteArray(), dataKey)
        }
    }

    @Test
    fun `v1 payload remains decryptable`() {
        val dataKey: ByteArray = ByteArray(BackupCrypto.KEY_SIZE_BYTES) { 19 }
        val header: EncryptedBackupHeader = createHeader(
            version = LEGACY_BACKUP_FORMAT_VERSION,
            payloadIv = BackupCrypto.encode(BackupCrypto.randomIv())
        )
        val headerBytes: ByteArray = "authenticated-v1-header".toByteArray()
        val plaintext: ByteArray = "backup heredado".repeat(1_000).toByteArray()

        val encrypted: ByteArray = encrypt(plaintext, header, headerBytes, dataKey)
        val decrypted: ByteArray = decrypt(encrypted, header, headerBytes, dataKey)

        assertArrayEquals(plaintext, decrypted)
    }

    private fun encrypt(
        plaintext: ByteArray,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray
    ): ByteArray {
        val output: ByteArrayOutputStream = ByteArrayOutputStream()
        BackupPayloadCipher.createEncryptingStream(output, header, headerBytes, dataKey).use { encrypted ->
            encrypted.write(plaintext)
        }
        return output.toByteArray()
    }

    private fun decrypt(
        ciphertext: ByteArray,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray
    ): ByteArray {
        if (header.formatVersion == LEGACY_BACKUP_FORMAT_VERSION) {
            val output = ByteArrayOutputStream()
            BackupPayloadCipher.decryptLegacyTo(
                ByteArrayInputStream(ciphertext),
                output,
                header,
                headerBytes,
                dataKey
            )
            return output.toByteArray()
        }
        return BackupPayloadCipher.createDecryptingStream(
            ByteArrayInputStream(ciphertext),
            header,
            headerBytes,
            dataKey
        ).use { it.readBytes() }
    }

    private fun createHeader(version: Int, payloadIv: String? = null): EncryptedBackupHeader =
        EncryptedBackupHeader(
            formatVersion = version,
            createdAt = 1L,
            appVersionName = "test",
            appVersionCode = 1L,
            databaseVersion = 8,
            invoiceCount = 0,
            productCount = 0,
            incomeCount = 0,
            imageCount = 0,
            kdfIterations = BackupCrypto.DEFAULT_KDF_ITERATIONS,
            salt = BackupCrypto.encode(ByteArray(16)),
            keyIv = BackupCrypto.encode(ByteArray(BackupCrypto.IV_SIZE_BYTES)),
            wrappedKey = BackupCrypto.encode(ByteArray(48)),
            payloadIv = payloadIv
        )
}
