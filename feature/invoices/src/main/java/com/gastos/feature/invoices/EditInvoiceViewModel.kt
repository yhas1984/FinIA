package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
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

/**
 * Form de edición de FACTURA (siempre un GASTO).
 *
 * El tipo "Ingreso" se quitó de esta pantalla porque los ingresos tienen su
 * propia edición en la pestaña Ingresos.
 */
data class EditInvoiceForm(
    val id: Long = 0,
    val fecha: Long = System.currentTimeMillis(),
    val proveedor: String = "",
    val moneda: String = "EUR",
    val total: String = "",
    val ivaPercent: String = "21.0",
    val irpfPercent: String = "0.0",
    val paisCodigo: String = "ES",
    val nifEmisor: String = "",
    val nifReceptor: String = "",
    val notas: String = ""
) {

    /**
     * Resultado inmutable del cálculo fiscal: cantidades derivadas a
     * partir del [total] y los porcentajes de [ivaPercent]/[irpfPercent].
     *
     * Interpretación (factura emitida/recibida en España):
     *   - [total] es el importe con IVA incluido (bruto).
     *   - [baseImponible] = total / (1 + iva%)       → sin IVA
     *   - [ivaAmount]     = total - baseImponible     → cuota de IVA
     *   - [irpfAmount]    = baseImponible * irpf%     → retención
     *   - [totalNeto]     = total - irpfAmount        → a ingresar/cobrar
     */
    data class FiscalBreakdown(
        val total: Double,
        val ivaPercent: Double,
        val irpfPercent: Double,
        val baseImponible: Double,
        val ivaAmount: Double,
        val irpfAmount: Double,
        val totalNeto: Double
    )

    /**
     * Recalcula el desglose fiscal del formulario a partir de los campos
     * de texto. Es una función PURA (sin Android, sin I/O) para que pueda
     * testearse con JUnit sin Robolectric.
     *
     * Devuelve `null` si el total o los porcentajes no son numéricos
     * válidos (igual que hacía `saveInvoice()` antes con `toDoubleOrNull`).
     */
    fun recalcFiscal(): FiscalBreakdown? {
        val total = total.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val iva = ivaPercent.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val irpf = irpfPercent.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null
        val base = total / (1.0 + iva / 100.0)
        val ivaAmount = total - base
        val irpfAmount = base * irpf / 100.0
        val neto = total - irpfAmount
        return FiscalBreakdown(
            total = total,
            ivaPercent = iva,
            irpfPercent = irpf,
            baseImponible = base,
            ivaAmount = ivaAmount,
            irpfAmount = irpfAmount,
            totalNeto = neto
        )
    }
}

@HiltViewModel
class EditInvoiceViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditInvoiceUiState())
    val uiState: StateFlow<EditInvoiceUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditInvoiceForm())
    val form: StateFlow<EditInvoiceForm> = _form.asStateFlow()

    private var originalInvoice: Invoice? = null

    fun loadInvoice(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val invoice = invoiceRepository.getInvoiceById(id)
                if (invoice != null) {
                    originalInvoice = invoice
                    _form.update {
                        EditInvoiceForm(
                            id = invoice.id,
                            fecha = invoice.fecha,
                            proveedor = invoice.proveedor,
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
                // Las facturas son siempre GASTO (los ingresos se editan en su
                // propia pestaña). Forzamos el tipo aquí por si el registro
                // antiguo era INGRESO y se ha migrado al tab de Ingresos.
                // Conserva campos no editables (createdAt, imagenUri,
                // ocrRawText) del registro original para no perder la foto
                // ni el texto OCR al guardar.
                val original = originalInvoice
                val invoice = Invoice(
                    id = form.id,
                    fecha = form.fecha,
                    proveedor = form.proveedor.trim(),
                    tipo = InvoiceType.GASTO,
                    moneda = form.moneda,
                    total = total,
                    ivaPercent = form.ivaPercent.toDoubleOrNull() ?: 0.0,
                    irpfPercent = form.irpfPercent.toDoubleOrNull() ?: 0.0,
                    paisCodigo = form.paisCodigo,
                    nifEmisor = form.nifEmisor.trim().takeIf { it.isNotBlank() },
                    nifReceptor = form.nifReceptor.trim().takeIf { it.isNotBlank() },
                    imagenUri = original?.imagenUri,
                    ocrRawText = original?.ocrRawText,
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                if (form.id == 0L) {
                    val invoiceId = invoiceRepository.insertInvoice(invoice)
                    sheetsSyncManager.upsertExpense(invoice.copy(id = invoiceId))
                } else {
                    invoiceRepository.updateInvoice(invoice)
                    sheetsSyncManager.upsertExpense(invoice)
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
