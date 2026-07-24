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

    private fun fileFor(uri: Uri): File = File(imageDir, requireNotNull(uri.lastPathSegment))

    companion object {
        private const val DIRECTORY_NAME = "invoice_images"
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
