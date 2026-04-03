package com.gastos.feature.fiscal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.repository.CountryFiscalConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FiscalUiState(
    val configs: List<CountryFiscalConfig> = emptyList(),
    val selectedCountry: String = "ES",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class FiscalViewModel @Inject constructor(
    private val countryFiscalConfigRepository: CountryFiscalConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FiscalUiState())
    val uiState: StateFlow<FiscalUiState> = _uiState.asStateFlow()

    init {
        loadConfigs()
    }

    private fun loadConfigs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Asegurar que existen las configuraciones por defecto
                countryFiscalConfigRepository.insertDefaultConfigs()

                countryFiscalConfigRepository.getAllConfigs().collect { configs ->
                    _uiState.update {
                        it.copy(configs = configs, isLoading = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error al cargar configuraciones"
                    )
                }
            }
        }
    }

    fun selectCountry(countryCode: String) {
        _uiState.update { it.copy(selectedCountry = countryCode) }
    }

}
