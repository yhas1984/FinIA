@file:Suppress("DEPRECATION")

package com.gastos.feature.settings

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.gastos.extension.SafeLog

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
            SafeLog.w(TAG, "EncryptedSharedPreferences corrupto, reseteando", e)
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
        check(prefs.edit().putString(key, value).commit()) {
            "No se pudo guardar el valor cifrado"
        }
    }

    /**
     * Flow que emite el valor actual de [key] y vuelve a emitir cada vez que
     * cambia. Necesario para que los consumidores (p.ej. la API key de
     * Gemini) reaccionen en caliente sin reiniciar la app.
     *
     * El listener funciona también sobre EncryptedSharedPreferences porque
     * ésta delega en un SharedPreferences real que sí notifica cambios.
     */
    fun observeString(key: String, default: String = ""): Flow<String> = callbackFlow {
        trySend(prefs.getString(key, default) ?: default)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey == key) {
                trySend(prefs.getString(key, default) ?: default)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO)

    suspend fun getString(key: String, default: String = ""): String = withContext(Dispatchers.IO) {
        prefs.getString(key, default) ?: default
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(key).commit()) {
            "No se pudo eliminar el valor cifrado"
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().clear().commit()) {
            "No se pudo limpiar el almacenamiento cifrado"
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SecureStorageModule {
    // SecureStorage tiene @Inject constructor + @Singleton, Hilt lo provee
    // automáticamente. Este module queda como placeholder para posibles
    // provides adicionales en el futuro.
}
