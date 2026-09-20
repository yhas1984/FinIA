package com.gastos.feature.incomes

import com.gastos.domain.model.*
import com.gastos.common.LocalizedNumbers
import com.gastos.common.SaveState
import com.gastos.common.TaxFormRow
import com.gastos.common.reconcileTaxForm
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
    val fuente: String = "",
    val categoria: String = "",
    val isCustomCategory: Boolean = false,
    val subcategoria: String = "",
    val isCustomSubcategory: Boolean = false,
    val ivaPercent: String = "0.0",
    val irpfPercent: String = "0.0",
    val notas: String = ""
)

@HiltViewModel
class EditIncomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditIncomeUiState())
    val uiState: StateFlow<EditIncomeUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditIncomeForm())
    val form: StateFlow<EditIncomeForm> = _form.asStateFlow()

    private var originalIncome: Income? = null
    private var duplicateForm: EditIncomeForm? = null
    private var existingSubcategories: List<String?> = emptyList()

    init {
        loadAvailableCategories()
    }

    private fun loadAvailableCategories() {
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
        if (_form.value.id == id) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val income = incomeRepository.getIncomeById(id)
                if (income != null) {
                    originalIncome = income
                    _form.update {
                        EditIncomeForm(
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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            income = income,
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

    fun updateTaxes(rows: List<TaxFormRow>) { _form.update { it.copy(taxes = rows) } }
    fun updateTaxBase(value: String) { _form.update { it.copy(taxBase = value) } }
    fun addTax(locale: Locale) {
        _form.update { form -> form.copy(taxes = form.taxes + TaxFormRow()) }
    }

    fun updateConcepto(value: String) { _form.update { it.copy(concepto = value) } }
    fun updateFecha(value: Long) { _form.update { it.copy(fecha = value) } }
    fun updateMonto(value: String) { _form.update { it.copy(monto = value) } }
    fun updateTotalDevengado(value: String) { _form.update { it.copy(totalDevengado = value) } }
    fun updateTotalNeto(value: String) { _form.update { it.copy(totalNeto = value) } }
    fun updateMoneda(value: String) { _form.update { it.copy(moneda = value) } }
    fun updateFuente(value: String) { _form.update { it.copy(fuente = value) } }
    fun updateCategoria(value: String) { _form.update { it.copy(categoria = value) } }
    fun updateSubcategoria(value: String) { _form.update { it.copy(subcategoria = value) } }
    fun selectCategory(value: String?, isCustomCategory: Boolean) {
        _form.update {
            it.copy(
                categoria = value.orEmpty(),
                isCustomCategory = isCustomCategory,
                subcategoria = if (value.isNullOrBlank()) "" else it.subcategoria,
                isCustomSubcategory = if (value.isNullOrBlank()) false else it.isCustomSubcategory
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
    }
    fun selectSubcategory(value: String?, isCustom: Boolean) {
        _form.update {
            it.copy(
                subcategoria = value.orEmpty(),
                isCustomSubcategory = isCustom
            )
        }
    }
    fun updateIvaPercent(value: String) { _form.update { it.copy(ivaPercent = value) } }
    fun updateIrpfPercent(value: String) { _form.update { it.copy(irpfPercent = value) } }
    fun updateNotas(value: String) { _form.update { it.copy(notas = value) } }
    fun updateDocumentField(field: String, value: String) {
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
        if (_uiState.value.saveState == SaveState.Saving || _uiState.value.saveState == SaveState.Success) return
        _uiState.update { it.copy(saveState = SaveState.Saving, duplicates = emptyList()) }
        viewModelScope.launch {
            val form = _form.value
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
            if (parsedTaxes == null || (form.taxes.isNotEmpty() && (taxTotals == null || (form.taxBase.isNotBlank() && base == null)))) {
                _uiState.update { it.copy(saveState = SaveState.Error(context.getString(com.gastos.common.R.string.taxes_inconsistent))) }
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
                            currency = currency, total = monto, gross = devengado, net = neto,
                            taxes = parsedTaxes, taxesComplete = if (parsedTaxes.isNotEmpty()) true else null,
                            taxBase = taxTotals?.base ?: original?.evidence?.document?.taxBase,
                            vatAmount = taxTotals?.charges ?: original?.evidence?.document?.vatAmount,
                            withholdingAmount = taxTotals?.withholding ?: original?.evidence?.document?.withholdingAmount,
                            vatPercent = if (parsedTaxes.isNotEmpty()) DocumentTaxes.singleRate(parsedTaxes) else iva, withholdingPercent = irpf
                        )
                    ),
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
                    income
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
