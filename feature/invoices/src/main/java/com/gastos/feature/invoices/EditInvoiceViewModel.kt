package com.gastos.feature.invoices

import com.gastos.domain.model.*
import com.gastos.common.LocalizedNumbers
import com.gastos.common.ManualField
import com.gastos.common.SaveState
import com.gastos.common.TaxFormRow
import com.gastos.common.reconcileTaxForm
import java.util.Locale
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.SUPPORTED_CURRENCIES
import com.gastos.domain.model.TransactionCategories
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.invoices.R
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
    val products: List<Product> = emptyList(),
    val defaultsReady: Boolean = true,
    val fieldErrors: Map<ManualField, String> = emptyMap(),
    val validationAttempt: Int = 0,
    val duplicates: List<DuplicateMatch> = emptyList(),
    val isLoading: Boolean = false,
    val saveState: SaveState = SaveState.Idle,
    val invoice: Invoice? = null,
    val availableCategories: List<String> = TransactionCategories.defaultExpenseCategories,
    val availableSubcategories: List<String> = emptyList(),
    val error: String? = null
) {
    val isSaving: Boolean get() = saveState == SaveState.Saving
    val saveResult: String? get() = (saveState as? SaveState.Error)?.message
}

/**
 * Form de edición de FACTURA (siempre un GASTO).
 *
 * El tipo "Ingreso" se quitó de esta pantalla porque los ingresos tienen su
 * propia edición en la pestaña Ingresos.
 */
data class EditInvoiceForm(
    val manualAmountAdjusted: Boolean = false,
    val taxes: List<TaxFormRow> = emptyList(),
    val withheldAmountRead: Double? = null,
    val withheldRateRead: Double? = null,
    val id: Long = 0,
    val fecha: Long = System.currentTimeMillis(),
    val proveedor: String = "",
    val moneda: String = "EUR",
    val total: String = "",
    val numeroFactura: String = "",
    val baseImponible: String = "",
    val cuotaIva: String = "",
    val ivaPercent: String = "0",
    val irpfPercent: String = "0.0",
    val paisCodigo: String = "ES",
    val nifEmisor: String = "",
    val nifReceptor: String = "",
    val categoria: String = "",
    val isCustomCategory: Boolean = false,
    val isCustomSubcategory: Boolean = false,
    val subcategoria: String = "",
    val notas: String = ""
) : java.io.Serializable {

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
        val ivaPercent: Double?,
        val irpfPercent: Double,
        val baseImponible: Double?,
        val ivaAmount: Double?,
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
    fun recalcFiscal(locale: Locale = Locale.getDefault()): FiscalBreakdown? {
        val total = LocalizedNumbers.parse(total, locale)?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val iva: Double? = LocalizedNumbers.parse(ivaPercent, locale)?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (taxes.isEmpty() && ivaPercent.isNotBlank() && iva == null) return null
        val irpf: Double = LocalizedNumbers.parse(irpfPercent, locale)?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val enteredBase: Double? = LocalizedNumbers.parse(baseImponible, locale)?.takeIf { it >= 0.0 }
        if (baseImponible.isNotBlank() && enteredBase == null) return null
        val enteredCuota: Double? = LocalizedNumbers.parse(cuotaIva, locale)?.takeIf { it >= 0.0 }
        if (cuotaIva.isNotBlank() && enteredCuota == null) return null
        if (manualAmountAdjusted) {
            val parsed = TaxFormRow.parseAll(taxes, locale, moneda) ?: return null
            return FiscalBreakdown(total, if (parsed.isEmpty()) iva else DocumentTaxes.singleRate(parsed), irpf,
                enteredBase, enteredCuota ?: parsed.takeIf { it.isNotEmpty() }?.let { DocumentTaxes.total(it, TaxEffect.CHARGE) },
                withheldAmountRead ?: DocumentTaxes.total(parsed, TaxEffect.WITHHOLDING) ?: 0.0, total)
        }
        if (taxes.isNotEmpty()) {
            val parsed: List<DocumentTax> = TaxFormRow.parseAll(taxes, locale, moneda) ?: return null
            val withholding: Double? = if (withheldRateRead == irpf) withheldAmountRead else enteredBase?.times(irpf / 100.0)
            val result = reconcileTaxForm(parsed, total, enteredBase, withholding, moneda) ?: return null
            return FiscalBreakdown(total, DocumentTaxes.singleRate(parsed), irpf, result.base, result.charges, result.withholding, total)
        }
        val withholdingDeducted: Boolean = withheldAmountRead != null
        val base: Double? = enteredBase ?: iva?.let { total / (1.0 + it / 100.0 - if (withholdingDeducted) irpf / 100.0 else 0.0) }
        if (base != null && !base.isFinite()) return null
        val irpfAmount: Double = if (withholdingDeducted && withheldRateRead == irpf) withheldAmountRead!! else (base?.times(irpf / 100.0) ?: 0.0)
        val ivaAmount: Double? = enteredCuota ?: base?.let { total + (if (withholdingDeducted) irpfAmount else 0.0) - it }
        val neto: Double = if (withholdingDeducted) total else total - irpfAmount
        return FiscalBreakdown(total, iva, irpf, base, ivaAmount, irpfAmount, neto)
    }
}

