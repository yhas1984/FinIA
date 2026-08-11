package com.gastos.feature.incomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.TransactionCategories
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
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val invoiceImageStorage: InvoiceImageStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncomesUiState())
    val uiState: StateFlow<IncomesUiState> = _uiState.asStateFlow()
    private val selectedCategoryFilter = MutableStateFlow<String?>(null)
    private val selectedSubcategoryFilter = MutableStateFlow<String?>(null)

    init {
        // Una sola cadena reactiva: ingresos + moneda destino + tasas.
        viewModelScope.launch {
            incomeRepository.getAllIncomes()
                .combine(selectedCategoryFilter) { incomes, categoryFilter ->
                    incomes to categoryFilter
                }
                .combine(selectedSubcategoryFilter) { (allIncomes, categoryFilter), subcategoryFilter ->
                    Triple(allIncomes, categoryFilter, subcategoryFilter)
                }
                .combine(currencyPreference.defaultCurrency) { (allIncomes, categoryFilter, subcategoryFilter), target ->
                    (allIncomes to categoryFilter) to (subcategoryFilter to target)
                }
                .combine(exchangeRateProvider.rates) { (outer, inner), _ ->
                    val (allIncomes, categoryFilter) = outer
                    val (subcategoryFilter, target) = inner
                    val visibleIncomes = filterIncomesByCategory(
                        allIncomes,
                        categoryFilter,
                        subcategoryFilter
                    )
                    val availableCategories = TransactionCategories.availableCategories(
                        defaults = TransactionCategories.defaultIncomeCategories,
                        existing = allIncomes.map { it.categoria }
                    )
                    val availableSubcategories = if (categoryFilter == null || categoryFilter == UNCATEGORIZED_FILTER) {
                        emptyList()
                    } else {
                        TransactionCategories.availableSubcategories(
                            defaults = TransactionCategories.suggestedSubcategories(
                                categoryFilter,
                                isIncome = true
                            ),
                            existing = allIncomes
                                .filter { TransactionCategories.matchesCategory(it.categoria, categoryFilter) }
                                .map { it.subcategoria }
                        )
                    }
                    IncomesDisplayData(
                        incomes = visibleIncomes,
                        targetCurrency = target,
                        total = recomputeTotal(visibleIncomes, target),
                        categoryFilter = categoryFilter,
                        subcategoryFilter = subcategoryFilter,
                        availableCategories = availableCategories,
                        availableSubcategories = availableSubcategories,
                        hasAnyIncomes = allIncomes.isNotEmpty()
                    )
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar ingresos", isLoading = false)
                    }
                }
                .collect { data ->
                    _uiState.update {
                        it.copy(
                            incomes = data.incomes,
                            hasAnyIncomes = data.hasAnyIncomes,
                            isLoading = false,
                            error = null,
                            totalIngresosConvertido = data.total,
                            defaultCurrency = data.targetCurrency,
                            selectedCategoryFilter = data.categoryFilter,
                            availableCategories = data.availableCategories,
                            selectedSubcategoryFilter = data.subcategoryFilter,
                            availableSubcategories = data.availableSubcategories
                        )
                    }
                }
        }
    }

    fun filterByCategory(category: String?) {
        _uiState.update { it.copy(selectedCategoryFilter = category, selectedSubcategoryFilter = null) }
        selectedCategoryFilter.value = category
        selectedSubcategoryFilter.value = null
    }

    fun filterBySubcategory(subcategory: String?) {
        _uiState.update { it.copy(selectedSubcategoryFilter = subcategory) }
        selectedSubcategoryFilter.value = subcategory
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

    private fun filterIncomesByCategory(
        incomes: List<Income>,
        categoryFilter: String?,
        subcategoryFilter: String? = null
    ): List<Income> {
        val byCategory = when (categoryFilter) {
            null -> incomes
            UNCATEGORIZED_FILTER -> incomes.filter { TransactionCategories.normalizeCategory(it.categoria) == null }
            else -> incomes.filter { TransactionCategories.matchesCategory(it.categoria, categoryFilter) }
        }
        if (subcategoryFilter.isNullOrBlank()) return byCategory
        return byCategory.filter { TransactionCategories.matchesCategory(it.subcategoria, subcategoryFilter) }
    }

    fun deleteIncome(income: Income) {
        viewModelScope.launch {
            try {
                incomeRepository.deleteIncome(income)
                invoiceImageStorage.delete(income.imagenUri)
                // Propaga el borrado a la hoja unificada "Ingresos".
                sheetsSyncManager.deleteIncome(income.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }
}

private data class IncomesDisplayData(
    val incomes: List<Income>,
    val targetCurrency: String,
    val total: Double?,
    val categoryFilter: String?,
    val subcategoryFilter: String?,
    val availableCategories: List<String>,
    val availableSubcategories: List<String>,
    val hasAnyIncomes: Boolean
)
