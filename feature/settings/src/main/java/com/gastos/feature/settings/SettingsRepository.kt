package com.gastos.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "finai_settings")

data class AppSettings(
    val geminiApiKey: String = "",
    val systemInstructions: String = "",
    val sheetId: String = "",
    val defaultCurrency: String = "EUR",
    val defaultCountry: String = "ES",
    val darkMode: String = "system",
    val showTutorials: Boolean = true,
    val autoBackup: Boolean = false,
    val isPro: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    private object Keys {
        val SYSTEM_INSTRUCTIONS = stringPreferencesKey("system_instructions")
        val SHEET_ID = stringPreferencesKey("sheet_id")
        val DEFAULT_CURRENCY = stringPreferencesKey("default_currency")
        val DEFAULT_COUNTRY = stringPreferencesKey("default_country")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SHOW_TUTORIALS = booleanPreferencesKey("show_tutorials")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
    }

    /**
     * Flujo combinado: datos no sensibles de DataStore + datos sensibles
     * de EncryptedSharedPreferences (SecureStorage).
     *
     * La API key se observa con [SecureStorage.observeString] (NO con un
     * flow one-shot) para que al guardarla se re-emita al instante y el
     * AIService se reconfigure sin reiniciar la app.
     */
    val settings: Flow<AppSettings> = combine(
        context.dataStore.data,
        secureStorage.observeString(SecureStorage.KEY_GEMINI_API_KEY)
    ) { preferences, geminiKey ->
        AppSettings(
            geminiApiKey = geminiKey,
            systemInstructions = preferences[Keys.SYSTEM_INSTRUCTIONS] ?: "",
            sheetId = preferences[Keys.SHEET_ID] ?: "",
            defaultCurrency = preferences[Keys.DEFAULT_CURRENCY] ?: "EUR",
            defaultCountry = preferences[Keys.DEFAULT_COUNTRY] ?: "ES",
            darkMode = preferences[Keys.DARK_MODE] ?: "system",
            showTutorials = preferences[Keys.SHOW_TUTORIALS] ?: true,
            autoBackup = preferences[Keys.AUTO_BACKUP] ?: false
        )
    }

    suspend fun updateGeminiApiKey(apiKey: String) {
        secureStorage.putString(SecureStorage.KEY_GEMINI_API_KEY, apiKey)
    }

    suspend fun updateSystemInstructions(instructions: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SYSTEM_INSTRUCTIONS] = instructions
        }
    }

    suspend fun updateDefaultCurrency(currency: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DEFAULT_CURRENCY] = currency
        }
    }

    suspend fun updateDefaultCountry(country: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DEFAULT_COUNTRY] = country
        }
    }

    suspend fun updateDarkMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DARK_MODE] = mode
        }
    }

    suspend fun updateShowTutorials(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SHOW_TUTORIALS] = show
        }
    }

    suspend fun updateAutoBackup(autoBackup: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTO_BACKUP] = autoBackup
        }
    }

    suspend fun updateSheetId(sheetId: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SHEET_ID] = sheetId
        }
    }
}
