package com.gastos.feature.incomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IncomesViewModel @Inject constructor(
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val invoiceImageStorage: InvoiceImageStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncomesUiState())
    val uiState: StateFlow<IncomesUiState> = _uiState.asStateFlow()

    init {
        // Una sola cadena reactiva: ingresos + moneda destino + tasas.
        viewModelScope.launch {
            incomeRepository.getAllIncomes()
                .combine(currencyPreference.defaultCurrency) { incomes, target ->
                    incomes to target
                }
                .combine(exchangeRateProvider.rates) { (incomes, target), _ ->
                    Triple(incomes, target, recomputeTotal(incomes, target))
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar ingresos", isLoading = false)
                    }
                }
                .collect { (incomes, target, total) ->
                    _uiState.update {
                        it.copy(
                            incomes = incomes,
                            isLoading = false,
                            error = null,
                            totalIngresosConvertido = total,
                            defaultCurrency = target
                        )
                    }
                }
        }
    }

    /**
     * Convierte cada ingreso a la moneda por defecto del usuario y suma.
     * Si falta la tasa de alguna moneda, su importe se excluye del total
     * (no se suma como si fuera la moneda por defecto).
     */
    private fun recomputeTotal(incomes: List<Income>, target: String): Double? {
        if (incomes.isEmpty()) return 0.0
        val converted = incomes.sumOf { income ->
            exchangeRateProvider.convert(income.monto, income.moneda, target) ?: 0.0
        }
        val allMissing = incomes.all {
            exchangeRateProvider.convert(it.monto, it.moneda, target) == null
        }
        return if (allMissing) null else converted
    }

    fun deleteIncome(income: Income) {
        viewModelScope.launch {
            try {
                incomeRepository.deleteIncome(income)
                invoiceImageStorage.delete(income.imagenUri)
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
