package com.gastos.feature.backup

import android.content.Context
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveImageCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: InvoiceDriveService,
    private val imageStorage: InvoiceImageStorage
) {
    private val mutex = Mutex()
    private val directory get() = File(context.cacheDir, "drive_images").apply { mkdirs() }

    suspend fun resolve(localUri: String?, fileId: String?, accountId: String?, contentHash: String?): File = withContext(Dispatchers.IO) {
        imageStorage.managedFile(localUri)?.let { return@withContext it }
        if (fileId.isNullOrBlank()) throw ImageAccessException("MISSING_SOURCE")
        val activeAccount = drive.activeAccountId() ?: throw ImageAccessException("AUTH_REQUIRED")
        if (accountId != null && activeAccount != accountId) throw ImageAccessException("WRONG_ACCOUNT")
        mutex.withLock {
            val key = MessageDigest.getInstance("SHA-256").digest("$activeAccount\u0000$fileId".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(directory, key)
            if (target.isFile) { target.setLastModified(System.currentTimeMillis()); return@withLock target }
            val temporary = File(directory, "$key.part")
            try {
                temporary.outputStream().use { output ->
                    drive.downloadImage(fileId, activeAccount, LimitedImageOutput(output, MAX_IMAGE_BYTES))
                }
                if (contentHash != null) {
                    val digest = MessageDigest.getInstance("MD5")
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    if (digest.digest().joinToString("") { "%02x".format(it) } != contentHash)
                        throw ImageAccessException("IDENTITY_REVIEW_REQUIRED")
                }
                check(temporary.renameTo(target)) { "CACHE_WRITE_FAILED" }
                target.setLastModified(System.currentTimeMillis())
                trim(target)
                target
            } finally { temporary.delete() }
        }
    }

    private fun trim(protected: File) {
        // Only downloaded copies live here. Original pending images have a separate directory.
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var bytes = files.sumOf(File::length)
        for (file in files) if (bytes > MAX_CACHE_BYTES && file != protected) {
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    companion object {
        const val MAX_CACHE_BYTES = 100L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    }
}

private class LimitedImageOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var bytes = 0L
    override fun write(value: Int) { checkSize(1); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { checkSize(length); out.write(buffer, offset, length) }
    private fun checkSize(count: Int) {
        bytes += count
        if (bytes > limit) throw ImageAccessException("IMAGE_TOO_LARGE")
    }
}
