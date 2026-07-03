package com.gastos.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.feature.ai.AIService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val licenseInput: String = "",
    val licenseError: String? = null,
    val isApiKeyValidating: Boolean = false,
    val apiKeyValidation: ApiKeyValidation = ApiKeyValidation.None
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiService: AIService,
    private val licenseManager: LicenseManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Refresca el UI con cada cambio de settings...
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings.copy(isPro = licenseManager.isPro()),
                        isLoading = false
                    )
                }
            }
        }

        // ...pero solo reconfigura el modelo cuando cambian la API key o las
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

    /**
     * Valida la API key con una petición de prueba antes de guardarla.
     * Si es válida, la persiste y reconfigura el modelo; si no, marca el estado
     * como inválido sin guardar.
     */
    fun updateGeminiApiKey(apiKey: String) {
        _uiState.update {
            it.copy(isApiKeyValidating = true, apiKeyValidation = ApiKeyValidation.None)
        }
        viewModelScope.launch {
            val result = aiService.validateApiKey(apiKey)
            if (result.isSuccess) {
                // Al persistir, el collect con distinctUntilChanged reconfigurará el modelo.
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

    /** Limpia el estado de validación (al cerrar el diálogo, por ejemplo). */
    fun resetApiKeyValidation() {
        _uiState.update {
            it.copy(isApiKeyValidating = false, apiKeyValidation = ApiKeyValidation.None)
        }
    }

    fun updateSystemInstructions(instructions: String) {
        viewModelScope.launch {
            // Al persistir, el collect con distinctUntilChanged reconfigurará el modelo.
            settingsRepository.updateSystemInstructions(instructions)
        }
    }

    fun updateDefaultCurrency(currency: String) {
        viewModelScope.launch {
            settingsRepository.updateDefaultCurrency(currency)
        }
    }

    fun updateDefaultCountry(country: String) {
        viewModelScope.launch {
            settingsRepository.updateDefaultCountry(country)
        }
    }

    fun updateDarkMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.updateDarkMode(mode)
        }
    }

    fun updateAutoBackup(autoBackup: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoBackup(autoBackup)
        }
    }

    fun updateLicenseInput(input: String) {
        _uiState.update {
            it.copy(licenseInput = input, licenseError = null)
        }
    }

    fun activateLicense() {
        val code = _uiState.value.licenseInput
        if (code.isBlank()) return

        val result = licenseManager.activateLicense(code)
        when (result) {
            is LicenseResult.Success -> {
                _uiState.update {
                    it.copy(
                        licenseError = null,
                        licenseInput = "",
                        settings = it.settings.copy(isPro = true)
                    )
                }
            }
            is LicenseResult.InvalidCode -> {
                _uiState.update {
                    it.copy(licenseError = "Código de licencia no válido")
                }
            }
        }
    }

    fun deactivateLicense() {
        licenseManager.deactivateLicense()
        _uiState.update {
            it.copy(settings = it.settings.copy(isPro = false))
        }
    }
}
