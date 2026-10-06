package com.gastos.feature.invoices

import com.gastos.domain.model.MovementListEntry
import com.gastos.domain.model.MovementListFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import com.gastos.domain.model.ConversionSummary
import com.gastos.domain.model.summarize
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import com.gastos.feature.invoices.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoicesUiState(
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
    val conversion: ConversionSummary? = null,
    val invoices: List<MovementListEntry> = emptyList(),
    val query: String = "",
    val matchingCount: Int = 0,
    val hasMore: Boolean = false,
    val hasAnyInvoices: Boolean = false,
    val selectedType: InvoiceType? = null,
    val selectedCategoryFilter: String? = null,
    val availableCategories: List<String> = emptyList(),
    val selectedSubcategoryFilter: String? = null,
    val availableSubcategories: List<String> = emptyList(),
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
    @ApplicationContext private val context: Context,
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val invoiceDriveService: InvoiceDriveService,
    private val invoiceImageStorage: InvoiceImageStorage,
    private val premiumStatus: PremiumStatusProvider,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val selectedCategoryFilter = savedStateHandle.getStateFlow<String?>("category", null)
    private val selectedSubcategoryFilter = savedStateHandle.getStateFlow<String?>("subcategory", null)

    private val _uiState = MutableStateFlow(InvoicesUiState())
    val uiState: StateFlow<InvoicesUiState> = _uiState.asStateFlow()

    private val query = savedStateHandle.getStateFlow("query", "")
    private val visibleLimit = savedStateHandle.getStateFlow("visibleLimit", MovementListFilter.PAGE_SIZE)

    private val periodStart = savedStateHandle.getStateFlow<Long?>("periodStart", null)
    private val periodEnd = savedStateHandle.getStateFlow<Long?>("periodEnd", null)

    init {
        observeInvoices()
        viewModelScope.launch {
            premiumStatus.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
    }

    private fun observeInvoices() {
        viewModelScope.launch {
            val filters = combine(query, selectedCategoryFilter, selectedSubcategoryFilter, periodStart, periodEnd) { search, category, subcategory, start, end ->
                MovementListFilter(search, category, subcategory, start, end)
            }.conflate()
            invoiceRepository.observeListEntries()
                .map { it.sortedWith(compareByDescending<MovementListEntry> { row -> row.date }.thenBy { row -> row.documentUuid }) }
                .combine(filters) { rows, filter ->
                    val filtered = rows.filter(filter::includes)
                    val categories = TransactionCategories.availableCategories(TransactionCategories.defaultExpenseCategories, rows.map { it.category })
                    val subcategories = if (filter.category == null || filter.category == UNCATEGORIZED_FILTER) emptyList() else
                        TransactionCategories.availableSubcategories(TransactionCategories.suggestedSubcategories(filter.category, isIncome = false),
                            rows.filter { TransactionCategories.matchesCategory(it.category, filter.category) }.map { it.subcategory })
                    InvoicesListData(filtered, filter, categories, subcategories, rows.isNotEmpty())
                }
                .combine(currencyPreference.defaultCurrency) { data, target -> data to target }
                .combine(exchangeRateProvider.rates) { (data, target), _ ->
                    Triple(data, target, exchangeRateProvider.summarize(data.rows.filter { !it.isIncome }.map { it.moneyRecord() }, target))
                }
                .combine(visibleLimit) { (data, target, conversion), limit ->
                    InvoicesUiState(invoices = data.rows.take(limit), query = data.filter.query, matchingCount = data.rows.size,
                        hasMore = data.rows.size > limit, periodStart = data.filter.start, periodEnd = data.filter.end,
                        hasAnyInvoices = data.hasAny, selectedCategoryFilter = data.filter.category,
                        selectedSubcategoryFilter = data.filter.subcategory, availableCategories = data.categories,
                        availableSubcategories = data.subcategories, totalGastosConvertido = conversion.amount,
                        conversion = conversion, defaultCurrency = target, isLoading = false)
                }
                .flowOn(Dispatchers.Default)
                .catch { failure -> _uiState.update { it.copy(error = failure.message ?: context.getString(R.string.load_invoice_error), isLoading = false) } }
                .collect { state ->
                    if (state.query == query.value && state.selectedCategoryFilter == selectedCategoryFilter.value &&
                        state.selectedSubcategoryFilter == selectedSubcategoryFilter.value && state.periodStart == periodStart.value && state.periodEnd == periodEnd.value) {
                        _uiState.update { current -> state.copy(isPremium = current.isPremium, uploadingToDrive = current.uploadingToDrive, selectedType = current.selectedType) }
                    }
                }
        }
    }

    fun search(value: String) {
        if (query.value == value) return
        savedStateHandle["visibleLimit"] = MovementListFilter.PAGE_SIZE
        savedStateHandle["query"] = value
        _uiState.update { it.copy(query = value) }
    }

    fun loadMore() {
        if (_uiState.value.hasMore) savedStateHandle["visibleLimit"] = visibleLimit.value + MovementListFilter.PAGE_SIZE
    }

    fun clearFilters() {
        search("")
        filterByCategory(null)
        filterByPeriod(null, null)
    }

    fun filterByType(type: InvoiceType?) {
        // Kept for existing callers; this screen always lists expenses.
        _uiState.update { it.copy(selectedType = type) }
    }

    fun filterByPeriod(start: Long?, end: Long?) {
        if (periodStart.value == start && periodEnd.value == end) return
        savedStateHandle["visibleLimit"] = MovementListFilter.PAGE_SIZE
        savedStateHandle["periodStart"] = start
        savedStateHandle["periodEnd"] = end
    }

    fun filterByCategory(category: String?) {
        if (selectedCategoryFilter.value == category && selectedSubcategoryFilter.value == null) return
        savedStateHandle["visibleLimit"] = MovementListFilter.PAGE_SIZE
        _uiState.update { it.copy(selectedCategoryFilter = category, selectedSubcategoryFilter = null) }
        savedStateHandle["category"] = category
        savedStateHandle["subcategory"] = null
    }

    fun filterBySubcategory(subcategory: String?) {
        if (selectedSubcategoryFilter.value == subcategory) return
        savedStateHandle["visibleLimit"] = MovementListFilter.PAGE_SIZE
        _uiState.update { it.copy(selectedSubcategoryFilter = subcategory) }
        savedStateHandle["subcategory"] = subcategory
    }

    fun refreshRates() {
        viewModelScope.launch {
            try { exchangeRateProvider.refresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _uiState.update { it.copy(error = error.message) } }
        }

    }

    fun deleteInvoice(invoice: Invoice, deleteRemoteImage: Boolean = false) {
        viewModelScope.launch {
            try {
                sheetsSyncManager.deleteLocal(invoice) {
                    if (deleteRemoteImage) invoiceDriveService.enqueueDelete(invoice, consent = true, prepared = true)
                }
                runCatching { invoiceImageStorage.delete(invoice.imagenUri) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                        it.copy(error = e.message ?: context.getString(R.string.delete))
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

private data class InvoicesListData(
    val rows: List<MovementListEntry>, val filter: MovementListFilter,
    val categories: List<String>, val subcategories: List<String>, val hasAny: Boolean
)
