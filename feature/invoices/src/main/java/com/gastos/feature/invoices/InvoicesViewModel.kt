package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.InvoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoicesUiState(
    val invoices: List<Invoice> = emptyList(),
    val selectedType: InvoiceType? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class InvoicesViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(InvoicesUiState())
    val uiState: StateFlow<InvoicesUiState> = _uiState.asStateFlow()

    init {
        loadInvoices()
    }

    fun loadInvoices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            invoiceRepository.getAllInvoices()
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar facturas", isLoading = false)
                    }
                }
                .collect { invoices ->
                    _uiState.update {
                        it.copy(invoices = invoices, isLoading = false, error = null)
                    }
                }
        }
    }

    fun filterByType(type: InvoiceType?) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedType = type, isLoading = true) }

            val flow = if (type == null) {
                invoiceRepository.getAllInvoices()
            } else {
                invoiceRepository.getInvoicesByType(type)
            }

            flow.catch { e ->
                _uiState.update {
                    it.copy(error = e.message ?: "Error al filtrar", isLoading = false)
                }
            }.collect { invoices ->
                _uiState.update {
                    it.copy(invoices = invoices, isLoading = false, error = null)
                }
            }
        }
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
