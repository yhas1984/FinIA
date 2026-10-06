package com.gastos.feature.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class PendingCloudUpload(val fileId: String, val fileName: String, val md5: String)

/** A reserved binary Drive ID and the exact encrypted bytes survive unknown upload outcomes. */
internal class CloudBackupUploadJournal(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun journal(account: String): File {
        check(directory.isDirectory || directory.mkdirs())
        val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
    fun read(account: String): PendingCloudUpload? = journal(account).takeIf(File::exists)?.let {
        json.decodeFromString<PendingCloudUpload>(it.readText()).also { upload ->
            require(upload.fileId.isNotBlank() && upload.fileName.matches(Regex("cloud_[a-zA-Z0-9-]+\\.finai")))
        }
    }
    fun write(account: String, pending: PendingCloudUpload) {
        val target = journal(account)
        val temp = File(directory, "${target.name}.tmp")
        try {
            temp.outputStream().use { output -> output.write(json.encodeToString(pending).toByteArray()); output.fd.sync() }
            check(temp.renameTo(target)) { "Cannot persist cloud upload" }
        } finally { temp.delete() }
    }
    fun clear(account: String) { check(!journal(account).exists() || journal(account).delete()) }
    fun source(pending: PendingCloudUpload): File = File(directory, pending.fileName)
}

internal fun cloudFileChecksum(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
