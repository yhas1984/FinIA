package com.gastos.feature.incomes

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
import com.gastos.domain.model.Income
import com.gastos.domain.model.SUPPORTED_CURRENCIES
import com.gastos.domain.model.TransactionCategories
import com.gastos.repository.IncomeRepository
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.incomes.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditIncomeUiState(
    val defaultsReady: Boolean = true,
    val fieldErrors: Map<ManualField, String> = emptyMap(),
    val validationAttempt: Int = 0,
    val duplicates: List<DuplicateMatch> = emptyList(),
    val isLoading: Boolean = false,
    val saveState: SaveState = SaveState.Idle,
    val income: Income? = null,
    val availableCategories: List<String> = TransactionCategories.defaultIncomeCategories,
    val availableSubcategories: List<String> = emptyList(),
    val error: String? = null
) {
    val isSaving: Boolean get() = saveState == SaveState.Saving
    val saveResult: String? get() = (saveState as? SaveState.Error)?.message
}

data class EditIncomeForm(
    val manualAmountAdjusted: Boolean = false,
    val taxes: List<TaxFormRow> = emptyList(),
    val taxBase: String = "",
    val documentNumber: String = "",
    val issuerTaxId: String = "",
    val workerId: String = "",
    val payPeriod: String = "",
    val paymentKind: String = "",
    val payrollReference: String = "",
    val documentKind: String = "",
    val id: Long = 0,
    val fecha: Long = System.currentTimeMillis(),
    val concepto: String = "",
    val monto: String = "",
    val totalDevengado: String = "",
    val totalNeto: String = "",
    val moneda: String = "EUR",
    val paisCodigo: String = "ES",
    val fuente: String = "",
    val categoria: String = "",
    val isCustomCategory: Boolean = false,
    val subcategoria: String = "",
    val isCustomSubcategory: Boolean = false,
    val ivaPercent: String = "0.0",
    val irpfPercent: String = "0.0",
    val notas: String = ""
) : java.io.Serializable

