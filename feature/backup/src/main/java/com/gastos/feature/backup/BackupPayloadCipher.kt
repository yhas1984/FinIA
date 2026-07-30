package com.gastos.feature.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.util.SecretBytes
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.CipherOutputStream

internal object BackupPayloadCipher {
    fun createEncryptingStream(
        output: OutputStream,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray
    ): OutputStream = when (header.formatVersion) {
        LEGACY_BACKUP_FORMAT_VERSION -> CipherOutputStream(
            output,
            BackupCrypto.encryptionCipher(dataKey, requirePayloadIv(header), headerBytes)
        )
        BACKUP_FORMAT_VERSION -> createStreamingCipher(dataKey).newEncryptingStream(output, headerBytes)
        else -> throw IllegalArgumentException("Versión de backup incompatible.")
    }

    fun createDecryptingStream(
        input: InputStream,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray
    ): InputStream {
        require(header.formatVersion == BACKUP_FORMAT_VERSION) { "Versión de backup incompatible." }
        return createStreamingCipher(dataKey).newDecryptingStream(input, headerBytes)
    }

    fun decryptLegacyTo(
        input: InputStream,
        output: OutputStream,
        header: EncryptedBackupHeader,
        headerBytes: ByteArray,
        dataKey: ByteArray,
        checkCancellation: () -> Unit = {}
    ) {
        require(header.formatVersion == LEGACY_BACKUP_FORMAT_VERSION) { "Versión de backup incompatible." }
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(
            false,
            AEADParameters(
                KeyParameter(dataKey),
                GCM_TAG_SIZE_BITS,
                requirePayloadIv(header),
                headerBytes
            )
        )
        val inputBuffer = ByteArray(LEGACY_BUFFER_SIZE)
        val outputBuffer = ByteArray(LEGACY_BUFFER_SIZE)
        try {
            while (true) {
                checkCancellation()
                val count = input.read(inputBuffer)
                if (count < 0) break
                val produced = cipher.processBytes(inputBuffer, 0, count, outputBuffer, 0)
                if (produced > 0) output.write(outputBuffer, 0, produced)
            }
            val finalCount = cipher.doFinal(outputBuffer, 0)
            if (finalCount > 0) output.write(outputBuffer, 0, finalCount)
            output.flush()
        } catch (_: InvalidCipherTextException) {
            throw IllegalArgumentException("El backup está dañado o no pudo autenticarse.")
        } finally {
            inputBuffer.fill(0)
            outputBuffer.fill(0)
        }
    }

    private fun createStreamingCipher(dataKey: ByteArray): StreamingAead {
        val key: AesGcmHkdfStreamingKey = AesGcmHkdfStreamingKey.create(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
            SecretBytes.copyFrom(dataKey, InsecureSecretKeyAccess.get())
        )
        return AesGcmHkdfStreaming.create(key)
    }

    private fun requirePayloadIv(header: EncryptedBackupHeader): ByteArray {
        val encodedIv: String = requireNotNull(header.payloadIv) { "Cabecera de backup no válida." }
        return BackupCrypto.decode(encodedIv)
    }

    private const val GCM_TAG_SIZE_BITS = 128
    private const val LEGACY_BUFFER_SIZE = 64 * 1024
}
