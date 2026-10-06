package com.gastos.feature.backup

import android.content.Context
import com.gastos.extension.SafeLog
import com.gastos.repository.PremiumStatusProvider
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
import com.google.api.services.drive.model.GeneratedIds
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CloudBackupReliabilityTest {
    private class Fixture {
        val root = Files.createTempDirectory("cloud-backup-test").toFile()
        val context = mockk<Context>(relaxed = true) { every { noBackupFilesDir } returns root }
        val archive = mockk<BackupArchiveService> { every { isPasswordConfigured() } returns true }
        val drive = mockk<Drive>(relaxed = true)
        val remote = mutableMapOf<String, DriveFile>()
        var archives = 0
        var creates = 0
        var loseUploadResponse = false
        var lookupUnavailable = false
        var failMaintenance = false
        val service = spyk(CloudBackupService(context, archive, mockk(relaxed = true),
            mockk<PremiumStatusProvider> { every { isPremium } returns MutableStateFlow(true) }), recordPrivateCalls = true)
        init {
            every { service.accountKey() } returns "account"
            every { service["driveService"]() } returns drive
            every { service["driveService"]("account") } returns drive
            coEvery { archive.createArchive(any<File>(), BackupMode.DATA_ONLY) } coAnswers {
                archives++
                firstArg<File>().writeText("encrypted synthetic $archives")
                preview()
            }
            every { archive.inspect(any()) } answers { preview() }
            every { drive.files().generateIds().setCount(1).setSpace(any()).execute() } answers {
                GeneratedIds().setIds(listOf("id-$archives"))
            }
            every { drive.files().get(any()) } answers {
                val id = firstArg<String>()
                val request = mockk<Drive.Files.Get>(relaxed = true)
                every { request.setFields(any()).execute() } answers {
                    if (lookupUnavailable) throw IOException("lookup unavailable")
                    remote[id] ?: throw GoogleJsonResponseException(HttpResponseException.Builder(404,"missing",HttpHeaders()),null)
                }
                request
            }
            val create = mockk<Drive.Files.Create>(relaxed = true)
            every { drive.files().create(any<DriveFile>(), any<FileContent>()) } answers {
                val metadata = firstArg<DriveFile>()
                assertNotNull(CloudBackupUploadJournal(File(root,"cloud-upload")).read("account"))
                val source = secondArg<FileContent>().file
                remote[metadata.id] = DriveFile().setId(metadata.id).setName(metadata.name)
                    .setAppProperties(metadata.appProperties.toMap())
                    .setMd5Checksum(cloudFileChecksum(source)).setSize(source.length())
                creates++
                create
            }
            every { create.setFields(any()).execute() } answers {
                if (loseUploadResponse) { lookupUnavailable = true; throw IOException("response lost") }
                remote.values.last()
            }
            every { drive.files().list().setSpaces(any()).setQ(any()).setOrderBy(any()).setPageSize(any()).setPageToken(any()).setFields(any()).execute() } answers {
                if (failMaintenance) throw IOException("maintenance unavailable")
                FileList().setFiles(remote.values.toList())
            }
        }
        fun preview() = BackupPreview(archives * 100L,"test",17,archives,0,0,0,BackupMode.DATA_ONLY)
        fun close() { root.deleteRecursively() }
    }

    @Test fun `a manual backup after an edit creates new bytes even within two minutes`() = runTest {
        val f = Fixture()
        try {
            val first = f.service.createBackup()
            val second = f.service.createBackup()
            assertNotEquals(first.fileId, second.fileId)
            assertEquals(100L, first.createdAt)
            assertEquals(200L, second.createdAt)
            assertEquals(2, f.archives)
            assertEquals(2, f.creates)
        } finally { f.close() }
    }

    @Test fun `unknown upload outcome resumes the same file without recreating its snapshot`() = runTest {
        val f = Fixture()
        try {
            f.loseUploadResponse = true
            assertTrue(runCatching { f.service.createBackup() }.isFailure)
            f.lookupUnavailable = false
            f.loseUploadResponse = false
            val recovered = f.service.createBackup()
            assertTrue(recovered.recoveredUpload)
            assertEquals(100L, recovered.createdAt)
            assertEquals(1, f.creates)
            assertEquals(1, f.archives)
            assertNull(CloudBackupUploadJournal(File(f.root,"cloud-upload")).read("account"))
        } finally { f.close() }
    }

    @Test fun `failed retention listing does not invalidate a verified uploaded backup`() = runTest {
        val f = Fixture()
        mockkObject(SafeLog)
        every { SafeLog.w(any(),any(),any()) } returns Unit
        try {
            f.failMaintenance = true
            val saved = f.service.createBackup()
            assertEquals("id-1", saved.fileId)
            assertTrue(saved.maintenancePending)
            assertEquals(100L, saved.createdAt)
        } finally { unmockkObject(SafeLog); f.close() }
    }
}
