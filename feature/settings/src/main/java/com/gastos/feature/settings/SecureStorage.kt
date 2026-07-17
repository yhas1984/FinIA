package com.gastos.feature.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Almacenamiento cifrado para datos sensibles (API keys, tokens OAuth, etc.)
 *
 * Usa EncryptedSharedPreferences (AndroidX Security) con clave maestra
 * gestionada por Android KeyStore. El cifrado es AES256_GCM y el HMAC
 * SHA-256 para integridad.
 *
 * NUNCA se loggean los valores almacenados.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val PREFS_NAME = "finai_secure_storage"

        // Keys
        const val KEY_GEMINI_API_KEY = "gemini_api_key"

        private const val TAG = "SecureStorage"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Si el almacenamiento cifrado se corrompe (p.ej. migración de
            // AndroidX Security 1.0 → 1.1), se borra y se recrea. Esto
            // implica perder los valores almacenados — el usuario deberá
            // reintroducir la API key de Gemini.
            Log.w(TAG, "EncryptedSharedPreferences corrupto, reseteando", e)
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (_: Exception) {}
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).apply()
    }

    suspend fun getString(key: String, default: String = ""): String = withContext(Dispatchers.IO) {
        prefs.getString(key, default) ?: default
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SecureStorageModule {
    // SecureStorage tiene @Inject constructor + @Singleton, Hilt lo provee
    // automáticamente. Este module queda como placeholder para posibles
    // provides adicionales en el futuro.
}
