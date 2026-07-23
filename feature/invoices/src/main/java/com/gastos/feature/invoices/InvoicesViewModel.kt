package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.InvoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoicesUiState(
    val invoices: List<Invoice> = emptyList(),
    val selectedType: InvoiceType? = null,
    /** Total convertido a la moneda por defecto (solo gastos). null = sin tasas. */
    val totalGastosConvertido: Double? = null,
    val defaultCurrency: String = "EUR",
    val isLoading: Boolean = true,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InvoicesViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val selectedType = MutableStateFlow<InvoiceType?>(null)

    private val _uiState = MutableStateFlow(InvoicesUiState())
    val uiState: StateFlow<InvoicesUiState> = _uiState.asStateFlow()

    init {
        observeInvoices()
    }

    /**
     * Una sola cadena reactiva que cubre:
     *   • filtro por tipo (cambios en [selectedType])
     *   • tasas de cambio
     *   • moneda por defecto del usuario
     * Antes cada cambio de filtro abría un `collect` nuevo sin cancelar el
     * anterior; ahora `flatMapLatest` garantiza que solo hay un collector
     * activo a la vez.
     */
    private fun observeInvoices() {
        viewModelScope.launch {
            selectedType
                .flatMapLatest { type ->
                    if (type == null) invoiceRepository.getAllInvoices()
                    else invoiceRepository.getInvoicesByType(type)
                }
                .combine(currencyPreference.defaultCurrency) { invoices, target ->
                    invoices to target
                }
                .combine(exchangeRateProvider.rates) { (invoices, target), _ ->
                    Triple(invoices, target, recomputeTotal(invoices, target))
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar facturas", isLoading = false)
                    }
                }
                .collect { (invoices, target, total) ->
                    _uiState.update {
                        it.copy(
                            invoices = invoices,
                            isLoading = false,
                            error = null,
                            totalGastosConvertido = total,
                            defaultCurrency = target
                        )
                    }
                }
        }
    }

    fun filterByType(type: InvoiceType?) {
        _uiState.update { it.copy(selectedType = type, isLoading = true) }
        selectedType.value = type
    }

    /**
     * Suma los importes de los gastos (excluyendo INGRESO porque esos se
     * gestionan en la pestaña Ingresos). Si una moneda no tiene tasa, su
     * importe se excluye (no se suma como si fuera la moneda destino).
     */
    private fun recomputeTotal(invoices: List<Invoice>, target: String): Double? {
        val gastos = invoices.filter { it.tipo == InvoiceType.GASTO }
        if (gastos.isEmpty()) return 0.0
        val converted = gastos.sumOf {
            exchangeRateProvider.convert(it.total, it.moneda, target) ?: 0.0
        }
        val allMissing = gastos.all {
            exchangeRateProvider.convert(it.total, it.moneda, target) == null
        }
        return if (allMissing) null else converted
    }

    fun deleteInvoice(invoice: Invoice) {
        viewModelScope.launch {
            try {
                invoiceRepository.deleteInvoice(invoice)
                // Propaga el borrado al Sheet (fila del gasto + sus productos).
                sheetsSyncManager.deleteExpense(invoice.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }
}
