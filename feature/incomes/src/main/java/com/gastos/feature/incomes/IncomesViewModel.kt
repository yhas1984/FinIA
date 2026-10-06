package com.gastos.feature.incomes

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
import com.gastos.domain.model.Income
import com.gastos.domain.model.TransactionCategories
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.storage.InvoiceImageStorage
import com.gastos.feature.incomes.R
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
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
    val conversion: ConversionSummary? = null,
    val incomes: List<MovementListEntry> = emptyList(),
    val query: String = "",
    val matchingCount: Int = 0,
    val hasMore: Boolean = false,
    val hasAnyIncomes: Boolean = false,
    val selectedCategoryFilter: String? = null,
    val availableCategories: List<String> = emptyList(),
    val selectedSubcategoryFilter: String? = null,
    val availableSubcategories: List<String> = emptyList(),
    /** Total convertido a la moneda por defecto (null = sin tasas cargadas). */
    val totalIngresosConvertido: Double? = null,
    val defaultCurrency: String = "EUR",
    val isLoading: Boolean = true,
    val error: String? = null
)

private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IncomesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val invoiceDriveService: com.gastos.feature.backup.InvoiceDriveService,
    private val invoiceImageStorage: InvoiceImageStorage,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncomesUiState())
    val uiState: StateFlow<IncomesUiState> = _uiState.asStateFlow()
    private val selectedCategoryFilter = savedStateHandle.getStateFlow<String?>("category", null)
    private val selectedSubcategoryFilter = savedStateHandle.getStateFlow<String?>("subcategory", null)

    private val query = savedStateHandle.getStateFlow("query", "")
    private val visibleLimit = savedStateHandle.getStateFlow("visibleLimit", MovementListFilter.PAGE_SIZE)

    private val periodStart = savedStateHandle.getStateFlow<Long?>("periodStart", null)
    private val periodEnd = savedStateHandle.getStateFlow<Long?>("periodEnd", null)

    init { observeIncomes() }

    private fun observeIncomes() {
        viewModelScope.launch {
            val filters = combine(query, selectedCategoryFilter, selectedSubcategoryFilter, periodStart, periodEnd) { search, category, subcategory, start, end ->
                MovementListFilter(search, category, subcategory, start, end)
            }.conflate()
            incomeRepository.observeListEntries()
                .map { it.sortedWith(compareByDescending<MovementListEntry> { row -> row.date }.thenBy { row -> row.documentUuid }) }
                .combine(filters) { rows, filter ->
                    val filtered = rows.filter(filter::includes)
                    val categories = TransactionCategories.availableCategories(TransactionCategories.defaultIncomeCategories, rows.map { it.category })
                    val subcategories = if (filter.category == null || filter.category == UNCATEGORIZED_FILTER) emptyList() else
                        TransactionCategories.availableSubcategories(TransactionCategories.suggestedSubcategories(filter.category, isIncome = true),
                            rows.filter { TransactionCategories.matchesCategory(it.category, filter.category) }.map { it.subcategory })
                    IncomesListData(filtered, filter, categories, subcategories, rows.isNotEmpty())
                }
                .combine(currencyPreference.defaultCurrency) { data, target -> data to target }
                .combine(exchangeRateProvider.rates) { (data, target), _ ->
                    Triple(data, target, exchangeRateProvider.summarize(data.rows.map { it.moneyRecord() }, target))
                }
                .combine(visibleLimit) { (data, target, conversion), limit ->
                    IncomesUiState(incomes = data.rows.take(limit), query = data.filter.query, matchingCount = data.rows.size,
                        hasMore = data.rows.size > limit, periodStart = data.filter.start, periodEnd = data.filter.end,
                        hasAnyIncomes = data.hasAny, selectedCategoryFilter = data.filter.category,
                        selectedSubcategoryFilter = data.filter.subcategory, availableCategories = data.categories,
                        availableSubcategories = data.subcategories, totalIngresosConvertido = conversion.amount,
                        conversion = conversion, defaultCurrency = target, isLoading = false)
                }
                .flowOn(Dispatchers.Default)
                .catch { failure -> _uiState.update { it.copy(error = failure.message ?: context.getString(R.string.load_income_error), isLoading = false) } }
                .collect { state ->
                    if (state.query == query.value && state.selectedCategoryFilter == selectedCategoryFilter.value &&
                        state.selectedSubcategoryFilter == selectedSubcategoryFilter.value && state.periodStart == periodStart.value && state.periodEnd == periodEnd.value) {
                        _uiState.value = state
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

    fun deleteIncome(income: Income, deleteRemoteImage: Boolean = false) {
        viewModelScope.launch {
            try {
                sheetsSyncManager.deleteLocal(income) {
                    if (deleteRemoteImage) invoiceDriveService.enqueueDelete(income, consent = true, prepared = true)
                }
                runCatching { invoiceImageStorage.delete(income.imagenUri) }
                // Propaga el borrado a la hoja unificada "Ingresos".

            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                        it.copy(error = e.message ?: context.getString(R.string.delete_income_error))
                }
            }
        }
    }
}

private data class IncomesListData(
    val rows: List<MovementListEntry>, val filter: MovementListFilter,
    val categories: List<String>, val subcategories: List<String>, val hasAny: Boolean
)
