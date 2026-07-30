package com.gastos.feature.backup

import android.content.Context
import com.gastos.repository.BackupDataRepository
import com.gastos.repository.BackupSettingsProvider
import com.gastos.storage.InvoiceImageStorage
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyBackupRestoreTest {
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    @Test
    fun `service restores a complete v1 archive`() = runTest {
        val cacheDirectory = Files.createTempDirectory("finai-v1-restore").toFile()
        val context: Context = mockk {
            every { cacheDir } returns cacheDirectory
            every { filesDir } returns cacheDirectory
            every { packageName } returns "com.gastos.ingresos"
        }
        val dataRepository: BackupDataRepository = mockk {
            coEvery { replaceAll(any()) } just Runs
        }
        val settingsProvider: BackupSettingsProvider = mockk {
            coEvery { snapshotSettings() } returns com.gastos.repository.RestorableSettings()
            coEvery { restoreSettings(any()) } just Runs
        }
        val imageStorage: InvoiceImageStorage = mockk {
            coEvery { stageRestoreFiles(emptyMap()) } returns emptyMap()
            every { activateRestoreStage() } just Runs
            every { rollbackRestoreStage() } just Runs
            every { finalizeRestoreStage() } just Runs
        }
        val keyStore: BackupKeyStore = mockk {
            every { remember(any(), any()) } just Runs
        }
        val restoreJournal: BackupRestoreJournal = mockk {
            every { write(any(), any()) } just Runs
            every { clear() } just Runs
            every { read() } returns null
        }
        val service = BackupArchiveService(
            context,
            dataRepository,
            settingsProvider,
            imageStorage,
            keyStore,
            restoreJournal
        )
        val password: CharArray = "frase-segura-v1".toCharArray()
        val archive: ByteArray = createLegacyArchive(password.copyOf())

        val result: BackupRestoreResult = service.restore(
            ByteArrayInputStream(archive),
            password.copyOf()
        )

        assertEquals(0, result.restoredImages)
        coVerify(exactly = 1) { dataRepository.replaceAll(match { it.invoices.isEmpty() }) }
        coVerify(exactly = 1) { settingsProvider.restoreSettings(any()) }
        verify(exactly = 1) { keyStore.remember(any(), any()) }
        cacheDirectory.deleteRecursively()
    }

    private fun createLegacyArchive(password: CharArray): ByteArray {
        val material: BackupKeyMaterial = BackupCrypto.createKeyMaterial(password)
        val payloadIv: ByteArray = BackupCrypto.randomIv()
        val header = EncryptedBackupHeader(
            formatVersion = LEGACY_BACKUP_FORMAT_VERSION,
            createdAt = 1L,
            appVersionName = "1.2.0",
            appVersionCode = 7L,
            databaseVersion = 1,
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
        val payload = BackupPayloadDto(
            formatVersion = LEGACY_BACKUP_FORMAT_VERSION,
            createdAt = 1L,
            invoices = emptyList(),
            products = emptyList(),
            incomes = emptyList(),
            fiscalConfigs = emptyList(),
            chatMessages = emptyList(),
            settings = RestorableSettingsDto("", "EUR", "ES", "system")
        )
        val headerBytes: ByteArray = json.encodeToString(header).toByteArray()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            write("FINAIBK1".toByteArray(Charsets.US_ASCII))
            writeInt(headerBytes.size)
            write(headerBytes)
            flush()
        }
        BackupPayloadCipher.createEncryptingStream(output, header, headerBytes, material.dataKey).use { encrypted ->
            ZipOutputStream(encrypted).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(json.encodeToString(payload).toByteArray())
                zip.closeEntry()
            }
        }
        material.dataKey.fill(0)
        password.fill('\u0000')
        return output.toByteArray()
    }
}
