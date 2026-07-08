package com.gastos.feature.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.feature.ai.AIService
import com.android.billingclient.api.ProductDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Resultado de validar la API key. */
sealed class ApiKeyValidation {
    data object None : ApiKeyValidation()
    data object Valid : ApiKeyValidation()
    data class Invalid(val message: String) : ApiKeyValidation()
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isApiKeyValidating: Boolean = false,
    val apiKeyValidation: ApiKeyValidation = ApiKeyValidation.None,
    val isPremium: Boolean = false,
    val productDetails: ProductDetails? = null,
    val isBillingConnecting: Boolean = false,
    val purchaseError: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiService: AIService,
    private val billingManager: BillingManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        observeBilling()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Un único collector combina settings + estado premium para refrescar
            // la UI de forma consistente (evita carreras entre dos emissions
            // que pisaban _uiState en orden no determinista).
            combine(
                settingsRepository.settings,
                billingManager.isPremium
            ) { settings, isPremium ->
                settings to isPremium
            }.collect { (settings, isPremium) ->
                _uiState.update {
                    it.copy(
                        settings = settings.copy(isPro = isPremium),
                        isLoading = false
                    )
                }
            }
        }

        // Solo reconfigura el modelo cuando cambian la API key o las
        // instrucciones (no al tocar moneda, tema, etc.), evitando reiniciar
        // la memoria conversacional innecesariamente.
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.geminiApiKey to it.systemInstructions }
                .distinctUntilChanged()
                .collect { (apiKey, instructions) ->
                    aiService.configureGemini(apiKey, instructions)
                }
        }
    }

    /** Observa el estado de Premium y los detalles de producto de BillingManager. */
    private fun observeBilling() {
        viewModelScope.launch {
            billingManager.isPremium.collect { premium ->
                // Aplica los límites de IA según el estado premium.
                aiService.setPremiumLimits(premium)
                _uiState.update {
                    it.copy(
                        isPremium = premium,
                        settings = it.settings.copy(isPro = premium)
                    )
                }
            }
        }
        viewModelScope.launch {
            billingManager.productDetails.collect { details ->
                _uiState.update { it.copy(productDetails = details) }
            }
        }
        viewModelScope.launch {
            billingManager.isConnecting.collect { connecting ->
                _uiState.update { it.copy(isBillingConnecting = connecting) }
            }
        }
        viewModelScope.launch {
            billingManager.purchaseError.collect { err ->
                _uiState.update { it.copy(purchaseError = err) }
            }
        }
    }

    // ---------------- API key ----------------

    /**
     * Valida la API key con una petición de prueba antes de guardarla.
     * Si es válida, la persista y reconfigura el modelo; si no, marca el estado
     * como inválido sin guardar.
     */
    fun updateGeminiApiKey(apiKey: String) {
        _uiState.update {
            it.copy(isApiKeyValidating = true, apiKeyValidation = ApiKeyValidation.None)
        }
        viewModelScope.launch {
            val result = aiService.validateApiKey(apiKey)
            if (result.isSuccess) {
                settingsRepository.updateGeminiApiKey(apiKey)
                _uiState.update {
                    it.copy(isApiKeyValidating = false, apiKeyValidation = ApiKeyValidation.Valid)
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "API key no válida"
                _uiState.update {
                    it.copy(isApiKeyValidating = false, apiKeyValidation = ApiKeyValidation.Invalid(msg))
                }
            }
        }
    }

    fun resetApiKeyValidation() {
        _uiState.update {
            it.copy(isApiKeyValidating = false, apiKeyValidation = ApiKeyValidation.None)
        }
    }

    fun updateSystemInstructions(instructions: String) {
        viewModelScope.launch {
            settingsRepository.updateSystemInstructions(instructions)
        }
    }

    // ---------------- Preferencias generales ----------------

    fun updateDefaultCurrency(currency: String) {
        viewModelScope.launch { settingsRepository.updateDefaultCurrency(currency) }
    }

    fun updateDefaultCountry(country: String) {
        viewModelScope.launch { settingsRepository.updateDefaultCountry(country) }
    }

    fun updateDarkMode(mode: String) {
        viewModelScope.launch { settingsRepository.updateDarkMode(mode) }
    }

    fun updateAutoBackup(autoBackup: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoBackup(autoBackup) }
    }

    fun updateShowTutorials(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowTutorials(show) }
    }

    // ---------------- Premium / Billing ----------------

    /** Inicia el flujo de compra de Premium desde una Activity. */
    fun purchasePremium(activity: Activity) {
        billingManager.clearError()
        billingManager.launchBillingFlow(activity)
    }

    /** Reintenta la conexión con Play Billing y refresca compras. */
    fun refreshBilling() {
        billingManager.clearError()
        billingManager.startConnection()
        billingManager.queryProductDetails()
        billingManager.queryPurchases()
    }

    fun clearPurchaseError() {
        billingManager.clearError()
    }
}
