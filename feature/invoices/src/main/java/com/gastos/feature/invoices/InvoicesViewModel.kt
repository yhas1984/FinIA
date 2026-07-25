package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.gastos.storage.InvoiceImageStorage
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
    val hasAnyInvoices: Boolean = false,
    val selectedType: InvoiceType? = null,
    val selectedCategoryFilter: String? = null,
    val availableCategories: List<String> = emptyList(),
    /** Total convertido a la moneda por defecto (solo gastos). null = sin tasas. */
    val totalGastosConvertido: Double? = null,
    val defaultCurrency: String = "EUR",
    val isPremium: Boolean = false,
    val uploadingToDrive: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null
)

private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InvoicesViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val invoiceDriveService: InvoiceDriveService,
    private val invoiceImageStorage: InvoiceImageStorage,
    private val premiumStatus: PremiumStatusProvider
) : ViewModel() {

    private val selectedType = MutableStateFlow<InvoiceType?>(null)
    private val selectedCategoryFilter = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(InvoicesUiState())
    val uiState: StateFlow<InvoicesUiState> = _uiState.asStateFlow()

    init {
        observeInvoices()
        viewModelScope.launch {
            premiumStatus.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
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
                .combine(selectedCategoryFilter) { invoices, categoryFilter ->
                    invoices to categoryFilter
                }
                .combine(currencyPreference.defaultCurrency) { (allInvoices, categoryFilter), target ->
                    Triple(allInvoices, categoryFilter, target)
                }
                .combine(exchangeRateProvider.rates) { (allInvoices, categoryFilter, target), _ ->
                    val visibleInvoices = filterInvoicesByCategory(allInvoices, categoryFilter)
                    val availableCategories = TransactionCategories.availableCategories(
                        defaults = TransactionCategories.defaultExpenseCategories,
                        existing = allInvoices.map { it.categoria }
                    )
                    InvoicesDisplayData(
                        invoices = visibleInvoices,
                        targetCurrency = target,
                        total = recomputeTotal(visibleInvoices, target),
                        categoryFilter = categoryFilter,
                        availableCategories = availableCategories,
                        hasAnyInvoices = allInvoices.isNotEmpty()
                    )
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar facturas", isLoading = false)
                    }
                }
                .collect { data ->
                    _uiState.update {
                        it.copy(
                            invoices = data.invoices,
                            hasAnyInvoices = data.hasAnyInvoices,
                            isLoading = false,
                            error = null,
                            totalGastosConvertido = data.total,
                            defaultCurrency = data.targetCurrency,
                            selectedCategoryFilter = data.categoryFilter,
                            availableCategories = data.availableCategories
                        )
                    }
                }
        }
    }

    fun filterByType(type: InvoiceType?) {
        _uiState.update { it.copy(selectedType = type, isLoading = true) }
        selectedType.value = type
    }

    fun filterByCategory(category: String?) {
        _uiState.update { it.copy(selectedCategoryFilter = category) }
        selectedCategoryFilter.value = category
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

    private fun filterInvoicesByCategory(invoices: List<Invoice>, categoryFilter: String?): List<Invoice> =
        when (categoryFilter) {
            null -> invoices
            UNCATEGORIZED_FILTER -> invoices.filter { TransactionCategories.normalizeCategory(it.categoria) == null }
            else -> invoices.filter { TransactionCategories.matchesCategory(it.categoria, categoryFilter) }
        }

    fun deleteInvoice(invoice: Invoice) {
        viewModelScope.launch {
            try {
                invoiceRepository.deleteInvoice(invoice)
                invoiceImageStorage.delete(invoice.imagenUri)
                // Propaga el borrado al Sheet (fila del gasto + sus productos).
                sheetsSyncManager.deleteExpense(invoice.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }

    fun retryDriveUpload(invoice: Invoice) {
        if (
            invoice.imagenUri.isNullOrBlank() ||
            invoice.driveWebViewLink != null ||
            invoice.id in _uiState.value.uploadingToDrive
        ) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    uploadingToDrive = it.uploadingToDrive + invoice.id,
                    error = null
                )
            }
            val result = invoiceDriveService.upload(invoice)
            if (result.uploaded) {
                sheetsSyncManager.upsertExpense(result.invoice)
            }
            _uiState.update {
                it.copy(
                    uploadingToDrive = it.uploadingToDrive - invoice.id,
                    error = result.message.takeUnless { result.uploaded }
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

private data class InvoicesDisplayData(
    val invoices: List<Invoice>,
    val targetCurrency: String,
    val total: Double?,
    val categoryFilter: String?,
    val availableCategories: List<String>,
    val hasAnyInvoices: Boolean
)
