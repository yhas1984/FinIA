package com.gastos.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.feature.ai.AIEngine
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.GemmaModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val licenseInput: String = "",
    val licenseError: String? = null,
    val gemmaModel: GemmaModelState = GemmaModelState()
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
        observeGemmaState()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings.copy(isPro = licenseManager.isPro()),
                        isLoading = false
                    )
                }
                when (settings.aiEngine) {
                    "gemini_api" -> aiService.setEngine(AIEngine.GEMINI_API, settings.geminiApiKey)
                    "gemma_local" -> aiService.setEngine(AIEngine.GEMMA_LOCAL)
                }
            }
        }
    }

    private fun observeGemmaState() {
        viewModelScope.launch {
            aiService.gemmaModelState.collect { gemmaState ->
                _uiState.update { it.copy(gemmaModel = gemmaState) }
            }
        }
    }

    fun updateAiEngine(engine: String) {
        viewModelScope.launch {
            settingsRepository.updateAiEngine(engine)
            when (engine) {
                "gemini_api" -> aiService.setEngine(AIEngine.GEMINI_API, _uiState.value.settings.geminiApiKey)
                "gemma_local" -> {
                    aiService.setEngine(AIEngine.GEMMA_LOCAL)
                    checkGemmaStatus()
                }
            }
        }
    }

    fun checkGemmaStatus() {
        viewModelScope.launch {
            val result = aiService.checkGemmaStatus()
            if (result.isFailure) {
                _uiState.update {
                    it.copy(error = result.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun downloadGemmaModel() {
        viewModelScope.launch {
            val result = aiService.downloadGemmaModel()
            if (result.isFailure) {
                _uiState.update {
                    it.copy(error = result.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun updateGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            settingsRepository.updateGeminiApiKey(apiKey)
            aiService.setEngine(AIEngine.GEMINI_API, apiKey)
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