@HiltViewModel
class EditIncomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val catalog: com.gastos.storage.CategoryCatalog? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val entryDefaults: com.gastos.repository.ManualEntryDefaultsProvider? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditIncomeUiState(defaultsReady = entryDefaults == null || savedStateHandle.get<Boolean>("defaultsReady") == true, fieldErrors = savedStateHandle.get<java.util.HashMap<String, String>>("fieldErrors").orEmpty().mapKeys { ManualField.valueOf(it.key) }, saveState = savedStateHandle.get<String>("saveError")?.let(SaveState::Error) ?: SaveState.Idle))
    val uiState: StateFlow<EditIncomeUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(savedStateHandle.get<EditIncomeForm>("draft") ?: EditIncomeForm())
    val form: StateFlow<EditIncomeForm> = _form.asStateFlow()

    private val restoredDraft: Boolean = savedStateHandle.contains("draft")
    private var originalIncome: Income? = savedStateHandle.get<String>("original")?.let {
        runCatching { kotlinx.serialization.json.Json.decodeFromString(Income.serializer(), it) }.getOrNull()
    }
    private var baseline: EditIncomeForm = savedStateHandle.get<EditIncomeForm>("baseline") ?: _form.value
    val hasChanges: Boolean get() = _form.value != baseline
    private fun rememberBaseline() { baseline = _form.value; savedStateHandle["baseline"] = baseline }
    private var duplicateForm: EditIncomeForm? = null
    private var existingSubcategories: List<String?> = emptyList()

    private var catalogValues: List<com.gastos.domain.model.Category> = emptyList()
    private fun refreshCatalog() {
        val roots = catalogValues.filter { it.kind == com.gastos.domain.model.DocumentKind.INCOME && it.parentId.isEmpty() && !it.archived }
        val parent = roots.firstOrNull { it.name == _form.value.categoria }
        _uiState.update { it.copy(availableCategories = roots.map { category -> category.name },
            availableSubcategories = catalogValues.filter { parent != null && it.parentId == parent.id && !it.archived }.map { category -> category.name }) }
    }
    init {
        _uiState.update { it.copy(income = originalIncome) }
        viewModelScope.launch { _form.collect { if (_uiState.value.defaultsReady || restoredDraft || it.id != 0L) savedStateHandle["draft"] = it } }
        viewModelScope.launch { _uiState.collect { savedStateHandle["saveError"] = it.saveResult; savedStateHandle["fieldErrors"] = java.util.HashMap(it.fieldErrors.mapKeys { entry -> entry.key.name }) } }
        catalog?.let { source -> viewModelScope.launch { source.initialize(); source.categories.collect { catalogValues = it; refreshCatalog() } } }
        loadAvailableCategories()
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
            catch (failure: Exception) { _uiState.update { it.copy(error = failure.message ?: context.getString(com.gastos.common.R.string.manual_preferences_failed)) } }
        }
    }

    private fun validationFailure(errors: Map<ManualField, String>) {
        _uiState.update { it.copy(fieldErrors = errors, validationAttempt = it.validationAttempt + 1,
            saveState = SaveState.Error(errors.values.first())) }
    }

    private fun loadAvailableCategories() {
        if (catalog != null) return
        viewModelScope.launch {
            val existing = incomeRepository.getAllIncomes().first()
            val existingCategories = existing.map { it.categoria }
            existingSubcategories = existing.map { it.subcategoria }
            _uiState.update {
                it.copy(
                    availableCategories = TransactionCategories.availableCategories(
                        defaults = TransactionCategories.defaultIncomeCategories,
                        existing = existingCategories
                    ),
                    availableSubcategories = TransactionCategories.availableSubcategories(
                        defaults = TransactionCategories.suggestedSubcategories(_form.value.categoria, isIncome = true),
                        existing = existingSubcategories
                    )
                )
            }
        }
    }

    fun loadIncome(id: Long, locale: Locale = Locale.getDefault()) {
        if (_form.value.id == id) { _uiState.update { it.copy(defaultsReady = true) }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val income = incomeRepository.getIncomeById(id)
                if (income != null) {
                    originalIncome = income
                    savedStateHandle["original"] = kotlinx.serialization.json.Json.encodeToString(Income.serializer(), income)
                    _form.update {
                        EditIncomeForm(
                            manualAmountAdjusted = income.manualAmountAdjusted,
                            taxes = income.taxes.map { TaxFormRow.from(it, locale) },
                            taxBase = income.evidence?.document?.taxBase?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
                            documentNumber = income.evidence?.document?.number.orEmpty(),
                            issuerTaxId = income.evidence?.document?.issuerTaxId.orEmpty(),
                            workerId = income.evidence?.document?.workerId.orEmpty(),
                            payPeriod = income.evidence?.document?.payPeriod.orEmpty(),
                            paymentKind = income.evidence?.document?.paymentKind.orEmpty(),
                            payrollReference = income.evidence?.document?.payrollReference.orEmpty(),
                            documentKind = income.evidence?.document?.kind.orEmpty(),
                            id = income.id,
                            fecha = income.fecha,
                            concepto = income.concepto,
                            monto = LocalizedNumbers.format(income.monto, locale),
                            totalDevengado = if (income.totalDevengado > 0) LocalizedNumbers.format(income.totalDevengado, locale) else "",
                            totalNeto = if (income.totalNeto > 0) LocalizedNumbers.format(income.totalNeto, locale) else "",
                            moneda = income.moneda,
                            paisCodigo = income.evidence?.document?.country.orEmpty(),
                            fuente = income.fuente ?: "",
                            categoria = income.categoria.orEmpty(),
                            isCustomCategory = income.categoria?.let {
                                TransactionCategories.canonicalIncomeCategory(it) !in TransactionCategories.defaultIncomeCategories
                            } ?: false,
                            subcategoria = income.subcategoria.orEmpty(),
                            isCustomSubcategory = income.subcategoria?.let {
                                TransactionCategories.suggestedSubcategories(income.categoria, isIncome = true).none { suggested ->
                                    TransactionCategories.normalizeKey(suggested) == TransactionCategories.normalizeKey(it)
                                }
                            } ?: false,
                            ivaPercent = income.ivaPercent?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
                            irpfPercent = LocalizedNumbers.format(income.irpfPercent, locale),
                            notas = income.notas ?: ""
                        )
                    }
                    rememberBaseline()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            income = income,
                            defaultsReady = true,
                            availableSubcategories = TransactionCategories.availableSubcategories(
                                defaults = TransactionCategories.suggestedSubcategories(income.categoria, isIncome = true),
                                existing = existingSubcategories
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = context.getString(R.string.income_not_found))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: context.getString(R.string.load_income_error))
                }
            }
        }
    }

    fun updateTaxes(rows: List<TaxFormRow>) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(taxes = rows, manualAmountAdjusted = false) } }
    fun updateTaxBase(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(taxBase = value, manualAmountAdjusted = false) } }
    fun addTax(locale: Locale) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return;
        _form.update { form -> form.copy(taxes = form.taxes + TaxFormRow(), manualAmountAdjusted = false) }
    }

    fun updateConcepto(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(concepto = value) } }
    fun updateFecha(value: Long) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(fecha = value) } }
    fun updateMonto(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(monto = value) } }
    fun updateTotalDevengado(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(totalDevengado = value) } }
    fun updateTotalNeto(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(totalNeto = value) } }
    fun updateMoneda(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(moneda = value) } }
    fun updatePaisCodigo(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(paisCodigo = value) } }
    fun updateFuente(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(fuente = value) } }
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
                    defaults = TransactionCategories.suggestedSubcategories(value, isIncome = true),
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
    fun updateIvaPercent(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(ivaPercent = value, manualAmountAdjusted = false) } }
    fun updateIrpfPercent(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(irpfPercent = value, manualAmountAdjusted = false) } }
    fun updateNotas(value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return; _form.update { it.copy(notas = value) } }
    fun updateDocumentField(field: String, value: String) { if (_uiState.value.isSaving || _uiState.value.saveState == SaveState.Success) return;
        _form.update { when (field) {
            "number" -> it.copy(documentNumber = value)
            "issuerTaxId" -> it.copy(issuerTaxId = value)
            "workerId" -> it.copy(workerId = value)
            "payPeriod" -> it.copy(payPeriod = value)
            "paymentKind" -> it.copy(paymentKind = value)
            "payrollReference" -> it.copy(payrollReference = value)
            "kind" -> it.copy(documentKind = value)
            else -> it
        } }
    }

    fun saveIncome(locale: Locale = Locale.getDefault(), distinctFrom: Set<String> = emptySet()) {
        if (!_uiState.value.defaultsReady) return
        if (_uiState.value.saveState == SaveState.Saving || _uiState.value.saveState == SaveState.Success) return
        _uiState.update { it.copy(saveState = SaveState.Saving, duplicates = emptyList(), fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val form = _form.value
            val errors = linkedMapOf<ManualField, String>()
            val enteredAmount = LocalizedNumbers.parse(form.monto, locale)
            if (enteredAmount == null || !enteredAmount.isFinite() || enteredAmount <= 0)
                errors[ManualField.AMOUNT] = context.getString(R.string.validation_amount_positive)
            if (form.concepto.isBlank()) errors[ManualField.CONCEPT] = context.getString(R.string.validation_concept_required)
            if (form.moneda.trim().uppercase() !in SUPPORTED_CURRENCIES)
                errors[ManualField.CURRENCY] = context.getString(R.string.validation_currency_not_supported)
            fun checkPercentage(field: ManualField, raw: String, optional: Boolean = false) {
                if (optional && raw.isBlank()) return
                val value = LocalizedNumbers.parse(raw, locale)
                if (value == null || !value.isFinite() || value !in 0.0..100.0)
                    errors[field] = context.getString(R.string.validation_percentages_range)
            }
            if (form.taxes.isEmpty()) checkPercentage(ManualField.VAT, form.ivaPercent, true)
            checkPercentage(ManualField.WITHHOLDING, form.irpfPercent)
            for ((field, raw) in listOf(ManualField.GROSS to form.totalDevengado, ManualField.NET to form.totalNeto)) {
                if (raw.isNotBlank() && LocalizedNumbers.parse(raw, locale)?.let { it.isFinite() && it > 0 } != true)
                    errors[field] = context.getString(R.string.validation_gross_net_positive)
            }
            if (errors.isNotEmpty()) { validationFailure(errors); return@launch }
            val monto = LocalizedNumbers.parse(form.monto, locale)
            if (monto == null || !monto.isFinite() || monto <= 0) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_amount_positive))) }
                return@launch
            }
            val devengado = LocalizedNumbers.parse(form.totalDevengado, locale)
            val neto = LocalizedNumbers.parse(form.totalNeto, locale)
            val iva = LocalizedNumbers.parse(form.ivaPercent, locale)
            val irpf = LocalizedNumbers.parse(form.irpfPercent, locale)
            val invalidOptionalAmount = listOf(form.totalDevengado to devengado, form.totalNeto to neto)
                .any { (raw, value) -> raw.isNotBlank() && (value == null || !value.isFinite() || value <= 0.0) }
            if (invalidOptionalAmount) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_gross_net_positive))) }
                return@launch
            }
            if ((form.taxes.isEmpty() && form.ivaPercent.isNotBlank() && (iva == null || !iva.isFinite() || iva !in 0.0..100.0)) ||
                irpf == null || !irpf.isFinite() || irpf !in 0.0..100.0
            ) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_percentages_range))) }
                return@launch
            }
            val parsedTaxes: List<DocumentTax>? = TaxFormRow.parseAll(form.taxes, locale, form.moneda)
            val base: Double? = LocalizedNumbers.parse(form.taxBase, locale)
            val withholding: Double? = if (irpf == originalIncome?.irpfPercent) originalIncome?.evidence?.document?.withholdingAmount
                else base?.times(irpf / 100.0)
            val taxTotals = if (!parsedTaxes.isNullOrEmpty()) reconcileTaxForm(parsedTaxes, monto, base, withholding, form.moneda) else null
            if (parsedTaxes == null || (form.taxes.isNotEmpty() && ((taxTotals == null && !form.manualAmountAdjusted) || (form.taxBase.isNotBlank() && base == null)))) {
                validationFailure(mapOf(ManualField.TAX_BREAKDOWN to context.getString(com.gastos.common.R.string.taxes_inconsistent)))
                return@launch
            }
            val currency = form.moneda.trim().uppercase()
            if (currency !in SUPPORTED_CURRENCIES) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_currency_not_supported))) }
                return@launch
            }
            if (form.concepto.isBlank()) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(R.string.validation_concept_required))) }
                return@launch
            }



            try {
                // Conserva la imagen y la fecha de creación del registro
                // original (no se editan desde el formulario).
                val original = originalIncome
                val income = Income(
                    categoryId = original?.categoryId, subcategoryId = original?.subcategoryId,
                    sourceMimeType = original?.sourceMimeType, sourceName = original?.sourceName,
                    origin = original?.origin ?: "MANUAL", manualAmountAdjusted = original?.manualAmountAdjusted ?: false,
                    financialRevision = original?.financialRevision ?: 0,
                    taxes = parsedTaxes,
                    evidence = (original?.evidence ?: DocumentEvidence(ScannedDocument())).copy(
                        distinctFrom = distinctFrom,
                        document = (original?.evidence?.document ?: ScannedDocument()).copy(
                            date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(form.fecha)),
                            kind = form.documentKind.takeIf(String::isNotBlank),
                            number = form.documentNumber.takeIf(String::isNotBlank),
                            issuerTaxId = form.issuerTaxId.takeIf(String::isNotBlank),
                            issuer = form.fuente.takeIf(String::isNotBlank) ?: form.concepto,
                            workerId = form.workerId.takeIf(String::isNotBlank),
                            payPeriod = form.payPeriod.takeIf(String::isNotBlank),
                            paymentKind = form.paymentKind.takeIf(String::isNotBlank),
                            payrollReference = form.payrollReference.takeIf(String::isNotBlank),
                            payroll = original?.evidence?.document?.payroll?.let { details ->
                                if (form.fecha != original.fecha) details.copy(dateBasis = com.gastos.domain.model.PayrollDateBasis.MANUAL) else details
                            },
                            country = form.paisCodigo.takeIf(String::isNotBlank), currency = currency, total = if (form.manualAmountAdjusted) original?.evidence?.document?.total else monto,
                            gross = if (form.manualAmountAdjusted) original?.evidence?.document?.gross else devengado, net = if (form.manualAmountAdjusted) original?.evidence?.document?.net else neto,
                            taxes = parsedTaxes, taxesComplete = if (parsedTaxes.isNotEmpty()) true else null,
                            taxBase = taxTotals?.base ?: original?.evidence?.document?.taxBase,
                            vatAmount = taxTotals?.charges ?: original?.evidence?.document?.vatAmount,
                            withholdingAmount = taxTotals?.withholding ?: original?.evidence?.document?.withholdingAmount,
                            vatPercent = if (parsedTaxes.isNotEmpty()) DocumentTaxes.singleRate(parsedTaxes) else iva, withholdingPercent = irpf
                        )
                    ).let { edited -> com.gastos.domain.model.DocumentProvenance.markManual(original?.evidence, edited.document).copy(distinctFrom = distinctFrom).let { evidence ->
                        if (original?.concepto != form.concepto) evidence.copy(fieldOrigins = evidence.fieldOrigins + ("concept" to DocumentFieldOrigin.MANUAL)) else evidence
                    } },
                    documentUuid = original?.documentUuid ?: java.util.UUID.randomUUID().toString(),
                    driveAccountId = original?.driveAccountId,
                    driveContentHash = original?.driveContentHash,
                    driveSyncError = original?.driveSyncError,
                    driveFileId = original?.driveFileId,
                    driveWebViewLink = original?.driveWebViewLink,
                    driveUploadPending = original?.driveUploadPending ?: false,
                    id = form.id,
                    fecha = form.fecha,
                    concepto = form.concepto.trim(),
                    monto = monto,
                    totalDevengado = devengado ?: 0.0,
                    totalNeto = neto ?: 0.0,
                    moneda = currency,
                    fuente = form.fuente.trim().takeIf { it.isNotBlank() },
                    categoria = TransactionCategories.canonicalIncomeCategory(form.categoria),
                    subcategoria = TransactionCategories.normalizeCategory(form.subcategoria),
                    ivaPercent = if (parsedTaxes.isNotEmpty()) DocumentTaxes.singleRate(parsedTaxes) else iva,
                    irpfPercent = irpf,
                    imagenUri = original?.imagenUri,
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                val saved = if (form.id == 0L) {
                    income.copy(id = incomeRepository.insertIncome(income))
                } else {
                    incomeRepository.updateIncome(income)
                    income.copy(financialRevision = income.financialRevision + 1)
                }
                originalIncome = saved
                _form.update { it.copy(id = saved.id) }
                _uiState.update { it.copy(saveState = SaveState.Success) }
                // Startup reconciliation recovers a commit interrupted before enqueue.
                try {
                    sheetsSyncManager.upsertIncome(saved)
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
                        saveState = SaveState.Error(context.getString(R.string.save_income_error_prefix, e.message.orEmpty()))
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
        saveIncome(locale, confirmed)
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveState = SaveState.Idle) }
    }
}
