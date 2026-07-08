package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.parseMoney
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
    /**
     * Total IVA incluido que paga el cliente.
     * Si la factura tiene productos, este total = suma(productos.subtotal).
     */
    val total: String = "",
    /**
     * IVA% global. Solo se aplica a la parte sin productos.
     * Para facturas 100% OCR, dejamos 0.0 (los productos llevan su IVA).
     */
    val ivaPercent: String = "21.0",
    val irpfPercent: String = "0.0",
    val baseImponible: String = "0.0",
    val cuotaIva: String = "0.0",
    val cuotaIrpf: String = "0.0",
    val paisCodigo: String = "ES",
    val nifEmisor: String = "",
    val nifReceptor: String = "",
    val notas: String = ""
) {
    /**
     * Recalcula los importes fiscales (base imponible, cuota IVA, cuota IRPF)
     * a partir del total y el IVA%.
     *
     * Convención: [total] es siempre el importe **IVA incluido** (lo que paga el cliente).
     *   - baseImponible = total / (1 + iva/100)
     *   - cuotaIva     = baseImponible * iva/100
     *   - cuotaIrpf    = baseImponible * irpf/100  (si irpf > 0)
     *
     * Nótese: si la factura tiene productos asociados, sus IVAs reales se
     * agregarán desde la lista de productos y este recálculo se usará solo
     * para la parte "manual" de la factura.
     */
    fun recalcFiscal(): EditInvoiceForm {
        val t = total.parseMoney() ?: 0.0
        val iva = ivaPercent.parseMoney() ?: 0.0
        val irpf = irpfPercent.parseMoney() ?: 0.0
        val base = if (t > 0) t / (1.0 + iva / 100.0) else 0.0
        val cuotaIva = base * iva / 100.0
        val cuotaIrpf = base * irpf / 100.0
        return copy(
            baseImponible = String.format(java.util.Locale.US, "%.2f", base),
            cuotaIva = String.format(java.util.Locale.US, "%.2f", cuotaIva),
            cuotaIrpf = String.format(java.util.Locale.US, "%.2f", cuotaIrpf)
        )
    }

    companion object {
        fun fresh(): EditInvoiceForm = EditInvoiceForm().recalcFiscal()

        /**
         * Normaliza un form cargado de la BD. Solo recalcula si el total > 0
         * pero la base/cuota derivada está vacía o 0 (caso típico: datos
         * viejos sin desglose). Si los derivados ya están bien, los respeta.
         */
        fun sanitize(form: EditInvoiceForm): EditInvoiceForm {
            val total = form.total.parseMoney() ?: 0.0
            val base = form.baseImponible.parseMoney() ?: 0.0
            val cuota = form.cuotaIva.parseMoney() ?: 0.0
            return if (total > 0.0 && (base == 0.0 || cuota == 0.0)) {
                form.copy().recalcFiscal()
            } else {
                form
            }
        }
    }
}

@HiltViewModel
class EditInvoiceViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditInvoiceUiState())
    val uiState: StateFlow<EditInvoiceUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditInvoiceForm.fresh())
    val form: StateFlow<EditInvoiceForm> = _form.asStateFlow()

    fun loadInvoice(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val invoice = invoiceRepository.getInvoiceById(id)
                if (invoice != null) {
                    val loaded = EditInvoiceForm.sanitize(
                        EditInvoiceForm(
                            id = invoice.id,
                            fecha = invoice.fecha,
                            proveedor = invoice.proveedor,
                            tipo = invoice.tipo,
                            moneda = invoice.moneda,
                            total = invoice.total.toString(),
                            ivaPercent = invoice.ivaPercent.toString(),
                            irpfPercent = invoice.irpfPercent.toString(),
                            baseImponible = invoice.baseImponible.toString(),
                            cuotaIva = invoice.cuotaIva.toString(),
                            // cuotaIrpf: leer si existe; si no, recalcular.
                            cuotaIrpf = if (invoice.cuotaIrpf > 0) {
                                String.format(java.util.Locale.US, "%.2f", invoice.cuotaIrpf)
                            } else {
                                String.format(
                                    java.util.Locale.US,
                                    "%.2f",
                                    invoice.baseImponible * invoice.irpfPercent / 100.0
                                )
                            },
                            paisCodigo = invoice.paisCodigo,
                            nifEmisor = invoice.nifEmisor ?: "",
                            nifReceptor = invoice.nifReceptor ?: "",
                            notas = invoice.notas ?: ""
                        )
                    )
                    _form.value = loaded
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
    fun updateTotal(value: String) { _form.update { it.copy(total = value).recalcFiscal() } }
    fun updateIvaPercent(value: String) { _form.update { it.copy(ivaPercent = value).recalcFiscal() } }
    fun updateIrpfPercent(value: String) { _form.update { it.copy(irpfPercent = value).recalcFiscal() } }
    fun updatePaisCodigo(value: String) { _form.update { it.copy(paisCodigo = value) } }
    fun updateNifEmisor(value: String) { _form.update { it.copy(nifEmisor = value) } }
    fun updateNifReceptor(value: String) { _form.update { it.copy(nifReceptor = value) } }
    fun updateNotas(value: String) { _form.update { it.copy(notas = value) } }

    fun saveInvoice() {
        viewModelScope.launch {
            val form = EditInvoiceForm.sanitize(_form.value)
            _form.value = form

            val total = form.total.parseMoney()
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
                    ivaPercent = form.ivaPercent.parseMoney() ?: 0.0,
                    irpfPercent = form.irpfPercent.parseMoney() ?: 0.0,
                    baseImponible = form.baseImponible.parseMoney() ?: 0.0,
                    cuotaIva = form.cuotaIva.parseMoney() ?: 0.0,
                    cuotaIrpf = form.cuotaIrpf.parseMoney() ?: 0.0,
                    paisCodigo = form.paisCodigo,
                    nifEmisor = form.nifEmisor.trim().takeIf { it.isNotBlank() },
                    nifReceptor = form.nifReceptor.trim().takeIf { it.isNotBlank() },
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis()
                )

                if (form.id == 0L) {
                    invoiceRepository.insertInvoice(invoice)
                } else {
                    invoiceRepository.updateInvoice(invoice)
                }
                // Sincroniza a la hoja correcta según el tipo de factura.
                if (form.tipo == InvoiceType.INGRESO) {
                    sheetsSyncManager.syncInvoiceIngreso(invoice)
                } else {
                    sheetsSyncManager.syncExpense(invoice)
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