@HiltViewModel
class EditInvoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val invoiceRepository: InvoiceRepository,
    private val productRepository: ProductRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val catalog: com.gastos.storage.CategoryCatalog? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val entryDefaults: com.gastos.repository.ManualEntryDefaultsProvider? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditInvoiceUiState(defaultsReady = entryDefaults == null || savedStateHandle.get<Boolean>("defaultsReady") == true, fieldErrors = savedStateHandle.get<java.util.HashMap<String, String>>("fieldErrors").orEmpty().mapKeys { ManualField.valueOf(it.key) }, saveState = savedStateHandle.get<String>("saveError")?.let(SaveState::Error) ?: SaveState.Idle))
    val uiState: StateFlow<EditInvoiceUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(savedStateHandle.get<EditInvoiceForm>("draft") ?: EditInvoiceForm())
    val form: StateFlow<EditInvoiceForm> = _form.asStateFlow()

    private val restoredDraft: Boolean = savedStateHandle.contains("draft")
    private var originalInvoice: Invoice? = savedStateHandle.get<String>("original")?.let {
        runCatching { kotlinx.serialization.json.Json.decodeFromString(Invoice.serializer(), it) }.getOrNull()
    }
    private var baseline: EditInvoiceForm = savedStateHandle.get<EditInvoiceForm>("baseline") ?: _form.value
    val hasChanges: Boolean get() = _form.value != baseline
    private fun rememberBaseline() { baseline = _form.value; savedStateHandle["baseline"] = baseline }
    private var duplicateForm: EditInvoiceForm? = null
    private var existingSubcategories: List<String?> = emptyList()

    private var catalogValues: List<com.gastos.domain.model.Category> = emptyList()
    private fun refreshCatalog() {
        val roots = catalogValues.filter { it.kind == com.gastos.domain.model.DocumentKind.EXPENSE && it.parentId.isEmpty() && !it.archived }
        val parent = roots.firstOrNull { it.name == _form.value.categoria }
        _uiState.update { it.copy(availableCategories = roots.map { category -> category.name },
            availableSubcategories = catalogValues.filter { parent != null && it.parentId == parent.id && !it.archived }.map { category -> category.name }) }
    }
    init {
        _uiState.update { it.copy(invoice = originalInvoice) }
        viewModelScope.launch { _form.collect { if (_uiState.value.defaultsReady || restoredDraft || it.id != 0L) savedStateHandle["draft"] = it } }
        viewModelScope.launch { _uiState.collect { savedStateHandle["saveError"] = it.saveResult; savedStateHandle["fieldErrors"] = java.util.HashMap(it.fieldErrors.mapKeys { entry -> entry.key.name }) } }
        catalog?.let { source -> viewModelScope.launch { source.initialize(); source.categories.collect { catalogValues = it; refreshCatalog() } } }
        if (_form.value.id > 0) observeProducts(_form.value.id)
        loadAvailableCategories()
    }

    private fun observeProducts(id: Long) {
        viewModelScope.launch {
            try { productRepository.getProductsByInvoiceId(id).collect { rows -> _uiState.update { it.copy(products = rows) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _uiState.update { it.copy(error = failure.message ?: context.getString(com.gastos.common.R.string.manual_preferences_failed)) } }
        }
    }

    private var defaultsJob: kotlinx.coroutines.Job? = null
    fun prepareDefaults() {
        if (_uiState.value.defaultsReady || defaultsJob?.isActive == true) return
        defaultsJob = viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            try {
                val defaults = entryDefaults?.manualEntryDefaults()
                if (!restoredDraft && _form.value.id == 0L && defaults != null) {
                    _form.update { it.copy(moneda = defaults.currency, paisCodigo = defaults.country) }
                    rememberBaseline()
                }
                savedStateHandle["draft"] = _form.value
                savedStateHandle["defaultsReady"] = true
                _uiState.update { it.copy(defaultsReady = true, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _uiState.update { it.copy(error = failure.message) } }
        }
    }

    private fun validationFailure(errors: Map<ManualField, String>) {
        _uiState.update { it.copy(fieldErrors = errors, validationAttempt = it.validationAttempt + 1,
            saveState = SaveState.Error(errors.values.first())) }
    }

    private fun loadAvailableCategories() {
        if (catalog != null) return
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

    fun loadInvoice(id: Long, locale: Locale = Locale.getDefault()) {
        if (_form.value.id == id) { _uiState.update { it.copy(defaultsReady = true) }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val invoice = invoiceRepository.getInvoiceById(id)
                if (invoice != null) {
                    originalInvoice = invoice
                    savedStateHandle["original"] = kotlinx.serialization.json.Json.encodeToString(Invoice.serializer(), invoice)
                    _form.update {
                        EditInvoiceForm(
                            manualAmountAdjusted = invoice.manualAmountAdjusted,
                            taxes = invoice.taxes.map { TaxFormRow.from(it, locale) },
                            withheldAmountRead = invoice.evidence?.document?.withholdingAmount,
                            withheldRateRead = invoice.irpfPercent,
                            id = invoice.id,
                            fecha = invoice.fecha,
                            proveedor = invoice.proveedor,
                            moneda = invoice.moneda,
                            total = LocalizedNumbers.format(invoice.total, locale),
                            numeroFactura = invoice.numeroFactura ?: "",
                            baseImponible = invoice.baseImponible?.let { LocalizedNumbers.format(it, locale) } ?: "",
                            cuotaIva = invoice.cuotaIva?.let { LocalizedNumbers.format(it, locale) } ?: "",
                            ivaPercent = invoice.ivaPercent?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
                            irpfPercent = LocalizedNumbers.format(invoice.irpfPercent, locale),
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
                    rememberBaseline()
                    _uiState.update {
                        it.copy(
                            availableSubcategories = TransactionCategories.availableSubcategories(
                                defaults = TransactionCategories.suggestedSubcategories(invoice.categoria, isIncome = false),
                                existing = existingSubcategories
                            )
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, invoice = invoice, defaultsReady = true) }
                    observeProducts(id)
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = context.getString(R.string.invoice_not_found))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: context.getString(R.string.load_invoice_error))
                }
            }
        }
    }

    fun updateTaxes(rows: List<TaxFormRow>) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(taxes = rows, cuotaIva = "", manualAmountAdjusted = false) } }
    fun addTax(locale: Locale) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return;
        _form.update { form ->
            if (form.taxes.isNotEmpty()) form.copy(taxes = form.taxes + TaxFormRow(), manualAmountAdjusted = false) else {
                val fiscal = form.recalcFiscal(locale)
                val row = TaxFormRow.from(DocumentTax(name = if (form.paisCodigo == "ES") "IVA" else "Tax",
                    rate = fiscal?.ivaPercent, base = fiscal?.baseImponible, amount = fiscal?.ivaAmount,
                    treatment = if (fiscal?.ivaPercent == 0.0) TaxTreatment.ZERO_RATED else TaxTreatment.TAXABLE), locale)
                form.copy(taxes = listOf(row), manualAmountAdjusted = false)
            }
        }
    }

    fun updateProveedor(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(proveedor = value) } }
    fun updateFecha(value: Long) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(fecha = value) } }
    fun updateMoneda(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(moneda = value) } }
    fun updateTotal(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(total = value) } }
    fun updateNumeroFactura(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(numeroFactura = value) } }
    fun updateBaseImponible(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(baseImponible = value, manualAmountAdjusted = false) } }
    fun updateCuotaIva(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(cuotaIva = value, manualAmountAdjusted = false) } }
    fun updateIvaPercent(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(ivaPercent = value, baseImponible = "", cuotaIva = "", manualAmountAdjusted = false) } }
    fun updateIrpfPercent(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(irpfPercent = value, manualAmountAdjusted = false) } }
    fun updatePaisCodigo(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(paisCodigo = value) } }
    fun updateNifEmisor(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(nifEmisor = value) } }
    fun updateNifReceptor(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(nifReceptor = value) } }
    fun updateCategoria(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(categoria = value) } }
    fun updateSubcategoria(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(subcategoria = value) } }
    fun selectCategory(value: String?, isCustomCategory: Boolean) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return;
        _form.update {
            it.copy(
                categoria = value.orEmpty(),
                isCustomCategory = isCustomCategory,
                subcategoria = if (value.orEmpty() != it.categoria) "" else it.subcategoria,
                isCustomSubcategory = if (value.orEmpty() != it.categoria) false else it.isCustomSubcategory
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
        if (catalog != null) refreshCatalog()
    }
    fun selectSubcategory(value: String?, isCustom: Boolean) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return;
        _form.update {
            it.copy(
                subcategoria = value.orEmpty(),
                isCustomSubcategory = isCustom
            )
        }
    }
    fun updateNotas(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(notas = value) } }

    fun saveInvoice(locale: Locale = Locale.getDefault(), distinctFrom: Set<String> = emptySet()) {
        if (!_uiState.value.defaultsReady) return
        if (_uiState.value.saveState == SaveState.Saving || _uiState.value.saveState == SaveState.Success) return
        _uiState.update { it.copy(saveState = SaveState.Saving, duplicates = emptyList(), fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val form = _form.value
            val errors = linkedMapOf<ManualField, String>()
            val enteredAmount = LocalizedNumbers.parse(form.total, locale)
            if (enteredAmount == null || !enteredAmount.isFinite() || enteredAmount <= 0)
                errors[ManualField.AMOUNT] = context.getString(R.string.validation_total_percentages)
            if (form.proveedor.isBlank()) errors[ManualField.CONCEPT] = context.getString(R.string.validation_provider_required)
            if (form.moneda.trim().uppercase() !in SUPPORTED_CURRENCIES)
                errors[ManualField.CURRENCY] = context.getString(R.string.validation_currency_not_supported)
            fun checkPercentage(field: ManualField, raw: String, optional: Boolean = false) {
                if (optional && raw.isBlank()) return
                val value = LocalizedNumbers.parse(raw, locale)
                if (value == null || !value.isFinite() || value !in 0.0..100.0)
                    errors[field] = context.getString(R.string.validation_total_percentages)
            }
            if (form.taxes.isEmpty()) checkPercentage(ManualField.VAT, form.ivaPercent, true)
            checkPercentage(ManualField.WITHHOLDING, form.irpfPercent)
            for ((field, raw) in listOf(ManualField.BASE to form.baseImponible, ManualField.TAX_AMOUNT to form.cuotaIva)) {
                if (raw.isNotBlank() && LocalizedNumbers.parse(raw, locale)?.let { it.isFinite() && it >= 0 } != true)
                    errors[field] = context.getString(R.string.validation_base_vat_mismatch)
            }
            if (errors.isNotEmpty()) { validationFailure(errors); return@launch }
            val fiscal = form.recalcFiscal(locale)
            if (fiscal == null || fiscal.total <= 0.0) {
                validationFailure(mapOf(ManualField.TAX_BREAKDOWN to context.getString(if (form.taxes.isNotEmpty()) com.gastos.common.R.string.taxes_inconsistent else R.string.validation_total_percentages)))
                return@launch
            }
            if (form.proveedor.isBlank()) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_provider_required))) }
                return@launch
            }
            val enteredBase = LocalizedNumbers.parse(form.baseImponible, locale)
            val enteredCuota = LocalizedNumbers.parse(form.cuotaIva, locale)
            val withholding = if (form.withheldAmountRead != null) fiscal.irpfAmount else 0.0
            val parsedTaxes: List<DocumentTax> = TaxFormRow.parseAll(form.taxes, locale, form.moneda) ?: emptyList()
            val legacyMixed: Boolean = originalInvoice?.evidence?.document?.lines?.mapNotNull { it.vatPercent }?.distinct()?.size?.let { it > 1 } == true
            val rate: Double? = fiscal.ivaPercent.takeUnless { legacyMixed && form.taxes.isEmpty() }
            val fiscalValuesAreConsistent = when {
                form.manualAmountAdjusted -> true
                form.taxes.isNotEmpty() -> parsedTaxes.size == form.taxes.size
                enteredBase != null && enteredCuota != null ->
                    abs(enteredBase + enteredCuota - withholding - fiscal.total) <= FISCAL_TOLERANCE &&
                        (rate == null || abs(enteredCuota - enteredBase * rate / 100.0) <= FISCAL_TOLERANCE)
                enteredBase != null && rate != null -> abs(enteredBase * (1.0 + rate / 100.0) - withholding - fiscal.total) <= FISCAL_TOLERANCE
                enteredCuota != null && rate != null -> {
                    val inferredBase = fiscal.total + withholding - enteredCuota
                    inferredBase >= -FISCAL_TOLERANCE && abs(enteredCuota - inferredBase * rate / 100.0) <= FISCAL_TOLERANCE
                }
                else -> true
            }
            if (!fiscalValuesAreConsistent) {
                validationFailure(mapOf(ManualField.BASE to context.getString(R.string.validation_base_vat_mismatch)))
                return@launch
            }
            val currency = form.moneda.trim().uppercase()
            if (currency !in SUPPORTED_CURRENCIES) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_currency_not_supported))) }
                return@launch
            }



            try {
                // Las facturas son siempre GASTO (los ingresos se editan en su
                // propia pestaña). Forzamos el tipo aquí por si el registro
                // antiguo era INGRESO y se ha migrado al tab de Ingresos.
                // Conserva campos no editables (createdAt, imagenUri,
                // ocrRawText) del registro original para no perder la foto
                // ni el texto OCR al guardar.
                val original = originalInvoice
                val invoice = Invoice(
                    categoryId = original?.categoryId, subcategoryId = original?.subcategoryId,
                    sourceMimeType = original?.sourceMimeType, sourceName = original?.sourceName,
                    origin = original?.origin ?: "MANUAL", manualAmountAdjusted = original?.manualAmountAdjusted ?: false,
                    financialRevision = original?.financialRevision ?: 0,
                    taxes = parsedTaxes,
                    evidence = (original?.evidence ?: DocumentEvidence(ScannedDocument())).copy(
                        distinctFrom = distinctFrom,
                        document = (original?.evidence?.document ?: ScannedDocument()).copy(
                            date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(form.fecha)),
                            number = form.numeroFactura.takeIf(String::isNotBlank), issuer = form.proveedor,
                            issuerTaxId = form.nifEmisor.takeIf(String::isNotBlank), recipientTaxId = form.nifReceptor.takeIf(String::isNotBlank),
                            country = form.paisCodigo, currency = currency, total = if (form.manualAmountAdjusted) original?.evidence?.document?.total else fiscal.total,
                            taxes = parsedTaxes, taxesComplete = if (parsedTaxes.isNotEmpty()) true else null,
                            taxBase = fiscal.baseImponible, vatAmount = fiscal.ivaAmount, vatPercent = fiscal.ivaPercent, withholdingPercent = fiscal.irpfPercent,
                            withholdingAmount = if (form.taxes.isNotEmpty() || form.withheldAmountRead != null) fiscal.irpfAmount else original?.evidence?.document?.withholdingAmount
                        )
                    ).let { edited -> com.gastos.domain.model.DocumentProvenance.markManual(original?.evidence, edited.document).copy(distinctFrom = distinctFrom) },
                    documentUuid = original?.documentUuid ?: java.util.UUID.randomUUID().toString(),
                    driveAccountId = original?.driveAccountId,
                    driveContentHash = original?.driveContentHash,
                    driveSyncError = original?.driveSyncError,
                    id = form.id,
                    fecha = form.fecha,
                    proveedor = form.proveedor.trim(),
                    tipo = InvoiceType.GASTO,
                    categoria = TransactionCategories.canonicalExpenseCategory(form.categoria),
                    subcategoria = TransactionCategories.normalizeCategory(form.subcategoria),
                    moneda = currency,
                    total = fiscal.total,
                    numeroFactura = form.numeroFactura.trim().takeIf { it.isNotBlank() },
                    baseImponible = if (form.taxes.isEmpty() && original != null && form.baseImponible.isBlank()) {
                        null
                    } else {
                        fiscal.baseImponible
                    },
                    cuotaIva = if (form.taxes.isEmpty() && original != null && form.cuotaIva.isBlank()) {
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

                val saved = if (form.id == 0L) {
                    invoice.copy(id = invoiceRepository.insertInvoice(invoice))
                } else {
                    invoiceRepository.updateInvoice(invoice)
                    invoice.copy(financialRevision = invoice.financialRevision + 1)
                }
                originalInvoice = saved
                _form.update { it.copy(id = saved.id) }
                _uiState.update { it.copy(saveState = SaveState.Success) }
                // Startup reconciliation recovers a commit interrupted before enqueue.
                try {
                    val products = productRepository.getProductsByInvoiceId(saved.id).first()
                    sheetsSyncManager.syncExpense(saved, products)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The record is durable; remote synchronization remains pending.
                }

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (duplicate: DuplicateDocumentException) {
                duplicateForm = form
                _uiState.update { it.copy(duplicates = duplicate.matches, saveState = SaveState.Error(context.getString(R.string.document_duplicate))) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        saveState = SaveState.Error(context.getString(R.string.save_invoice_error_prefix, e.message.orEmpty()))
                    )
                }
            }
        }
    }

    fun dismissDuplicate() { _uiState.update { it.copy(duplicates = emptyList()) } }

    fun confirmDistinct(locale: Locale) {
        val matches: List<DuplicateMatch> = _uiState.value.duplicates
        if (matches.any { it.strength == DuplicateStrength.STRONG }) return
        val confirmed: Set<String> = if (_form.value == duplicateForm) matches.map { it.existing.version }.toSet() else emptySet()
        saveInvoice(locale, confirmed)
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveState = SaveState.Idle) }
    }

    private companion object {
        private const val FISCAL_TOLERANCE = 0.02
    }
}
