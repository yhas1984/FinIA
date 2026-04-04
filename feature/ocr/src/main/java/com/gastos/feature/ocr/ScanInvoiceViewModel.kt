package com.gastos.feature.ocr

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.IncomeQueryResultParser
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    private val invoiceRepository: InvoiceRepository,
    private val productRepository: ProductRepository,
    private val incomeRepository: IncomeRepository
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
                val incomePayload = result.queryResult?.let { IncomeQueryResultParser.parse(it) }
                when {
                    result.invoice != null -> {
                        val invoice = result.invoice!!
                        
                        if (invoice.tipo == InvoiceType.INGRESO) {
                            val liquido = invoice.total
                            val devengado = invoice.ingresoDevengado.takeIf { it > 0.01 } ?: liquido
                            val ded = invoice.ingresoDeducciones.takeIf { it > 0.01 }
                                ?: (devengado - liquido).takeIf { it > 0.01 } ?: 0.0
                            val income = Income(
                                fecha = invoice.fecha,
                                concepto = invoice.conceptoIngreso?.takeIf { it.isNotBlank() } ?: invoice.proveedor,
                                monto = liquido,
                                totalDevengado = devengado,
                                totalDeducciones = ded,
                                totalNeto = liquido,
                                moneda = invoice.moneda,
                                tipoIngreso = invoice.ingresoTipo,
                                fuente = invoice.nifEmisor,
                                ivaPercent = invoice.ivaPercent,
                                irpfPercent = invoice.irpfPercent,
                                imagenUri = invoice.imagenUri,
                                notas = invoice.notas,
                                categoria = invoice.categoria
                            )
                            incomeRepository.insertIncome(income)
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    saveResult = "Ingreso guardado correctamente"
                                )
                            }
                        } else {
                            val savedInvoiceId = invoiceRepository.insertInvoice(invoice)

                            if (result.products.isNotEmpty()) {
                                val fe = invoice.fecha
                                val prov = invoice.proveedor
                                val productsWithInvoiceId = result.products.map {
                                    it.copy(
                                        invoiceId = savedInvoiceId,
                                        comercio = it.comercio ?: prov,
                                        fechaCompra = it.fechaCompra ?: fe
                                    )
                                }
                                productRepository.insertProducts(productsWithInvoiceId)
                            }

                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    saveResult = "Gasto guardado correctamente"
                                )
                            }
                        }
                    }
                    incomePayload != null -> {
                        incomeRepository.insertIncome(incomePayload)
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveResult = "Ingreso guardado correctamente"
                            )
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveResult = "No hay datos para guardar"
                            )
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
