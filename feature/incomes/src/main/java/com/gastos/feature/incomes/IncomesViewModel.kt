package com.gastos.feature.incomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IncomesUiState(
    val incomes: List<Income> = emptyList(),
    /** Total convertido a la moneda por defecto (null = sin tasas cargadas). */
    val totalIngresosConvertido: Double? = null,
    val defaultCurrency: String = "EUR",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class IncomesViewModel @Inject constructor(
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncomesUiState())
    val uiState: StateFlow<IncomesUiState> = _uiState.asStateFlow()

    init {
        loadIncomes()
        // Recalcula el total convertido cuando llegan tasas o cambia la moneda por defecto.
        viewModelScope.launch {
            combine(
                exchangeRateProvider.rates,
                currencyPreference.defaultCurrency
            ) { _, target -> target }.collect { target ->
                recomputeTotal(target)
            }
        }
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
                    recomputeTotal(currencyPreference.defaultCurrency.value)
                }
        }
    }

    /**
     * Convierte cada ingreso a la moneda por defecto del usuario y suma.
     * Si falta la tasa de alguna moneda, su importe se excluye del total
     * (no se suma como si fuera la moneda por defecto).
     */
    private fun recomputeTotal(target: String) {
        val incomes = _uiState.value.incomes
        val converted = incomes.sumOf { income ->
            // Cast explícito a Double (0.0 si no hay tasa): evita ambigüedad entre
            // sumOf((T)->Double) y sumOf((T)->Double?).
            (exchangeRateProvider.convert(income.monto, income.moneda, target) ?: 0.0)
        }
        // Si TODOS los convertibles están null (sin tasas), el total es null → UI muestra "—".
        val allConvertibleMissing = incomes.isNotEmpty() &&
            incomes.all { exchangeRateProvider.convert(it.monto, it.moneda, target) == null }
        _uiState.update {
            it.copy(
                totalIngresosConvertido = if (allConvertibleMissing) null else converted,
                defaultCurrency = target
            )
        }
    }

    fun deleteIncome(income: Income) {
        viewModelScope.launch {
            try {
                incomeRepository.deleteIncome(income)
                // Propaga el borrado a la hoja "Nóminas" del Sheet vinculado.
                sheetsSyncManager.deleteIncome(income.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }
}
