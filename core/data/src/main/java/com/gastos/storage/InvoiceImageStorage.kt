package com.gastos.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvoiceImageStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val imageDir = File(context.filesDir, DIRECTORY_NAME)
    private val stagedImageDir = File(context.filesDir, "$DIRECTORY_NAME.restore_new")
    private val oldImageDir = File(context.filesDir, "$DIRECTORY_NAME.restore_old")
    private val cameraDir = File(context.cacheDir, CAMERA_DIRECTORY_NAME)

    suspend fun persist(source: Uri): Uri = withContext(Dispatchers.IO) {
        imageDir.mkdirs()
        val mimeType = context.contentResolver.getType(source).orEmpty()
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: "jpg"
        val destination = File(imageDir, "invoice_${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "No se pudo abrir la imagen seleccionada" }
                destination.outputStream().use(input::copyTo)
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destination
            )
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun isManaged(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return runCatching {
            val file = fileFor(Uri.parse(uri))
            file.parentFile?.canonicalFile == imageDir.canonicalFile
        }.getOrDefault(false)
    }

    fun delete(uri: String?): Boolean {
        if (!isManaged(uri)) return false
        return runCatching { fileFor(Uri.parse(uri)).delete() }.getOrDefault(false)
    }

    fun managedFile(uri: String?): File? {
        if (!isManaged(uri)) return null
        return runCatching { fileFor(Uri.parse(uri)) }
            .getOrNull()
            ?.takeIf(File::isFile)
    }

    suspend fun restoreManagedFiles(files: Map<String, File>): Map<String, String> =
        withContext(Dispatchers.IO) {
            imageDir.mkdirs()
            files.mapValues { (fileName, source) ->
                require(
                    fileName.length in 1..255 &&
                        fileName != "." &&
                        fileName != ".." &&
                        SAFE_FILE_NAME.matches(fileName)
                ) { "Nombre de imagen no válido" }
                val destination = File(imageDir, fileName)
                source.copyTo(destination, overwrite = true)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destination
                ).toString()
            }
        }

    suspend fun stageRestoreFiles(files: Map<String, File>): Map<String, String> = withContext(Dispatchers.IO) {
        discardRestoreStage()
        stagedImageDir.mkdirs()
        files.mapValues { (fileName, source) ->
            require(
                fileName.length in 1..255 &&
                    fileName != "." &&
                    fileName != ".." &&
                    SAFE_FILE_NAME.matches(fileName)
            ) { "Nombre de imagen no válido" }
            val destination = File(stagedImageDir, fileName)
            source.copyTo(destination, overwrite = true)
            managedUriForFileName(fileName)
        }
    }

    fun activateRestoreStage() {
        discardOldRestore()
        if (imageDir.exists() && !imageDir.renameTo(oldImageDir)) {
            throw IllegalStateException("No se pudo preservar las imágenes actuales.")
        }
        if (!stagedImageDir.exists()) {
            throw IllegalStateException("No existe una restauración preparada.")
        }
        if (!stagedImageDir.renameTo(imageDir)) {
            if (oldImageDir.exists()) oldImageDir.renameTo(imageDir)
            throw IllegalStateException("No se pudo activar las imágenes restauradas.")
        }
    }

    fun rollbackRestoreStage() {
        if (imageDir.exists() && imageDir.canonicalPath != stagedImageDir.canonicalPath) {
            imageDir.deleteRecursively()
        }
        if (oldImageDir.exists()) {
            check(oldImageDir.renameTo(imageDir)) { "No se pudo restaurar las imágenes anteriores." }
        }
        discardRestoreStage()
    }

    fun finalizeRestoreStage() {
        discardOldRestore()
        discardRestoreStage()
    }

    fun discardRestoreStage() {
        if (stagedImageDir.exists()) stagedImageDir.deleteRecursively()
    }

    fun managedUriForFileName(fileName: String): String = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(imageDir, fileName)
    ).toString()

    fun hasRestoreSwapPending(): Boolean = oldImageDir.exists() || stagedImageDir.exists()

    fun pruneManagedFiles(keepFileNames: Set<String>) {
        imageDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keepFileNames }
            .forEach(File::delete)
    }

    fun deleteTemporaryCameraCopy(uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val fileName = requireNotNull(uri.lastPathSegment) { "URI sin nombre" }
                .substringAfterLast('/')
            val file = File(cameraDir, fileName)
            file.parentFile?.canonicalFile == cameraDir.canonicalFile && file.exists() && file.delete()
        }.getOrDefault(false)
    }

    private fun fileFor(uri: Uri): File = File(imageDir, requireNotNull(uri.lastPathSegment))

    private fun discardOldRestore() {
        if (oldImageDir.exists()) oldImageDir.deleteRecursively()
    }

    companion object {
        private const val DIRECTORY_NAME = "invoice_images"
        private const val CAMERA_DIRECTORY_NAME = "camera"
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
