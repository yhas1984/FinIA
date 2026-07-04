package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.feature.backup.SheetsSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditInvoiceUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val invoice: Invoice? = null,
    val error: String? = null
)

data class EditInvoiceForm(
    val id: Long = 0,
    val fecha: Long = System.currentTimeMillis(),
    val proveedor: String = "",
    val tipo: InvoiceType = InvoiceType.GASTO,
    val moneda: String = "EUR",
    val total: String = "",
    val ivaPercent: String = "21.0",
    val irpfPercent: String = "0.0",
    val paisCodigo: String = "ES",
    val nifEmisor: String = "",
    val nifReceptor: String = "",
    val notas: String = ""
)

@HiltViewModel
class EditInvoiceViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val incomeRepository: IncomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditInvoiceUiState())
    val uiState: StateFlow<EditInvoiceUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditInvoiceForm())
    val form: StateFlow<EditInvoiceForm> = _form.asStateFlow()

    fun loadInvoice(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val invoice = invoiceRepository.getInvoiceById(id)
                if (invoice != null) {
                    _form.update {
                        EditInvoiceForm(
                            id = invoice.id,
                            fecha = invoice.fecha,
                            proveedor = invoice.proveedor,
                            tipo = invoice.tipo,
                            moneda = invoice.moneda,
                            total = invoice.total.toString(),
                            ivaPercent = invoice.ivaPercent.toString(),
                            irpfPercent = invoice.irpfPercent.toString(),
                            paisCodigo = invoice.paisCodigo,
                            nifEmisor = invoice.nifEmisor ?: "",
                            nifReceptor = invoice.nifReceptor ?: "",
                            notas = invoice.notas ?: ""
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, invoice = invoice) }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Factura no encontrada")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Error al cargar factura")
                }
            }
        }
    }

    fun updateProveedor(value: String) { _form.update { it.copy(proveedor = value) } }
    fun updateFecha(value: Long) { _form.update { it.copy(fecha = value) } }
    fun updateTipo(value: InvoiceType) { _form.update { it.copy(tipo = value) } }
    fun updateMoneda(value: String) { _form.update { it.copy(moneda = value) } }
    fun updateTotal(value: String) { _form.update { it.copy(total = value) } }
    fun updateIvaPercent(value: String) { _form.update { it.copy(ivaPercent = value) } }
    fun updateIrpfPercent(value: String) { _form.update { it.copy(irpfPercent = value) } }
    fun updatePaisCodigo(value: String) { _form.update { it.copy(paisCodigo = value) } }
    fun updateNifEmisor(value: String) { _form.update { it.copy(nifEmisor = value) } }
    fun updateNifReceptor(value: String) { _form.update { it.copy(nifReceptor = value) } }
    fun updateNotas(value: String) { _form.update { it.copy(notas = value) } }

    fun saveInvoice() {
        viewModelScope.launch {
            val form = _form.value
            val total = form.total.toDoubleOrNull()
            if (total == null || total <= 0) {
                _uiState.update { it.copy(saveResult = "El total debe ser un número positivo") }
                return@launch
            }
            if (form.proveedor.isBlank()) {
                _uiState.update { it.copy(saveResult = "El proveedor es obligatorio") }
                return@launch
            }

            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                val invoice = Invoice(
                    id = form.id,
                    fecha = form.fecha,
                    proveedor = form.proveedor.trim(),
                    tipo = form.tipo,
                    moneda = form.moneda,
                    total = total,
                    ivaPercent = form.ivaPercent.toDoubleOrNull() ?: 0.0,
                    irpfPercent = form.irpfPercent.toDoubleOrNull() ?: 0.0,
                    paisCodigo = form.paisCodigo,
                    nifEmisor = form.nifEmisor.trim().takeIf { it.isNotBlank() },
                    nifReceptor = form.nifReceptor.trim().takeIf { it.isNotBlank() },
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis()
                )

                if (form.id == 0L) {
                    invoiceRepository.insertInvoice(invoice)
                    sheetsSyncManager.syncExpense(invoice)
                } else {
                    invoiceRepository.updateInvoice(invoice)
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = "Factura guardada correctamente"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = "Error al guardar: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }
}
