package com.gastos.feature.incomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.repository.IncomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IncomesUiState(
    val incomes: List<Income> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class IncomesViewModel @Inject constructor(
    private val incomeRepository: IncomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncomesUiState())
    val uiState: StateFlow<IncomesUiState> = _uiState.asStateFlow()

    init {
        loadIncomes()
    }

    fun loadIncomes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            incomeRepository.getAllIncomes()
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar ingresos", isLoading = false)
                    }
                }
                .collect { incomes ->
                    _uiState.update {
                        it.copy(incomes = incomes, isLoading = false, error = null)
                    }
                }
        }
    }

    fun deleteIncome(income: Income) {
        viewModelScope.launch {
            try {
                incomeRepository.deleteIncome(income)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }
}
