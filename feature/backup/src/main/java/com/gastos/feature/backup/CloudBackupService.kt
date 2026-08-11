package com.gastos.feature.backup

import android.content.Context
import com.gastos.repository.PremiumStatusProvider
import com.gastos.extension.SafeLog
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
            val drive = driveService()
            listBackups(drive).firstOrNull()?.takeIf {
                System.currentTimeMillis() - it.createdAt < MIN_BACKUP_INTERVAL_MS
            }?.let { return@withLock it }
            val temporary = File(context.cacheDir, "cloud_backup_${System.nanoTime()}.$BACKUP_FILE_EXTENSION")
            try {
                val preview = archiveService.createArchive(temporary)
                val name = "finai_backup_${preview.createdAt}.$BACKUP_FILE_EXTENSION"
                val metadata = DriveFile()
                    .setName(name)
                    .setParents(Collections.singletonList(APP_DATA_FOLDER))
                    .setMimeType(BACKUP_MIME_TYPE)
                    .setAppProperties(preview.toProperties())
                val uploaded = runInterruptible {
                    drive.files()
                        .create(metadata, FileContent(BACKUP_MIME_TYPE, temporary))
                        .setFields(FILE_FIELDS)
                        .execute()
                }
                pruneOldBackups(drive)
                uploaded.toCloudBackupInfo()
            } finally {
                temporary.delete()
            }
        }
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
        drive.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ("trashed=false and appProperties has { key='$PROPERTY_KIND' and value='$PROPERTY_VALUE' }")
            .setOrderBy("createdTime desc")
            .setPageSize(100)
            .setFields("files($FILE_FIELDS)")
            .execute()
            .files
            .orEmpty()
            .mapNotNull { runCatching { it.toCloudBackupInfo() }.getOrNull() }
    }

    private suspend fun pruneOldBackups(drive: Drive) {
        listBackups(drive).drop(MAX_CLOUD_BACKUPS).forEach { backup ->
            runCatching { runInterruptible { drive.files().delete(backup.fileId).execute() } }
                .onFailure { SafeLog.w(TAG, "No se pudo retirar un backup antiguo", it) }
        }
    }

    private fun driveService(): Drive {
        val account = sheetsExportService.getLastSignedInAccount()
            ?: throw IllegalStateException(context.getString(R.string.connect_google_first))
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
        "imageCount" to imageCount.toString()
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
                imageCount = properties.getValue("imageCount").toInt()
            )
        )
    }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
        const val PROPERTY_KIND = "finaiBackup"
        const val PROPERTY_VALUE = "encrypted-v1"
        const val MAX_CLOUD_BACKUPS = 5
        const val MIN_BACKUP_INTERVAL_MS = 2 * 60 * 1000L
        const val STALE_RESTORE_FILE_AGE_MS = 60 * 60 * 1000L
        const val CLOUD_RESTORE_FILE_PREFIX = "cloud_restore_"
        const val FILE_FIELDS = "id,name,createdTime,size,appProperties"
        const val TAG = "CloudBackupService"
    }
}
