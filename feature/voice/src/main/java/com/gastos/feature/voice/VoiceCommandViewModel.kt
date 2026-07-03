package com.gastos.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.AIResult
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceCommandUiState(
    val isListening: Boolean = false,
    val recognizedText: String = "",
    val aiResult: AIResult? = null,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val error: String? = null
)

@HiltViewModel
class VoiceCommandViewModel @Inject constructor(
    private val voiceRecognitionService: VoiceRecognitionService,
    private val aiService: AIService,
    private val invoiceRepository: InvoiceRepository,
    private val productRepository: ProductRepository,
    private val incomeRepository: IncomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceCommandUiState())
    val uiState: StateFlow<VoiceCommandUiState> = _uiState.asStateFlow()

    fun startListening() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isListening = true,
                    recognizedText = "",
                    aiResult = null,
                    error = null,
                    saveResult = null
                )
            }

            try {
                voiceRecognitionService.startListening().collect { voiceResult ->
                    if (voiceResult.isFinal) {
                        _uiState.update {
                            it.copy(
                                isListening = false,
                                recognizedText = voiceResult.text
                            )
                        }
                        processWithAI(voiceResult.text)
                    } else {
                        _uiState.update {
                            it.copy(recognizedText = voiceResult.text)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        error = e.message ?: "Error en reconocimiento de voz"
                    )
                }
            }
        }
    }

    fun stopListening() {
        voiceRecognitionService.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    fun processTextCommand(text: String) {
        _uiState.update {
            it.copy(
                recognizedText = text,
                aiResult = null,
                error = null,
                saveResult = null
            )
        }
        processWithAI(text)
    }

    private fun processWithAI(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }

            try {
                val result = aiService.processCommand(text)
                _uiState.update { it.copy(aiResult = result) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al procesar con IA")
                }
            }
        }
    }

    fun saveCommand(result: AIResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                when {
                    // Gasto (factura)
                    result.invoice != null && result.invoice!!.tipo != InvoiceType.INGRESO -> {
                        val invoice = result.invoice!!
                        val savedInvoiceId = invoiceRepository.insertInvoice(invoice)

                        if (result.products.isNotEmpty()) {
                            val productsWithInvoiceId = result.products.map {
                                it.copy(invoiceId = savedInvoiceId)
                            }
                            productRepository.insertProducts(productsWithInvoiceId)
                        }

                        _uiState.update {
                            it.copy(isSaving = false, saveResult = "Gasto guardado correctamente")
                        }
                    }
                    // Ingreso detectado por OCR (factura marcada como ingreso)
                    result.invoice != null && result.invoice!!.tipo == InvoiceType.INGRESO -> {
                        val invoice = result.invoice!!
                        val income = Income(
                            fecha = invoice.fecha,
                            concepto = invoice.proveedor,
                            monto = invoice.total,
                            moneda = invoice.moneda,
                            fuente = invoice.nifEmisor,
                            ivaPercent = invoice.ivaPercent,
                            irpfPercent = invoice.irpfPercent,
                            notas = invoice.notas
                        )
                        incomeRepository.insertIncome(income)
                        _uiState.update {
                            it.copy(isSaving = false, saveResult = "Ingreso guardado correctamente")
                        }
                    }
                    // Ingreso detectado por texto
                    result.income != null -> {
                        incomeRepository.insertIncome(result.income!!)
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
        _uiState.value = VoiceCommandUiState()
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}