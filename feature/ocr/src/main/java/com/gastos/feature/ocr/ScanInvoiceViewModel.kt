package com.gastos.feature.ocr

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.usecase.SaveIncomeUseCase
import com.gastos.domain.usecase.SaveInvoiceUseCase
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.AIResult
import com.gastos.feature.backup.SheetsSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanInvoiceUiState(
    val isLoading: Boolean = false,
    val processedImageUri: Uri? = null,
    val scanResult: AIResult? = null,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val error: String? = null
)

@HiltViewModel
class ScanInvoiceViewModel @Inject constructor(
    private val aiService: AIService,
    private val saveInvoiceUseCase: SaveInvoiceUseCase,
    private val saveIncomeUseCase: SaveIncomeUseCase,
    private val sheetsSyncManager: SheetsSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanInvoiceUiState())
    val uiState: StateFlow<ScanInvoiceUiState> = _uiState.asStateFlow()

    fun processImage(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    processedImageUri = imageUri,
                    error = null,
                    scanResult = null,
                    saveResult = null
                )
            }

            try {
                val result = aiService.processInvoiceFromImage(imageUri)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = if (!result.success) result.message else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error al procesar la imagen"
                    )
                }
            }
        }
    }

    fun saveInvoice(result: AIResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                when {
                    result.invoice != null -> {
                        val invoice = result.invoice!!

                        if (invoice.tipo == InvoiceType.INGRESO) {
                            val income = Income(
                                fecha = invoice.fecha,
                                concepto = invoice.proveedor,
                                monto = invoice.total,
                                moneda = invoice.moneda,
                                fuente = invoice.nifEmisor,
                                ivaPercent = invoice.ivaPercent,
                                irpfPercent = invoice.irpfPercent,
                                imagenUri = invoice.imagenUri,
                                notas = invoice.notas
                            )
                            saveIncomeUseCase(income)
                            sheetsSyncManager.syncIncome(income)
                            _uiState.update {
                                it.copy(isSaving = false, saveResult = "Ingreso guardado correctamente")
                            }
                        } else {
                            saveInvoiceUseCase(invoice, result.products)

                            sheetsSyncManager.syncExpense(invoice)
                            sheetsSyncManager.syncProducts(result.products, invoice.proveedor)

                            _uiState.update {
                                it.copy(isSaving = false, saveResult = "Gasto guardado correctamente")
                            }
                        }
                    }
                    // Ingreso detectado por texto
                    result.income != null -> {
                        saveIncomeUseCase(result.income!!)
                        sheetsSyncManager.syncIncome(result.income!!)
                        _uiState.update {
                            it.copy(isSaving = false, saveResult = "Ingreso guardado correctamente")
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(isSaving = false, saveResult = "No hay datos para guardar")
                        }
                    }
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

    fun clearResult() {
        _uiState.value = ScanInvoiceUiState()
    }

}
