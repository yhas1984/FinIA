package com.gastos.feature.backup

import android.content.Context
import com.gastos.repository.PremiumStatusProvider
import com.gastos.extension.SafeLog
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archiveService: BackupArchiveService,
    private val sheetsExportService: SheetsExportService,
    private val premiumStatus: PremiumStatusProvider
) {
    private val backupMutex = Mutex()

    suspend fun createBackup(): CloudBackupInfo = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            requirePremium()
            require(archiveService.isPasswordConfigured()) {
                context.getString(R.string.configure_recovery_password_before_auto_backup)
            }
            val accountKey = accountKey()
            val drive = driveService(accountKey)
            val directory = File(context.noBackupFilesDir, "cloud-upload").apply { check(isDirectory || mkdirs()) }
            val journal = CloudBackupUploadJournal(directory)
            var pending = journal.read(accountKey)
            val recovered = pending != null
            if (pending == null) {
                val source = File(directory, "cloud_${java.util.UUID.randomUUID()}.finai")
                try {
                    archiveService.createArchive(source, BackupMode.DATA_ONLY)
                    val reservedId = runInterruptible { drive.files().generateIds().setCount(1).setSpace("appDataFolder").execute().ids.single() }
                    pending = PendingCloudUpload(reservedId, source.name, cloudFileChecksum(source))
                    journal.write(accountKey, requireNotNull(pending))
                } catch (error: Exception) { source.delete(); throw error }
            }
            val upload = requireNotNull(pending)
            val source = journal.source(upload)
            var remote = getOrNull(drive, upload.fileId)
            if (remote == null) {
                check(source.isFile && cloudFileChecksum(source) == upload.md5) { "CLOUD_UPLOAD_SOURCE_UNAVAILABLE" }
                val preview = source.inputStream().use(archiveService::inspect)
                val metadata = DriveFile().setId(upload.fileId)
                    .setName("finai_backup_${preview.createdAt}.$BACKUP_FILE_EXTENSION")
                    .setParents(Collections.singletonList(APP_DATA_FOLDER)).setMimeType(BACKUP_MIME_TYPE)
                    .setAppProperties(preview.toProperties())
                try {
                    remote = runInterruptible { drive.files().create(metadata, FileContent(BACKUP_MIME_TYPE, source)).setFields(FILE_FIELDS).execute() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // A lost acknowledgement must resolve the reserved ID before any repeat.
                    remote = try { getOrNull(drive, upload.fileId) } catch (_: Exception) { null }
                    if (remote == null) throw failure
                }
            }
            val confirmed = requireNotNull(remote)
            check(confirmed.md5Checksum == upload.md5 && confirmed.appProperties?.get(PROPERTY_KIND) == PROPERTY_VALUE) { "CLOUD_UPLOAD_IDENTITY_MISMATCH" }
            val info = confirmed.toCloudBackupInfo().copy(accountKey = accountKey, recoveredUpload = recovered)
            journal.clear(accountKey)
            source.delete()
            var maintenanceWarning = false
            try { pruneOldBackups(drive) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                maintenanceWarning = true
                SafeLog.w(TAG, "Backup saved; retention maintenance is pending", failure)
            }
            info.copy(maintenancePending = maintenanceWarning)
        }
    }

    private suspend fun getOrNull(drive: Drive, id: String): DriveFile? = try {
        runInterruptible { drive.files().get(id).setFields(FILE_FIELDS).execute() }
    } catch (error: GoogleJsonResponseException) { if (error.statusCode == 404) null else throw error }

    fun accountKey(): String {
        val account = sheetsExportService.getLastSignedInAccount() ?: error("AUTH_REQUIRED")
        return SheetsLinkStore.getAccountPreferenceKey(account.id, account.email)
    }

    suspend fun listBackups(): List<CloudBackupInfo> = withContext(Dispatchers.IO) {
        requirePremium()
        listBackups(driveService())
    }

    suspend fun downloadBackup(fileId: String): File = withContext(Dispatchers.IO) {
        requirePremium()
        require(fileId.isNotBlank())
        cleanupStaleRestoreFiles()
        val destination = File(context.cacheDir, "cloud_restore_${System.nanoTime()}.$BACKUP_FILE_EXTENSION")
        try {
            destination.outputStream().use { output ->
                runInterruptible {
                    driveService().files().get(fileId).executeMediaAndDownloadTo(output)
                }
            }
            destination
        } catch (error: CancellationException) {
            destination.delete()
            throw error
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun cleanupStaleRestoreFiles() {
        val staleBefore: Long = System.currentTimeMillis() - STALE_RESTORE_FILE_AGE_MS
        context.cacheDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(CLOUD_RESTORE_FILE_PREFIX) &&
                    file.lastModified() < staleBefore
            }
            ?.forEach(File::delete)
    }

    suspend fun deleteAllBackups(): Int = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            requirePremium()
            val drive = driveService()
            val backups = listBackups(drive)
            backups.forEach { runInterruptible { drive.files().delete(it.fileId).execute() } }
            backups.size
        }
    }

    private suspend fun listBackups(drive: Drive): List<CloudBackupInfo> = runInterruptible {
        val backups = mutableListOf<CloudBackupInfo>()
        var page: String? = null
        do {
            val result = drive.files().list().setSpaces(APP_DATA_FOLDER)
                .setQ("trashed=false and appProperties has { key='$PROPERTY_KIND' and value='$PROPERTY_VALUE' }")
                .setOrderBy("createdTime desc").setPageSize(100).setPageToken(page)
                .setFields("nextPageToken,files($FILE_FIELDS)").execute()
            backups += result.files.orEmpty().mapNotNull { runCatching { it.toCloudBackupInfo() }.getOrNull() }
            page = result.nextPageToken
        } while (!page.isNullOrBlank())
        backups
    }

    private suspend fun pruneOldBackups(drive: Drive) {
        listBackups(drive).drop(MAX_CLOUD_BACKUPS).forEach { backup ->
            runInterruptible { drive.files().delete(backup.fileId).execute() }
        }
    }

    private fun driveService(): Drive = driveService(null)

    private fun driveService(expectedAccountKey: String?): Drive {
        val account = sheetsExportService.getLastSignedInAccount()
            ?: throw IllegalStateException(context.getString(R.string.connect_google_first))
        check(expectedAccountKey == null || SheetsLinkStore.getAccountPreferenceKey(account.id, account.email) == expectedAccountKey) {
            "WRONG_ACCOUNT"
        }
        require(sheetsExportService.isSignedIn()) {
            context.getString(R.string.reconnect_google_permission_backup)
        }
        val selectedAccount = account.account
            ?: throw IllegalStateException(context.getString(R.string.selected_google_account_unavailable))
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_APPDATA)
        ).setSelectedAccount(selectedAccount)
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("FinAI Backup")
            .build()
    }

    private fun requirePremium() {
        check(premiumStatus.isPremium.value) {
            context.getString(R.string.auto_backup_requires_premium)
        }
    }

    private fun BackupPreview.toProperties(): Map<String, String> = mapOf(
        PROPERTY_KIND to PROPERTY_VALUE,
        "formatVersion" to BACKUP_FORMAT_VERSION.toString(),
        "createdAt" to createdAt.toString(),
        "appVersion" to appVersionName,
        "databaseVersion" to databaseVersion.toString(),
        "invoiceCount" to invoiceCount.toString(),
        "productCount" to productCount.toString(),
        "incomeCount" to incomeCount.toString(),
        "imageCount" to imageCount.toString(),
        "mode" to mode.name
    )

    private fun DriveFile.toCloudBackupInfo(): CloudBackupInfo {
        val properties = appProperties.orEmpty()
        return CloudBackupInfo(
            fileId = requireNotNull(id),
            name = name ?: "FinAI backup",
            createdAt = properties.getValue("createdAt").toLong(),
            sizeBytes = getSize() ?: 0L,
            preview = BackupPreview(
                createdAt = properties.getValue("createdAt").toLong(),
                appVersionName = properties["appVersion"].orEmpty(),
                databaseVersion = properties.getValue("databaseVersion").toInt(),
                invoiceCount = properties.getValue("invoiceCount").toInt(),
                productCount = properties.getValue("productCount").toInt(),
                incomeCount = properties.getValue("incomeCount").toInt(),
                imageCount = properties.getValue("imageCount").toInt(),
                mode = runCatching { BackupMode.valueOf(properties["mode"].orEmpty()) }.getOrDefault(BackupMode.COMPLETE)
            )
        )
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
        const val PROPERTY_KIND = "finaiBackup"
        const val PROPERTY_VALUE = "encrypted-v1"
        const val MAX_CLOUD_BACKUPS = 5
        const val STALE_RESTORE_FILE_AGE_MS = 60 * 60 * 1000L
        const val CLOUD_RESTORE_FILE_PREFIX = "cloud_restore_"
        const val FILE_FIELDS = "id,name,createdTime,size,md5Checksum,appProperties"
        const val TAG = "CloudBackupService"
    }
}
