package com.gastos.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.BackupSettingsProvider
import com.gastos.repository.DashboardLayout
import com.gastos.repository.DashboardLayoutPreference
import com.gastos.repository.FloatingButtonEdge
import com.gastos.repository.FloatingButtonIds
import com.gastos.repository.FloatingButtonPosition
import com.gastos.repository.FloatingButtonPositionPreference
import com.gastos.repository.RestorableSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "finai_settings")

data class AppSettings(
    val geminiApiKey: String = "",
    val systemInstructions: String = "",
    val defaultCurrency: String = "EUR",
    val defaultCountry: String = "ES",
    val darkMode: String = "system",
    val isPro: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) : CurrencyPreference,
    BackupSettingsProvider,
    DashboardLayoutPreference,
    FloatingButtonPositionPreference {

    // Scope propio para stateIn (el repo es un @Singleton a nivel app).
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val SYSTEM_INSTRUCTIONS = stringPreferencesKey("system_instructions")
        val DEFAULT_CURRENCY = stringPreferencesKey("default_currency")
        val DEFAULT_COUNTRY = stringPreferencesKey("default_country")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val DASHBOARD_WIDGET_ORDER = stringPreferencesKey("dashboard_widget_order")
        val DASHBOARD_HIDDEN_WIDGETS = stringPreferencesKey("dashboard_hidden_widgets")
        val FLOATING_BUTTON_POSITIONS = stringPreferencesKey("floating_button_positions")
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
            defaultCurrency = preferences[Keys.DEFAULT_CURRENCY] ?: "EUR",
            defaultCountry = preferences[Keys.DEFAULT_COUNTRY] ?: "ES",
            darkMode = preferences[Keys.DARK_MODE] ?: "system"
        )
    }

    /**
     * Moneda por defecto como StateFlow (reactivo y con .value síncrono)
     * para que otros módulos conviertan importes vía [CurrencyPreference].
     * Debe ir DESPUÉS de `settings` (orden de inicialización de Kotlin).
     */
    override val defaultCurrency: kotlinx.coroutines.flow.StateFlow<String> =
        settings.map { it.defaultCurrency }
            .stateIn(repoScope, SharingStarted.Eagerly, "EUR")

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

    // ---- Distribución del Dashboard ----

    override val dashboardLayout: Flow<DashboardLayout> = context.dataStore.data.map { preferences ->
        DashboardLayout(
            widgetOrder = (preferences[Keys.DASHBOARD_WIDGET_ORDER] ?: "")
                .split(",").filter { it.isNotBlank() },
            hiddenWidgets = (preferences[Keys.DASHBOARD_HIDDEN_WIDGETS] ?: "")
                .split(",").filter { it.isNotBlank() }.toSet()
        )
    }

    override suspend fun updateDashboardLayout(layout: DashboardLayout) {
        context.dataStore.edit { preferences ->
            if (layout.widgetOrder.isEmpty()) {
                preferences.remove(Keys.DASHBOARD_WIDGET_ORDER)
            } else {
                preferences[Keys.DASHBOARD_WIDGET_ORDER] = layout.widgetOrder.joinToString(",")
            }
            if (layout.hiddenWidgets.isEmpty()) {
                preferences.remove(Keys.DASHBOARD_HIDDEN_WIDGETS)
            } else {
                preferences[Keys.DASHBOARD_HIDDEN_WIDGETS] = layout.hiddenWidgets.joinToString(",")
            }
        }
    }

    // ---- Posiciones de botones flotantes ----

    override val floatingButtonPositions: Flow<Map<String, FloatingButtonPosition>> =
        context.dataStore.data.map { preferences ->
            decodeFloatingButtonPositions(preferences[Keys.FLOATING_BUTTON_POSITIONS].orEmpty())
        }

    override suspend fun updateFloatingButtonPosition(id: String, position: FloatingButtonPosition) {
        if (id !in FloatingButtonIds.all) return
        context.dataStore.edit { preferences ->
            val current = decodeFloatingButtonPositions(
                preferences[Keys.FLOATING_BUTTON_POSITIONS].orEmpty()
            )
            preferences[Keys.FLOATING_BUTTON_POSITIONS] = encodeFloatingButtonPositions(
                current + (id to position.normalized())
            )
        }
    }

    override suspend fun replaceFloatingButtonPositions(
        positions: Map<String, FloatingButtonPosition>
    ) {
        context.dataStore.edit { preferences ->
            val encoded = encodeFloatingButtonPositions(positions)
            if (encoded.isEmpty()) {
                preferences.remove(Keys.FLOATING_BUTTON_POSITIONS)
            } else {
                preferences[Keys.FLOATING_BUTTON_POSITIONS] = encoded
            }
        }
    }

    override suspend fun snapshotSettings(): RestorableSettings {
        val current = settings.first()
        val layout = dashboardLayout.first()
        val fabPositions = floatingButtonPositions.first()
        return RestorableSettings(
            systemInstructions = current.systemInstructions,
            defaultCurrency = current.defaultCurrency,
            defaultCountry = current.defaultCountry,
            darkMode = current.darkMode,
            dashboardWidgetOrder = layout.widgetOrder,
            dashboardHiddenWidgets = layout.hiddenWidgets.toList(),
            floatingButtonPositions = fabPositions
        )
    }

    override suspend fun restoreSettings(settings: RestorableSettings) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SYSTEM_INSTRUCTIONS] = settings.systemInstructions
            preferences[Keys.DEFAULT_CURRENCY] = settings.defaultCurrency
            preferences[Keys.DEFAULT_COUNTRY] = settings.defaultCountry
            preferences[Keys.DARK_MODE] = settings.darkMode
            if (settings.dashboardWidgetOrder.isEmpty()) {
                preferences.remove(Keys.DASHBOARD_WIDGET_ORDER)
            } else {
                preferences[Keys.DASHBOARD_WIDGET_ORDER] = settings.dashboardWidgetOrder.joinToString(",")
            }
            if (settings.dashboardHiddenWidgets.isEmpty()) {
                preferences.remove(Keys.DASHBOARD_HIDDEN_WIDGETS)
            } else {
                preferences[Keys.DASHBOARD_HIDDEN_WIDGETS] = settings.dashboardHiddenWidgets.joinToString(",")
            }
            val fabPositions = encodeFloatingButtonPositions(settings.floatingButtonPositions)
            if (fabPositions.isEmpty()) {
                preferences.remove(Keys.FLOATING_BUTTON_POSITIONS)
            } else {
                preferences[Keys.FLOATING_BUTTON_POSITIONS] = fabPositions
            }
        }
    }

}

internal fun encodeFloatingButtonPositions(
    positions: Map<String, FloatingButtonPosition>
): String = positions
    .filterKeys { it in FloatingButtonIds.all }
    .toSortedMap()
    .entries
    .joinToString(";") { (id, position) ->
        val normalized = position.normalized()
        "$id|${normalized.edge.name}|${normalized.verticalFraction}"
    }

internal fun decodeFloatingButtonPositions(raw: String): Map<String, FloatingButtonPosition> = raw
    .split(';')
    .mapNotNull { encoded ->
        val parts = encoded.split('|')
        if (parts.size != 3) return@mapNotNull null
        val id = parts[0].takeIf { it in FloatingButtonIds.all } ?: return@mapNotNull null
        val edge = runCatching { FloatingButtonEdge.valueOf(parts[1]) }.getOrNull()
            ?: return@mapNotNull null
        val fraction = parts[2].toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?: return@mapNotNull null
        id to FloatingButtonPosition(edge, fraction).normalized()
    }
    .toMap()
