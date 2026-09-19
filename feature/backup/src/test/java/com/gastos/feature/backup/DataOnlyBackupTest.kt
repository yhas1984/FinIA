package com.gastos.feature.backup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.gastos.domain.model.*
import com.gastos.repository.*
import com.gastos.storage.InvoiceImageStorage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class DataOnlyBackupTest {
    private class Data(var value: BackupDataset) : BackupDataRepository {
        var marker: String? = null
        var commits = 0
        override suspend fun snapshot() = value
        override suspend fun replaceAll(dataset: BackupDataset) { value = dataset }
        override suspend fun replaceAllWithRestoreMarker(dataset: BackupDataset, restoreId: String) {
            value = dataset; marker = restoreId; commits++
        }
        override suspend fun committedRestoreId() = marker
        override suspend fun clearRestoreMarker(restoreId: String) { if (marker == restoreId) marker = null }
    }
    private class Settings(var value: RestorableSettings = RestorableSettings()) : BackupSettingsProvider {
        override suspend fun snapshotSettings() = value
        override suspend fun restoreSettings(settings: RestorableSettings) { value = settings }
    }
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("finai-data-backup").toFile()
        val photo = File(root, "original.jpg").apply { writeText("synthetic photograph") }
        val info = PackageInfo().apply { versionName = "test"; versionCode = 1 }
        val manager = mockk<PackageManager> { every { getPackageInfo(any<String>(), any<Int>()) } returns info }
        val context = mockk<Context> {
            every { getString(any()) } returns "Backup error"
            every { cacheDir } returns File(root, "cache").apply { mkdirs() }
            every { filesDir } returns File(root, "files").apply { mkdirs() }
            every { packageName } returns "com.gastos.test"
            every { packageManager } returns manager
        }
        val invoice = Invoice(id = 7, fecha = 1, proveedor = "Synthetic", tipo = InvoiceType.GASTO,
            total = 110.0, ivaPercent = 10.0, imagenUri = "local-photo", driveFileId = "expense-photo",
            driveAccountId = "test-account", driveWebViewLink = "https://drive.google.com/file/d/expense-photo/view")
        val income = Income(id = 7, fecha = 1, concepto = "Synthetic income", monto = 104.0, ivaPercent = 4.0,
            imagenUri = "local-photo", driveFileId = "income-photo", driveAccountId = "test-account")
        val original = BackupDataset(listOf(invoice), listOf(Product(id = 1, invoiceId = 7, descripcion = "Zero tax", precioUnitario = 10.0, ivaPercent = 0.0)),
            listOf(income), listOf(CountryFiscalConfig("ES", "Spain", listOf(21.0, 10.0, 4.0, 0.0))), emptyList())
        val data = Data(original)
        val settings = Settings()
        val storage = mockk<InvoiceImageStorage>(relaxed = true) {
            every { managedFile("local-photo") } returns photo
            every { managedFile(null) } returns null
            coEvery { stageRestoreFiles(any()) } answers { firstArg<Map<String, File>>().mapValues { "restored:${it.key}" } }
        }
        val password = "test-password-123".toCharArray()
        val material = BackupCrypto.createKeyMaterial(password)
        val keyStore = mockk<BackupKeyStore>(relaxed = true) {
            every { requireMaterial() } answers { material.copy(dataKey = material.dataKey.copyOf()) }
        }
        val journal = spyk(BackupRestoreJournal(context))
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed = true)
        val cache = mockk<DriveImageCache> {
            coEvery { resolve(any(), any(), any(), any()) } returns photo
        }
        val service = BackupArchiveService(context, data, settings, storage, keyStore, journal, outbox, cache)
        suspend fun archive(mode: BackupMode = BackupMode.DATA_ONLY): ByteArray = ByteArrayOutputStream().also { service.createArchive(it, mode) }.toByteArray()
        override fun close() { root.deleteRecursively() }
    }

    @Test fun `over 300 MiB of photos cannot block a data-only backup and taxes survive restore`() = runTest {
        Fixture().use { f ->
            RandomAccessFile(f.photo, "rw").use { it.setLength(310L * 1024 * 1024) }
            val archive = f.archive()
            assertEquals(BackupMode.DATA_ONLY, f.service.inspect(archive.inputStream()).mode)
            assertTrue(archive.size < 100_000)
            val result = f.service.restore(archive.inputStream(), f.password.copyOf())
            assertEquals(0, result.restoredImages)
            assertEquals(10.0, f.data.value.invoices.single().ivaPercent, 0.0)
            assertEquals(4.0, f.data.value.incomes.single().ivaPercent, 0.0)
            assertEquals(0.0, f.data.value.products.single().ivaPercent, 0.0)
            assertEquals(f.invoice.documentUuid, f.data.value.invoices.single().documentUuid)
            assertEquals("local-photo", f.data.value.invoices.single().imagenUri)
            assertEquals("income-photo", f.data.value.incomes.single().driveFileId)
            verify(exactly = 0) { f.storage.activateRestoreStage() }
            verify(exactly = 0) { f.storage.finalizeRestoreStage() }
            coVerify(exactly = 0) { f.cache.resolve(any(), any(), any(), any()) }
            coVerify { f.outbox.reconcile(any(), any(), emptyList(), false) }
        }
    }
    @Test fun `restore on a different device preserves remote identity without demanding a photo`() = runTest {
        Fixture().use { f ->
            val archive = f.archive()
            // Same local numeric ID, different UUID: must not reuse that photograph.
            f.data.value = f.original.copy(invoices = listOf(f.invoice.copy(documentUuid = java.util.UUID.randomUUID().toString())), incomes = emptyList())
            f.service.restore(archive.inputStream(), f.password.copyOf())
            assertNull(f.data.value.invoices.single().imagenUri)
            assertEquals("expense-photo", f.data.value.invoices.single().driveFileId)
            assertEquals("test-account", f.data.value.incomes.single().driveAccountId)
        }
    }
    @Test fun `wrong password and truncated archive never commit`() = runTest {
        Fixture().use { f ->
            val archive = f.archive()
            try { f.service.restore(archive.inputStream(), "wrong-password".toCharArray()); fail() } catch (_: Exception) { }
            try { f.service.restore(archive.copyOf(archive.size - 32).inputStream(), f.password.copyOf()); fail() } catch (_: Exception) { }
            assertEquals(0, f.data.commits)
        }
    }
    @Test fun `complete export must obtain all images and keeps their archived percentages`() = runTest {
        Fixture().use { f ->
            val archive = f.archive(BackupMode.COMPLETE)
            assertEquals(2, f.service.inspect(archive.inputStream()).imageCount)
            f.service.restore(archive.inputStream(), f.password.copyOf())
            assertEquals(10.0, f.data.value.invoices.single().ivaPercent, 0.0)
            coVerify(exactly = 2) { f.cache.resolve(any(), any(), any(), any()) }
            coEvery { f.cache.resolve(any(), any(), any(), any()) } throws ImageAccessException("REMOTE_FILE_MISSING")
            try { f.archive(BackupMode.COMPLETE); fail() } catch (_: ImageAccessException) { }
        }
    }
    @Test fun `data-only recovery after every journal phase never swaps or deletes remote images`() = runTest {
        for (phase in RestoreJournalPhase.entries) Fixture().use { f ->
            val archive = f.archive()
            every { f.journal.write(any(), any(), any(), any(), any(), any()) } answers {
                callOriginal()
                if (firstArg<RestoreJournalPhase>() == phase) throw SimulatedCrash()
            }
            try { f.service.restore(archive.inputStream(), f.password.copyOf()); fail() } catch (_: SimulatedCrash) { }
            f.service.recoverInterruptedRestore()
            assertNull(f.journal.read())
            assertEquals(10.0, f.data.value.invoices.single().ivaPercent, 0.0)
            verify(exactly = 0) { f.storage.activateRestoreStage() }
            verify(exactly = 0) { f.storage.rollbackRestoreStage() }
            coVerify(exactly = 0) { f.outbox.reconcile(any(), any(), match { it.isNotEmpty() }, any()) }
        }
    }
    @Test fun `lost pending image remains marked across backups and prevents false complete export`() = runTest {
        Fixture().use { f ->
            f.data.value = f.original.copy(invoices = listOf(f.invoice.copy(imagenUri=null,driveFileId=null,driveSyncError="MISSING_SOURCE")))
            val archive = f.archive()
            f.service.restore(archive.inputStream(),f.password.copyOf())
            assertEquals("MISSING_SOURCE",f.data.value.invoices.single().driveSyncError)
            try { f.archive(BackupMode.COMPLETE); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `insufficient output space cannot report success or modify local records`() = runTest {
        Fixture().use { f ->
            val output = object: java.io.OutputStream() {
                override fun write(value: Int) { throw java.io.IOException("No space left") }
            }
            try { f.service.createArchive(output); fail() } catch (_: java.io.IOException) { }
            assertEquals(0,f.data.commits)
            assertEquals(f.original,f.data.value)
            assertTrue(f.photo.isFile)
            assertTrue(f.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("backup_export_") })
        }
    }
    @Test fun `end to end encrypted data restore opens expense and income images from simulated Drive`() = runTest {
        Fixture().use { f ->
            val archive=f.archive()
            f.data.value=BackupDataset(emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
            val drive=mockk<InvoiceDriveService> { every { activeAccountId() } returns "test-account" }
            coEvery { drive.downloadImage(any(),"test-account",any()) } coAnswers {
                arg<java.io.OutputStream>(2).write(f.photo.readBytes())
            }
            val cache=DriveImageCache(f.context,drive,f.storage)
            val service=BackupArchiveService(f.context,f.data,f.settings,f.storage,f.keyStore,f.journal,f.outbox,cache)
            service.restore(archive.inputStream(),f.password.copyOf())
            coVerify(exactly=0) { drive.downloadImage(any(),any(),any()) }
            val expense=f.data.value.invoices.single()
            val income=f.data.value.incomes.single()
            assertNull(expense.imagenUri); assertNull(income.imagenUri)
            val expensePhoto=cache.resolve(expense.imagenUri,expense.driveFileId,expense.driveAccountId,expense.driveContentHash)
            val incomePhoto=cache.resolve(income.imagenUri,income.driveFileId,income.driveAccountId,income.driveContentHash)
            assertEquals(f.photo.readText(),expensePhoto.readText())
            assertEquals(f.photo.readText(),incomePhoto.readText())
            assertNotEquals(expensePhoto,incomePhoto)
            assertEquals(10.0,expense.ivaPercent,0.0)
            assertEquals(4.0,income.ivaPercent,0.0)
            coVerify(exactly=2) { drive.downloadImage(any(),any(),any()) }
            coVerify(exactly=0) { drive.delete(any(),any()) }
        }
    }
    private class SimulatedCrash : Error()
}
