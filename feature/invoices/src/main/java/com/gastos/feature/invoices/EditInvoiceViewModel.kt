package com.gastos.feature.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.SUPPORTED_CURRENCIES
import com.gastos.domain.model.TransactionCategories
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import com.gastos.feature.backup.SheetsSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

data class EditInvoiceUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val invoice: Invoice? = null,
    val availableCategories: List<String> = TransactionCategories.defaultExpenseCategories,
    val availableSubcategories: List<String> = emptyList(),
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
    val numeroFactura: String = "",
    val baseImponible: String = "",
    val cuotaIva: String = "",
    val ivaPercent: String = "21.0",
    val irpfPercent: String = "0.0",
    val paisCodigo: String = "ES",
    val nifEmisor: String = "",
    val nifReceptor: String = "",
    val categoria: String = "",
    val isCustomCategory: Boolean = false,
    val isCustomSubcategory: Boolean = false,
    val subcategoria: String = "",
    val notas: String = ""
) {

    /**
     * Resultado inmutable del cálculo fiscal: cantidades derivadas a
     * partir del [total] y los porcentajes de [ivaPercent]/[irpfPercent].
     *
     * Interpretación (factura emitida/recibida en España):
     *   - [total] es el importe con IVA incluido (bruto).
     *   - [baseImponible] = OCR/manual value or total / (1 + iva%)
     *   - [ivaAmount]     = OCR/manual value or total - baseImponible
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
        val total = total.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val iva = ivaPercent.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val irpf = irpfPercent.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val enteredBase = baseImponible.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        if (baseImponible.isNotBlank() && enteredBase == null) return null
        val enteredCuota = cuotaIva.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        if (cuotaIva.isNotBlank() && enteredCuota == null) return null
        val base = enteredBase
            ?: total / (1.0 + iva / 100.0)
        val ivaAmount = enteredCuota
            ?: total - base
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
    private val productRepository: ProductRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditInvoiceUiState())
    val uiState: StateFlow<EditInvoiceUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditInvoiceForm())
    val form: StateFlow<EditInvoiceForm> = _form.asStateFlow()

    private var originalInvoice: Invoice? = null
    private var existingSubcategories: List<String?> = emptyList()

    init {
        loadAvailableCategories()
    }

    private fun loadAvailableCategories() {
        viewModelScope.launch {
            val invoices = invoiceRepository.getAllInvoices().first()
            val existing = invoices.map { it.categoria }
            existingSubcategories = invoices.mapNotNull { it.subcategoria }
            _uiState.update {
                it.copy(
                    availableCategories = TransactionCategories.availableCategories(
                        defaults = TransactionCategories.defaultExpenseCategories,
                        existing = existing
                    )
                )
            }
        }
    }

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
                            numeroFactura = invoice.numeroFactura ?: "",
                            baseImponible = invoice.baseImponible?.toString() ?: "",
                            cuotaIva = invoice.cuotaIva?.toString() ?: "",
                            ivaPercent = invoice.ivaPercent.toString(),
                            irpfPercent = invoice.irpfPercent.toString(),
                            paisCodigo = invoice.paisCodigo,
                            nifEmisor = invoice.nifEmisor ?: "",
                            nifReceptor = invoice.nifReceptor ?: "",
                            categoria = invoice.categoria.orEmpty(),
                            isCustomCategory = invoice.categoria?.let {
                                TransactionCategories.canonicalExpenseCategory(it) !in TransactionCategories.defaultExpenseCategories
                            } ?: false,
                            isCustomSubcategory = invoice.subcategoria?.let { sub -> sub !in TransactionCategories.suggestedSubcategories(invoice.categoria, isIncome = false) } ?: false,
                            subcategoria = invoice.subcategoria.orEmpty(),
                            notas = invoice.notas ?: ""
                        )
                    }
                    _uiState.update {
                        it.copy(
                            availableSubcategories = TransactionCategories.availableSubcategories(
                                defaults = TransactionCategories.suggestedSubcategories(invoice.categoria, isIncome = false),
                                existing = existingSubcategories
                            )
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
    fun updateNumeroFactura(value: String) { _form.update { it.copy(numeroFactura = value) } }
    fun updateBaseImponible(value: String) { _form.update { it.copy(baseImponible = value) } }
    fun updateCuotaIva(value: String) { _form.update { it.copy(cuotaIva = value) } }
    fun updateIvaPercent(value: String) { _form.update { it.copy(ivaPercent = value) } }
    fun updateIrpfPercent(value: String) { _form.update { it.copy(irpfPercent = value) } }
    fun updatePaisCodigo(value: String) { _form.update { it.copy(paisCodigo = value) } }
    fun updateNifEmisor(value: String) { _form.update { it.copy(nifEmisor = value) } }
    fun updateNifReceptor(value: String) { _form.update { it.copy(nifReceptor = value) } }
    fun updateCategoria(value: String) { _form.update { it.copy(categoria = value) } }
    fun updateSubcategoria(value: String) { _form.update { it.copy(subcategoria = value) } }
    fun selectCategory(value: String?, isCustomCategory: Boolean) {
        _form.update {
            it.copy(
                categoria = value.orEmpty(),
                isCustomCategory = isCustomCategory
            )
        }
        _uiState.update {
            it.copy(
                availableSubcategories = TransactionCategories.availableSubcategories(
                    defaults = TransactionCategories.suggestedSubcategories(value, isIncome = false),
                    existing = existingSubcategories
                )
            )
        }
    }
    fun selectSubcategory(value: String?, isCustom: Boolean) {
        _form.update {
            it.copy(
                subcategoria = value.orEmpty(),
                isCustomSubcategory = isCustom
            )
        }
    }
    fun updateNotas(value: String) { _form.update { it.copy(notas = value) } }

    fun saveInvoice() {
        viewModelScope.launch {
            val form = _form.value
            val fiscal = form.recalcFiscal()
            if (fiscal == null || fiscal.total <= 0.0) {
                _uiState.update {
                    it.copy(saveResult = "Revisa el total y los porcentajes (deben estar entre 0 y 100)")
                }
                return@launch
            }
            if (form.proveedor.isBlank()) {
                _uiState.update { it.copy(saveResult = "El proveedor es obligatorio") }
                return@launch
            }
            val enteredBase = form.baseImponible.toDoubleOrNull()
            val enteredCuota = form.cuotaIva.toDoubleOrNull()
            val fiscalValuesAreConsistent = when {
                enteredBase != null && enteredCuota != null -> {
                    val rateQuota = enteredBase * fiscal.ivaPercent / 100.0
                    abs(enteredBase + enteredCuota - fiscal.total) <= FISCAL_TOLERANCE &&
                        abs(enteredCuota - rateQuota) <= FISCAL_TOLERANCE
                }
                enteredBase != null -> abs(
                    enteredBase * (1.0 + fiscal.ivaPercent / 100.0) - fiscal.total
                ) <= FISCAL_TOLERANCE
                enteredCuota != null -> {
                    val inferredBase = fiscal.total - enteredCuota
                    inferredBase >= -FISCAL_TOLERANCE &&
                        abs(enteredCuota - inferredBase * fiscal.ivaPercent / 100.0) <= FISCAL_TOLERANCE
                }
                else -> true
            }
            if (!fiscalValuesAreConsistent) {
                _uiState.update {
                    it.copy(saveResult = "La base y la cuota IVA no coinciden con el total y el porcentaje")
                }
                return@launch
            }
            val currency = form.moneda.trim().uppercase()
            if (currency !in SUPPORTED_CURRENCIES) {
                _uiState.update { it.copy(saveResult = "La moneda seleccionada no está soportada") }
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
                    categoria = TransactionCategories.canonicalExpenseCategory(form.categoria),
                    subcategoria = TransactionCategories.normalizeCategory(form.subcategoria),
                    moneda = currency,
                    total = fiscal.total,
                    numeroFactura = form.numeroFactura.trim().takeIf { it.isNotBlank() },
                    baseImponible = if (original != null && form.baseImponible.isBlank()) {
                        null
                    } else {
                        fiscal.baseImponible
                    },
                    cuotaIva = if (original != null && form.cuotaIva.isBlank()) {
                        null
                    } else {
                        fiscal.ivaAmount
                    },
                    ivaPercent = fiscal.ivaPercent,
                    irpfPercent = fiscal.irpfPercent,
                    paisCodigo = form.paisCodigo,
                    nifEmisor = form.nifEmisor.trim().takeIf { it.isNotBlank() },
                    nifReceptor = form.nifReceptor.trim().takeIf { it.isNotBlank() },
                    imagenUri = original?.imagenUri,
                    driveFileId = original?.driveFileId,
                    driveWebViewLink = original?.driveWebViewLink,
                    driveUploadPending = original?.driveUploadPending ?: false,
                    ocrRawText = original?.ocrRawText,
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                if (form.id == 0L) {
                    val invoiceId = invoiceRepository.insertInvoice(invoice)
                    sheetsSyncManager.syncExpense(invoice.copy(id = invoiceId), emptyList())
                } else {
                    invoiceRepository.updateInvoice(invoice)
                    val products = productRepository.getProductsByInvoiceId(invoice.id).first()
                    sheetsSyncManager.syncExpense(invoice, products)
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

    private companion object {
        private const val FISCAL_TOLERANCE = 0.02
    }
}
