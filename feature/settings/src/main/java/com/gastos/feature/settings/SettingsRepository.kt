package com.gastos.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "finai_settings")

data class AppSettings(
    val aiEngine: String = "gemini_api",
    val geminiApiKey: String = "",
    val defaultCurrency: String = "EUR",
    val defaultCountry: String = "ES",
    val darkMode: String = "system",
    val showTutorials: Boolean = true,
    val autoBackup: Boolean = false,
    val isPro: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AI_ENGINE = stringPreferencesKey("ai_engine")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val DEFAULT_CURRENCY = stringPreferencesKey("default_currency")
        val DEFAULT_COUNTRY = stringPreferencesKey("default_country")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val SHOW_TUTORIALS = booleanPreferencesKey("show_tutorials")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            aiEngine = preferences[Keys.AI_ENGINE] ?: "gemini_api",
            geminiApiKey = preferences[Keys.GEMINI_API_KEY] ?: "",
            defaultCurrency = preferences[Keys.DEFAULT_CURRENCY] ?: "EUR",
            defaultCountry = preferences[Keys.DEFAULT_COUNTRY] ?: "ES",
            darkMode = preferences[Keys.DARK_MODE] ?: "system",
            showTutorials = preferences[Keys.SHOW_TUTORIALS] ?: true,
            autoBackup = preferences[Keys.AUTO_BACKUP] ?: false
        )
    }

    suspend fun updateAiEngine(engine: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AI_ENGINE] = engine
        }
    }

    suspend fun updateGeminiApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.GEMINI_API_KEY] = apiKey
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
}
