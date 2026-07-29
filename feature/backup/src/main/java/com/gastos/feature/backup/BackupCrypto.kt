@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

internal data class BackupKeyMaterial(
    val dataKey: ByteArray,
    val salt: ByteArray,
    val keyIv: ByteArray,
    val wrappedKey: ByteArray,
    val iterations: Int
)

internal object BackupCrypto {
    const val KEY_SIZE_BYTES = 32
    const val IV_SIZE_BYTES = 12
    const val DEFAULT_KDF_ITERATIONS = 310_000

    private val random = SecureRandom()

    fun createKeyMaterial(password: CharArray): BackupKeyMaterial {
        require(password.size >= 8) { "La contraseña debe tener al menos 8 caracteres." }
        val dataKey = randomBytes(KEY_SIZE_BYTES)
        val salt = randomBytes(16)
        val keyIv = randomBytes(IV_SIZE_BYTES)
        val passwordKey = derivePasswordKey(password, salt, DEFAULT_KDF_ITERATIONS)
        val wrappedKey = crypt(Cipher.ENCRYPT_MODE, passwordKey, keyIv, dataKey)
        return BackupKeyMaterial(dataKey, salt, keyIv, wrappedKey, DEFAULT_KDF_ITERATIONS)
    }

    fun unwrapDataKey(header: EncryptedBackupHeader, password: CharArray): ByteArray {
        require(password.size >= 8) { "La contraseña debe tener al menos 8 caracteres." }
        val passwordKey = derivePasswordKey(
            password = password,
            salt = decode(header.salt),
            iterations = header.kdfIterations
        )
        return try {
            crypt(
                mode = Cipher.DECRYPT_MODE,
                key = passwordKey,
                iv = decode(header.keyIv),
                value = decode(header.wrappedKey)
            )
        } catch (_: Exception) {
            throw IllegalArgumentException("Contraseña de recuperación incorrecta o backup dañado.")
        }
    }

    fun encryptionCipher(dataKey: ByteArray, iv: ByteArray, authenticatedData: ByteArray? = null): Cipher =
        cipher(Cipher.ENCRYPT_MODE, dataKey, iv).apply {
            authenticatedData?.let(::updateAAD)
        }

    fun decryptionCipher(dataKey: ByteArray, iv: ByteArray, authenticatedData: ByteArray? = null): Cipher =
        cipher(Cipher.DECRYPT_MODE, dataKey, iv).apply {
            authenticatedData?.let(::updateAAD)
        }

    fun randomIv(): ByteArray = randomBytes(IV_SIZE_BYTES)

    fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun derivePasswordKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations in 100_000..2_000_000) { "Parámetros de cifrado no válidos." }
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun crypt(mode: Int, key: ByteArray, iv: ByteArray, value: ByteArray): ByteArray =
        cipher(mode, key, iv).doFinal(value)

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        require(key.size == KEY_SIZE_BYTES) { "Clave de backup no válida." }
        require(iv.size == IV_SIZE_BYTES) { "Vector de cifrado no válido." }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

@Singleton
class BackupKeyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    internal fun isConfigured(): Boolean = prefs.contains(KEY_DATA_KEY)

    internal fun configure(password: CharArray): BackupKeyMaterial {
        val material = BackupCrypto.createKeyMaterial(password)
        save(material)
        return material
    }

    internal fun requireMaterial(): BackupKeyMaterial {
        val dataKey = prefs.getString(KEY_DATA_KEY, null)?.let(BackupCrypto::decode)
            ?: throw IllegalStateException("Configura una contraseña de recuperación primero.")
        val salt = prefs.getString(KEY_SALT, null)?.let(BackupCrypto::decode)
            ?: throw IllegalStateException("Configuración de backup incompleta.")
        val keyIv = prefs.getString(KEY_KEY_IV, null)?.let(BackupCrypto::decode)
            ?: throw IllegalStateException("Configuración de backup incompleta.")
        val wrappedKey = prefs.getString(KEY_WRAPPED_KEY, null)?.let(BackupCrypto::decode)
            ?: throw IllegalStateException("Configuración de backup incompleta.")
        val iterations = prefs.getInt(KEY_ITERATIONS, BackupCrypto.DEFAULT_KDF_ITERATIONS)
        return BackupKeyMaterial(dataKey, salt, keyIv, wrappedKey, iterations)
    }

    internal fun remember(header: EncryptedBackupHeader, dataKey: ByteArray) {
        save(
            BackupKeyMaterial(
                dataKey = dataKey,
                salt = BackupCrypto.decode(header.salt),
                keyIv = BackupCrypto.decode(header.keyIv),
                wrappedKey = BackupCrypto.decode(header.wrappedKey),
                iterations = header.kdfIterations
            )
        )
    }

    internal fun clear() {
        prefs.edit().clear().apply()
    }

    private fun save(material: BackupKeyMaterial) {
        check(
            prefs.edit()
                .putString(KEY_DATA_KEY, BackupCrypto.encode(material.dataKey))
                .putString(KEY_SALT, BackupCrypto.encode(material.salt))
                .putString(KEY_KEY_IV, BackupCrypto.encode(material.keyIv))
                .putString(KEY_WRAPPED_KEY, BackupCrypto.encode(material.wrappedKey))
                .putInt(KEY_ITERATIONS, material.iterations)
                .commit()
        ) { "No se pudo guardar la clave de backup." }
    }

    private companion object {
        const val PREFS_NAME = "finai_backup_keys"
        const val KEY_DATA_KEY = "data_key"
        const val KEY_SALT = "salt"
        const val KEY_KEY_IV = "key_iv"
        const val KEY_WRAPPED_KEY = "wrapped_key"
        const val KEY_ITERATIONS = "iterations"
    }
}
