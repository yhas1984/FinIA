@file:Suppress("DEPRECATION")
package com.gastos.di

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.feature.backup.BackupArchiveService
import com.gastos.feature.backup.BackupKeyStore
import com.gastos.feature.backup.BackupMode
import com.gastos.feature.backup.BackupRestoreJournal
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpExecuteInterceptor
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.google.api.services.drive.model.File as DriveFile

/**
 * Opt-in only. Creates one current encrypted data backup in the connected test account.
 * No key changes, Room writes, restore, retention, workbook creation, or remote deletion.
 * This validates production archive creation and real Drive round-trip, not CloudBackupService's
 * upload journal/retention. That method cannot guarantee no retention deletions in a live account.
 */
@RunWith(AndroidJUnit4::class)
class LiveCloudBackupSafeTest {
    @Test fun createCurrentDataCopyWithoutRetentionOrRestore(): Unit = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit -e liveSafeCloudBackup true", arguments.getString("liveSafeCloudBackup") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev")) { "Only FinAI Dev is allowed" }
        val expectedName = requireNotNull(arguments.getString("expectedWorkbookName")) {
            "Pin the existing test workbook with expectedWorkbookName"
        }
        check(expectedName.isNotBlank())
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        assumeTrue("Google account and permissions must already be configured", app.sheets().isSignedIn())
        assumeTrue("Existing Premium authorization required; test does not alter it", app.premium().isPremium.value)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val accountId = account.id ?: account.email ?: error("Google identity missing")
        val expectedBook = app.sync().getStoredId(account)
        check(expectedBook.isNotBlank()) { "An existing workbook must already be linked" }
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_APPDATA))
            .setSelectedAccount(requireNotNull(account.account))
        val initializer = HttpRequestInitializer { request ->
            credential.initialize(request)
            val authentication = request.interceptor
            request.interceptor = HttpExecuteInterceptor { outgoing ->
                check(outgoing.requestMethod == "GET" || outgoing.requestMethod == "POST") {
                    "This test forbids deletion and updates of remote files"
                }
                authentication?.intercept(outgoing)
            }
            request.connectTimeout = 15_000
            request.readTimeout = 60_000
            request.numberOfRetries = 0
        }
        val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName("FinAI QA safe backup").build()
        val linked = drive.files().get(expectedBook).setFields("id,name,mimeType,trashed").execute()
        check(linked.name == expectedName && linked.trashed != true &&
            linked.mimeType == "application/vnd.google-apps.spreadsheet") { "The linked workbook must match the explicitly approved name" }
        val archive = BackupArchiveService(context, app.snapshots(), app.settings(), app.images(),
            BackupKeyStore(context), BackupRestoreJournal(context), app.outbox(), app.cache())
        assumeTrue("Configure a recovery password in FinAI Dev first; this test never changes it", archive.isPasswordConfigured())
        fun ids(space: String, query: String): Set<String> {
            val result = mutableSetOf<String>()
            var token: String? = null
            do {
                val page = drive.files().list().setSpaces(space).setQ(query).setPageToken(token)
                    .setFields("nextPageToken,files(id)").execute()
                result += page.files.orEmpty().map { it.id }
                token = page.nextPageToken
            } while (!token.isNullOrBlank())
            return result
        }
        fun checksum(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val workbookQuery = "trashed=false and mimeType='application/vnd.google-apps.spreadsheet'"
        val backupQuery = "trashed=false and appProperties has { key='finaiBackup' and value='encrypted-v1' }"
        val workbooksBefore = ids("drive", workbookQuery)
        val backupsBefore = ids("appDataFolder", backupQuery)
        val datasetBefore = app.snapshots().snapshot()
        val temporary = File(context.noBackupFilesDir, "qa-safe-cloud-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val report = JSONObject().put("passed", false).put("restoreExecuted", false).put("retentionExecuted", false)
        val reportFile = File(context.filesDir, "cloud-backup-safe-validation.json")
        val start = SystemClock.elapsedRealtime()
        try {
            val source = File(temporary, "current.finai")
            val createdAfter = System.currentTimeMillis()
            val preview = archive.createArchive(source, BackupMode.DATA_ONLY)
            check(preview.createdAt >= createdAfter) { "Must capture a fresh backup" }
            assertEquals(BackupMode.DATA_ONLY, preview.mode)
            assertEquals(0, preview.imageCount)
            assertEquals(datasetBefore.invoices.size, preview.invoiceCount)
            assertEquals(datasetBefore.incomes.size, preview.incomeCount)
            assertEquals(datasetBefore.products.size, preview.productCount)
            val currentAccount = requireNotNull(app.sheets().getLastSignedInAccount())
            check((currentAccount.id ?: currentAccount.email) == accountId && app.sync().getStoredId(currentAccount) == expectedBook) {
                "Account or workbook changed; no upload authorized"
            }
            val id = drive.files().generateIds().setCount(1).setSpace("appDataFolder").execute().ids.single()
            report.put("reservedFileId", id).put("archiveBytes", source.length()).put("createdAt", preview.createdAt)
            reportFile.writeText(report.toString(2))
            val metadata = DriveFile().setId(id).setName("finai_backup_${preview.createdAt}.finai")
                .setParents(listOf("appDataFolder")).setMimeType("application/vnd.finai.backup")
                .setAppProperties(mapOf("finaiBackup" to "encrypted-v1", "createdAt" to preview.createdAt.toString(),
                    "appVersion" to preview.appVersionName, "databaseVersion" to preview.databaseVersion.toString(),
                    "invoiceCount" to preview.invoiceCount.toString(), "incomeCount" to preview.incomeCount.toString(),
                    "productCount" to preview.productCount.toString(), "imageCount" to "0", "mode" to BackupMode.DATA_ONLY.name))
            val upload = drive.files().create(metadata, FileContent("application/vnd.finai.backup", source))
                .setFields("id,size,md5Checksum,appProperties")
            // A single POST avoids resumable PUT; this test deliberately permits no remote updates.
            upload.mediaHttpUploader.isDirectUploadEnabled = true
            val uploaded = upload.execute()
            assertEquals(id, uploaded.id)
            assertFalse("A current copy must not reuse an earlier backup", id in backupsBefore)
            assertEquals(source.length(), uploaded.getSize())
            assertEquals(checksum(source), uploaded.md5Checksum)
            val downloaded = File(temporary, "downloaded.finai")
            downloaded.outputStream().use { drive.files().get(id).executeMediaAndDownloadTo(it) }
            assertEquals(source.length(), downloaded.length())
            assertEquals(checksum(source), checksum(downloaded))
            assertEquals(preview, downloaded.inputStream().use(archive::inspect))
            val backupsAfter = ids("appDataFolder", backupQuery)
            assertTrue("Every previous remote backup remains", backupsAfter.containsAll(backupsBefore))
            assertTrue("The new backup appears in appDataFolder", id in backupsAfter)
            assertEquals("No additional workbook", workbooksBefore, ids("drive", workbookQuery))
            assertEquals("Workbook link preserved", expectedBook, app.sync().getStoredId(account))
            assertTrue("Local data changed concurrently; review without restoring it", datasetBefore == app.snapshots().snapshot())
            report.put("passed", true).put("checksumVerified", true).put("sameWorkbook", true)
                .put("previousBackupsPreserved", true).put("localDatasetUnchanged", true)
                .put("uploadedFileId", id).put("durationMs", SystemClock.elapsedRealtime() - start)
        } finally {
            reportFile.writeText(report.toString(2))
            temporary.deleteRecursively() // Only this test's private temporary byte copies.
        }
    }
}
