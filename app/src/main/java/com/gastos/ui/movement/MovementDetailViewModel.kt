package com.gastos.ui.movement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.*
import com.gastos.repository.*
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovementDetailState(
    val invoice: Invoice? = null, val income: Income? = null, val products: List<Product> = emptyList(),
    val loading: Boolean = true, val busy: Boolean = false, val deleted: Boolean = false,
    val premium: Boolean = false, val error: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MovementDetailViewModel @Inject constructor(
    state: SavedStateHandle, private val invoices: InvoiceRepository, private val incomes: IncomeRepository,
    products: ProductRepository, private val sheets: SheetsSyncManager, private val drive: InvoiceDriveService,
    private val images: InvoiceImageStorage, premium: PremiumStatusProvider
) : ViewModel() {
    private val uuid: String = state["uuid"] ?: ""
    private val source: MovementSource? = state.get<String>("source")?.let { runCatching { MovementSource.valueOf(it) }.getOrNull() }
    private val _state = MutableStateFlow(MovementDetailState())
    val uiState: StateFlow<MovementDetailState> = _state.asStateFlow()
    init {
        viewModelScope.launch { premium.isPremium.collect { value -> _state.update { it.copy(premium = value) } } }
        viewModelScope.launch {
            combine(invoices.getInvoicesByType(InvoiceType.GASTO), incomes.getAllIncomes()) { expenses, incomeRecords ->
                val expense = if (source == MovementSource.INVOICE) expenses.find { it.documentUuid == uuid } else null
                val income = incomeRecords.find { it.documentUuid == uuid && it.movementReference().source == source }
                expense to income
            }.flatMapLatest { (expense, income) ->
                val invoiceId = expense?.id ?: income?.id?.takeIf { it < 0 }?.let { -it }
                (invoiceId?.let { products.getProductsByInvoiceId(it) } ?: flowOf(emptyList())).map { Triple(expense, income, it) }
            }.catch { failure -> _state.update { it.copy(loading = false, error = failure.message) } }
                .collect { (expense, income, rows) -> _state.update { it.copy(invoice = expense, income = income, products = rows, loading = false) } }
        }
    }
    fun delete(deleteRemote: Boolean) {
        if (_state.value.busy) return
        val value = _state.value
        if (value.invoice == null && value.income == null) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                value.invoice?.let { invoice ->
                    sheets.deleteLocal(invoice) { if (deleteRemote) drive.enqueueDelete(invoice, consent = true, prepared = true) }
                    runCatching { images.delete(invoice.imagenUri) }
                }
                value.income?.let { income ->
                    sheets.deleteLocal(income) { if (deleteRemote) drive.enqueueDelete(income, consent = true, prepared = true) }
                    runCatching { images.delete(income.imagenUri) }
                }
                _state.update { it.copy(busy = false, deleted = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(busy = false, error = failure.message ?: "Error") } }
        }
    }
    fun retryImage() {
        if (_state.value.busy) return
        val invoice = _state.value.invoice ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = drive.upload(invoice)
                if (result.uploaded) sheets.upsertExpense(result.invoice)
                else _state.update { it.copy(error = result.message) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(error = failure.message) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

@HiltViewModel
class MovementNavigationViewModel @Inject constructor(private val invoices: InvoiceRepository, private val incomes: IncomeRepository) : ViewModel() {
    suspend fun resolve(uuid: String, kind: String?): MovementReference? = if (kind == "INCOME")
        incomes.getAllIncomes().first().find { it.documentUuid == uuid }?.movementReference()
    else invoices.getAllInvoices().first().find { it.documentUuid == uuid }?.movementReference()
}
