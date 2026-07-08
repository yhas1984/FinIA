package com.gastos.feature.incomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.parseMoney
import com.gastos.repository.IncomeRepository
import com.gastos.feature.backup.SheetsSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditIncomeUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val income: Income? = null,
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
    val ivaPercent: String = "0.0",
    val irpfPercent: String = "0.0",
    val notas: String = ""
)

@HiltViewModel
class EditIncomeViewModel @Inject constructor(
    private val incomeRepository: IncomeRepository,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditIncomeUiState())
    val uiState: StateFlow<EditIncomeUiState> = _uiState.asStateFlow()

    private val _form = MutableStateFlow(EditIncomeForm())
    val form: StateFlow<EditIncomeForm> = _form.asStateFlow()

    fun loadIncome(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val income = incomeRepository.getIncomeById(id)
                if (income != null) {
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
                            ivaPercent = income.ivaPercent.toString(),
                            irpfPercent = income.irpfPercent.toString(),
                            notas = income.notas ?: ""
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, income = income) }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Ingreso no encontrado")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Error al cargar ingreso")
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
    fun updateIvaPercent(value: String) { _form.update { it.copy(ivaPercent = value) } }
    fun updateIrpfPercent(value: String) { _form.update { it.copy(irpfPercent = value) } }
    fun updateNotas(value: String) { _form.update { it.copy(notas = value) } }

    fun saveIncome() {
        viewModelScope.launch {
            val form = _form.value
            val monto = form.monto.parseMoney()
            if (monto == null || monto <= 0) {
                _uiState.update { it.copy(saveResult = "El monto debe ser un número positivo") }
                return@launch
            }
            if (form.concepto.isBlank()) {
                _uiState.update { it.copy(saveResult = "El concepto es obligatorio") }
                return@launch
            }

            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                val devengado = form.totalDevengado.parseMoney() ?: 0.0
                val neto = form.totalNeto.parseMoney() ?: 0.0
                val income = run {
                    // Coherencia: si el usuario rellenó devengado/neto, esos mandan.
                    // Si no, ambos = monto (no hay retenciones).
                    val finalDevengado = if (devengado > 0) devengado else monto
                    val finalNeto = if (neto > 0) neto else monto
                    Income(
                        id = form.id,
                        fecha = form.fecha,
                        concepto = form.concepto.trim(),
                        monto = monto,
                        totalDevengado = finalDevengado,
                        totalNeto = finalNeto,
                        moneda = form.moneda,
                        fuente = form.fuente.trim().takeIf { it.isNotBlank() },
                        ivaPercent = form.ivaPercent.parseMoney() ?: 0.0,
                        irpfPercent = form.irpfPercent.parseMoney() ?: 0.0,
                        notas = form.notas.trim().takeIf { it.isNotBlank() },
                        updatedAt = System.currentTimeMillis()
                    )
                }

                if (form.id == 0L) {
                    incomeRepository.insertIncome(income)
                    sheetsSyncManager.syncIncome(income)
                } else {
                    incomeRepository.updateIncome(income)
                    sheetsSyncManager.syncIncome(income)
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = "Ingreso guardado correctamente"
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
