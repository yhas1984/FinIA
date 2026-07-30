package com.gastos.feature.backup

import android.content.Context
import android.os.Build
import com.gastos.local.database.AppDatabase
import com.gastos.extension.SafeLog
import com.gastos.repository.BackupDataRepository
import com.gastos.repository.BackupSettingsProvider
import com.gastos.repository.RestorableSettings
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupRestoreResult(
    val preview: BackupPreview,
    val restoredImages: Int
)

@Singleton
class BackupArchiveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataRepository: BackupDataRepository,
    private val settingsProvider: BackupSettingsProvider,
    private val imageStorage: InvoiceImageStorage,
    private val keyStore: BackupKeyStore,
    private val restoreJournal: BackupRestoreJournal
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }
    private val archiveMutex: Mutex = Mutex()

    fun isPasswordConfigured(): Boolean = keyStore.isConfigured()

    fun configurePassword(password: CharArray) {
        keyStore.configure(password)
    }

    suspend fun cleanupTemporaryFiles(createdBefore: Long) = withContext(Dispatchers.IO) {
        cleanupRestoreDirectories(createdBefore)
        context.cacheDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith(CLOUD_RESTORE_FILE_PREFIX) &&
                    it.lastModified() < createdBefore
            }
            ?.forEach(File::delete)
    }

    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        val journal = restoreJournal.read() ?: return@withContext
        val phase = runCatching { RestoreJournalPhase.valueOf(journal.phase) }.getOrNull() ?: run {
            restoreJournal.clear()
            return@withContext
        }
        when (phase) {
            RestoreJournalPhase.STAGED -> {
                imageStorage.discardRestoreStage()
                restoreJournal.clear()
            }
            RestoreJournalPhase.IMAGES_SWAPPED,
            RestoreJournalPhase.SETTINGS_RESTORED -> {
                imageStorage.rollbackRestoreStage()
                settingsProvider.restoreSettings(journal.previousSettings.toDomain())
                restoreJournal.clear()
            }
            RestoreJournalPhase.DB_RESTORED -> {
                imageStorage.finalizeRestoreStage()
                restoreJournal.clear()
            }
        }
    }

    suspend fun createArchive(destination: File): BackupPreview = withContext(Dispatchers.IO) {
        archiveMutex.withLock {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output -> createArchiveLocked(output) }
        }
    }

    suspend fun createArchive(output: OutputStream): BackupPreview = withContext(Dispatchers.IO) {
        archiveMutex.withLock { createArchiveLocked(output) }
    }

    private suspend fun createArchiveLocked(output: OutputStream): BackupPreview {
        val material = keyStore.requireMaterial()
        return try {
            val dataset = dataRepository.snapshot()
            val settings = settingsProvider.snapshotSettings()
            val createdAt = System.currentTimeMillis()
            val payload = dataset.toDto(createdAt, settings, imageStorage)
            val imageFiles = payload.imageFileNames.mapNotNull { fileName ->
                val sourceUri = dataset.invoices.firstOrNull {
                    imageStorage.managedFile(it.imagenUri)?.name == fileName
                }?.imagenUri ?: dataset.incomes.firstOrNull {
                    imageStorage.managedFile(it.imagenUri)?.name == fileName
                }?.imagenUri
                imageStorage.managedFile(sourceUri)?.let { fileName to it }
            }.toMap()
            check(imageFiles.keys == payload.imageFileNames) {
                "No se pudieron leer todas las imágenes del backup."
            }
            require(imageFiles.size <= MAX_ENTRIES) { "Hay demasiadas imágenes para crear el backup." }
            require(imageFiles.values.all { it.length() <= MAX_SINGLE_IMAGE_BYTES }) {
                "Una imagen supera el tamaño máximo permitido."
            }
            val payloadBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            require(payloadBytes.size <= MAX_JSON_BYTES) { "Los datos superan el tamaño máximo permitido." }
            require(payloadBytes.size + imageFiles.values.sumOf(File::length) <= MAX_UNCOMPRESSED_BYTES) {
                "El backup supera el tamaño máximo permitido."
            }

            val appVersion = appVersion()
            val header = EncryptedBackupHeader(
                formatVersion = BACKUP_FORMAT_VERSION,
                createdAt = createdAt,
                appVersionName = appVersion.first,
                appVersionCode = appVersion.second,
                databaseVersion = AppDatabase.DATABASE_VERSION,
                invoiceCount = payload.invoices.size,
                productCount = payload.products.size,
                incomeCount = payload.incomes.size,
                imageCount = imageFiles.size,
                kdfIterations = material.iterations,
                salt = BackupCrypto.encode(material.salt),
                keyIv = BackupCrypto.encode(material.keyIv),
                wrappedKey = BackupCrypto.encode(material.wrappedKey)
            )
            val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
            writeHeader(output, headerBytes)
            BackupPayloadCipher.createEncryptingStream(
                output = output,
                header = header,
                headerBytes = headerBytes,
                dataKey = material.dataKey
            ).use { encrypted ->
                ZipOutputStream(BufferedOutputStream(encrypted)).use { zip ->
                    zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
                    zip.write(payloadBytes)
                    zip.closeEntry()
                    imageFiles.forEach { (fileName, file) ->
                        zip.putNextEntry(ZipEntry("$IMAGES_PREFIX$fileName"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            header.toPreview()
        } finally {
            material.dataKey.fill(0)
        }
    }

    fun inspect(input: InputStream): BackupPreview = readHeader(input).header
        .also(::validateHeader)
        .toPreview()

    suspend fun restore(
        input: InputStream,
        password: CharArray,
        beginCommit: () -> Boolean = { true }
    ): BackupRestoreResult =
        withContext(Dispatchers.IO) {
            archiveMutex.withLock {
                restoreLocked(input, password, beginCommit)
            }
        }

    private suspend fun restoreLocked(
        input: InputStream,
        password: CharArray,
        beginCommit: () -> Boolean
    ): BackupRestoreResult {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val parsedHeader = readHeader(buffered)
        val header = parsedHeader.header
        validateHeader(header)
        val dataKey = try {
            BackupCrypto.unwrapDataKey(header, password)
        } catch (error: Exception) {
            password.fill('\u0000')
            throw error
        }
        cleanupRestoreDirectories()
        val stagingDir = File(context.cacheDir, "backup_restore_${System.nanoTime()}")
        check(stagingDir.mkdirs()) { "No se pudo preparar la restauración." }
        return try {
            val restored = readEncryptedPayload(
                input = buffered,
                header = header,
                headerBytes = parsedHeader.bytes,
                dataKey = dataKey,
                stagingDir = stagingDir
            )
            val missingImages = restored.payload.imageFileNames - restored.images.keys
            require(missingImages.isEmpty()) { "El backup no contiene todas las imágenes declaradas." }
            check(beginCommit()) { "Restauración cancelada." }
            val previousSettings = settingsProvider.snapshotSettings()
            val restoredUris = imageStorage.stageRestoreFiles(restored.images)
            restoreJournal.write(RestoreJournalPhase.STAGED, previousSettings)
            withContext(NonCancellable) {
                executeCommittedRestore(
                    header = header,
                    dataKey = dataKey,
                    previousSettings = previousSettings,
                    restored = restored,
                    restoredUris = restoredUris
                )
            }
        } finally {
            stagingDir.deleteRecursively()
            dataKey.fill(0)
            password.fill('\u0000')
        }
    }

    private fun cleanupRestoreDirectories(createdBefore: Long = Long.MAX_VALUE) {
        context.cacheDir.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name.startsWith(RESTORE_DIRECTORY_PREFIX) &&
                    it.lastModified() < createdBefore
            }
            ?.forEach(File::deleteRecursively)
    }

    private suspend fun executeCommittedRestore(
        header: EncryptedBackupHeader,
        dataKey: ByteArray,
        previousSettings: RestorableSettings,
        restored: RestoredArchive,
        restoredUris: Map<String, String>
    ): BackupRestoreResult {
        try {
            imageStorage.activateRestoreStage()
            restoreJournal.write(RestoreJournalPhase.IMAGES_SWAPPED, previousSettings)
            settingsProvider.restoreSettings(restored.payload.settings.toDomain())
            restoreJournal.write(RestoreJournalPhase.SETTINGS_RESTORED, previousSettings)
            dataRepository.replaceAll(restored.payload.toDataset(restoredUris))
            restoreJournal.write(RestoreJournalPhase.DB_RESTORED, previousSettings)
            runCatching { imageStorage.finalizeRestoreStage() }
                .onFailure { SafeLog.w(TAG, "No se pudo limpiar el directorio antiguo tras restaurar", it) }
            restoreJournal.clear()
            runCatching { keyStore.remember(header, dataKey) }
                .onFailure { SafeLog.w(TAG, "No se pudo recordar la clave del backup restaurado", it) }
            return BackupRestoreResult(header.toPreview(), restoredUris.size)
        } catch (error: Exception) {
            val phase = restoreJournal.read()?.phase
            if (phase == RestoreJournalPhase.DB_RESTORED.name) {
                throw error
            }
            runCatching { imageStorage.rollbackRestoreStage() }
            runCatching { settingsProvider.restoreSettings(previousSettings) }
            restoreJournal.clear()
            throw error
        }
    }

    private suspend fun readEncryptedPayload(
        input: InputStream,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray,
        stagingDir: File
    ): RestoredArchive {
        var payload: BackupPayloadDto? = null
        val images = mutableMapOf<String, File>()
        var totalBytes = 0L
        var entryCount = 0
        val legacyPayload = if (header.formatVersion == LEGACY_BACKUP_FORMAT_VERSION) {
            File(stagingDir, LEGACY_PAYLOAD_FILE).also { destination ->
                val restoreContext = currentCoroutineContext()
                destination.outputStream().use { output ->
                    BackupPayloadCipher.decryptLegacyTo(
                        input = input,
                        output = output,
                        header = header,
                        headerBytes = headerBytes,
                        dataKey = dataKey,
                        checkCancellation = restoreContext::ensureActive
                    )
                }
            }
        } else {
            null
        }
        val decrypted = legacyPayload?.inputStream() ?: BackupPayloadCipher.createDecryptingStream(
            input = input,
            header = header,
            headerBytes = headerBytes,
            dataKey = dataKey
        )
        ZipInputStream(BufferedInputStream(decrypted)).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "El backup contiene demasiados archivos." }
                require(!entry.isDirectory) { "El backup contiene una entrada no válida." }
                when {
                    entry.name == PAYLOAD_ENTRY -> {
                        require(payload == null) { "El backup contiene datos duplicados." }
                        val bytes = zip.readLimitedCancellable(MAX_JSON_BYTES)
                        totalBytes += bytes.size
                        payload = json.decodeFromString(bytes.toString(Charsets.UTF_8))
                    }
                    entry.name.startsWith(IMAGES_PREFIX) -> {
                        val fileName = entry.name.removePrefix(IMAGES_PREFIX)
                        require(isSafeImageFileName(fileName)) {
                            "Ruta de imagen no válida en el backup."
                        }
                        val destination = File(stagingDir, fileName)
                        require(fileName !in images) { "El backup contiene imágenes duplicadas." }
                        val copied = destination.outputStream().use { output ->
                            zip.copyLimitedToCancellable(output, MAX_SINGLE_IMAGE_BYTES)
                        }
                        totalBytes += copied
                        images[fileName] = destination
                    }
                    else -> throw IllegalArgumentException("Entrada desconocida en el backup: ${entry.name}")
                }
                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "El backup es demasiado grande." }
                zip.closeEntry()
            }
        }
        val decoded = requireNotNull(payload) { "El backup no contiene datos restaurables." }
        require(decoded.formatVersion == header.formatVersion) { "Versión de backup incompatible." }
        require(images.size == header.imageCount) { "El número de imágenes del backup no coincide." }
        return RestoredArchive(decoded, images)
    }

    private fun writeHeader(output: OutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_HEADER_BYTES)
        DataOutputStream(output).apply {
            write(MAGIC)
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun readHeader(input: InputStream): ParsedHeader {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "El archivo no es un backup de FinAI." }
        val size = data.readInt()
        require(size in 1..MAX_HEADER_BYTES) { "Cabecera de backup no válida." }
        val bytes = ByteArray(size)
        data.readFully(bytes)
        return ParsedHeader(
            header = json.decodeFromString(bytes.toString(Charsets.UTF_8)),
            bytes = bytes
        )
    }

    private fun validateHeader(header: EncryptedBackupHeader) {
        require(header.formatVersion in LEGACY_BACKUP_FORMAT_VERSION..BACKUP_FORMAT_VERSION) {
            "Este backup requiere una versión más reciente de FinAI."
        }
        if (header.formatVersion == LEGACY_BACKUP_FORMAT_VERSION) {
            require(!header.payloadIv.isNullOrBlank()) { "Cabecera de backup no válida." }
        }
        require(header.databaseVersion <= AppDatabase.DATABASE_VERSION) {
            "Este backup requiere una versión más reciente de FinAI."
        }
        require(header.invoiceCount >= 0 && header.productCount >= 0 && header.incomeCount >= 0)
        require(header.imageCount in 0..MAX_ENTRIES)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: "unknown"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return name to code
    }

    private fun EncryptedBackupHeader.toPreview(): BackupPreview = BackupPreview(
        createdAt = createdAt,
        appVersionName = appVersionName,
        databaseVersion = databaseVersion,
        invoiceCount = invoiceCount,
        productCount = productCount,
        incomeCount = incomeCount,
        imageCount = imageCount
    )

    private data class RestoredArchive(
        val payload: BackupPayloadDto,
        val images: Map<String, File>
    )

    private data class ParsedHeader(
        val header: EncryptedBackupHeader,
        val bytes: ByteArray
    )

    private fun isSafeImageFileName(fileName: String): Boolean =
        fileName.length in 1..255 &&
            fileName != "." &&
            fileName != ".." &&
            SAFE_IMAGE_NAME.matches(fileName)

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimitedTo(output, maxBytes)
        return output.toByteArray()
    }

    private suspend fun InputStream.readLimitedCancellable(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimitedToCancellable(output, maxBytes)
        return output.toByteArray()
    }

    private fun InputStream.copyLimitedTo(output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Una entrada del backup supera el tamaño permitido." }
            output.write(buffer, 0, count)
        }
        return total
    }

    private suspend fun InputStream.copyLimitedToCancellable(output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Una entrada del backup supera el tamaño permitido." }
            output.write(buffer, 0, count)
        }
        return total
    }

    private companion object {
        const val TAG = "BackupArchiveService"
        val MAGIC = "FINAIBK1".toByteArray(Charsets.US_ASCII)
        const val PAYLOAD_ENTRY = "backup.json"
        const val IMAGES_PREFIX = "images/"
        const val RESTORE_DIRECTORY_PREFIX = "backup_restore_"
        const val CLOUD_RESTORE_FILE_PREFIX = "cloud_restore_"
        const val LEGACY_PAYLOAD_FILE = ".legacy_payload.zip"
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_JSON_BYTES = 25L * 1024 * 1024
        const val MAX_SINGLE_IMAGE_BYTES = 50L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 300L * 1024 * 1024
        const val MAX_ENTRIES = 2_000
        val SAFE_IMAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
