package com.gastos.feature.incomes

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
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val income: Income? = null,
    val availableCategories: List<String> = TransactionCategories.defaultIncomeCategories,
    val availableSubcategories: List<String> = emptyList(),
    val error: String? = null
)

data class EditIncomeForm(
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

    fun loadIncome(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val income = incomeRepository.getIncomeById(id)
                if (income != null) {
                    originalIncome = income
                    _form.update {
                        EditIncomeForm(
                            id = income.id,
                            fecha = income.fecha,
                            concepto = income.concepto,
                            monto = income.monto.toString(),
                            totalDevengado = if (income.totalDevengado > 0) income.totalDevengado.toString() else "",
                            totalNeto = if (income.totalNeto > 0) income.totalNeto.toString() else "",
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
                            ivaPercent = income.ivaPercent.toString(),
                            irpfPercent = income.irpfPercent.toString(),
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: context.getString(R.string.load_income_error))
                }
            }
        }
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

    fun saveIncome() {
        viewModelScope.launch {
            val form = _form.value
            val monto = form.monto.toDoubleOrNull()
            if (monto == null || !monto.isFinite() || monto <= 0) {
                _uiState.update { it.copy(saveResult = context.getString(R.string.validation_amount_positive)) }
                return@launch
            }
            val devengado = form.totalDevengado.toDoubleOrNull()
            val neto = form.totalNeto.toDoubleOrNull()
            val iva = form.ivaPercent.toDoubleOrNull()
            val irpf = form.irpfPercent.toDoubleOrNull()
            val invalidOptionalAmount = listOf(form.totalDevengado to devengado, form.totalNeto to neto)
                .any { (raw, value) -> raw.isNotBlank() && (value == null || !value.isFinite() || value <= 0.0) }
            if (invalidOptionalAmount) {
                _uiState.update { it.copy(saveResult = context.getString(R.string.validation_gross_net_positive)) }
                return@launch
            }
            if (iva == null || !iva.isFinite() || iva !in 0.0..100.0 ||
                irpf == null || !irpf.isFinite() || irpf !in 0.0..100.0
            ) {
                _uiState.update { it.copy(saveResult = context.getString(R.string.validation_percentages_range)) }
                return@launch
            }
            val currency = form.moneda.trim().uppercase()
            if (currency !in SUPPORTED_CURRENCIES) {
                _uiState.update { it.copy(saveResult = context.getString(R.string.validation_currency_not_supported)) }
                return@launch
            }
            if (form.concepto.isBlank()) {
                _uiState.update { it.copy(saveResult = context.getString(R.string.validation_concept_required)) }
                return@launch
            }

            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                // Conserva la imagen y la fecha de creación del registro
                // original (no se editan desde el formulario).
                val original = originalIncome
                val income = Income(
                    id = form.id,
                    fecha = form.fecha,
                    concepto = form.concepto.trim(),
                    monto = monto,
                    totalDevengado = devengado ?: monto,
                    totalNeto = neto ?: monto,
                    moneda = currency,
                    fuente = form.fuente.trim().takeIf { it.isNotBlank() },
                    categoria = TransactionCategories.canonicalIncomeCategory(form.categoria),
                    subcategoria = TransactionCategories.normalizeCategory(form.subcategoria),
                    ivaPercent = iva,
                    irpfPercent = irpf,
                    imagenUri = original?.imagenUri,
                    notas = form.notas.trim().takeIf { it.isNotBlank() },
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                if (form.id == 0L) {
                    val incomeId = incomeRepository.insertIncome(income)
                    sheetsSyncManager.upsertIncome(income.copy(id = incomeId))
                } else {
                    incomeRepository.updateIncome(income)
                    sheetsSyncManager.upsertIncome(income)
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = context.getString(R.string.saved_ok)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = context.getString(R.string.save_income_error_prefix, e.message.orEmpty())
                    )
                }
            }
        }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }
}
