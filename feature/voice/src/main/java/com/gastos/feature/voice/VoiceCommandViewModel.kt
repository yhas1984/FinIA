package com.gastos.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.FinanceChatReplyBuilder
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

data class VoiceCommandUiState(
    val isListening: Boolean = false,
    val recognizedText: String = "",
    val aiResult: AIResult? = null,
    /** Misma redacción que en el chat (consultas incluidas); coherencia con memoria de sesión. */
    val assistantReply: String? = null,
    val isSaving: Boolean = false,
    val saveResult: String? = null,
    val error: String? = null
)

@HiltViewModel
class VoiceCommandViewModel @Inject constructor(
    private val voiceRecognitionService: VoiceRecognitionService,
    private val aiService: AIService,
    private val financeChatReplyBuilder: FinanceChatReplyBuilder,
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
                    assistantReply = null,
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
                assistantReply = null,
                error = null,
                saveResult = null
            )
        }
        processWithAI(text)
    }

    private fun processWithAI(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, assistantReply = null) }

            try {
                val result = aiService.processCommand(text)
                val reply = financeChatReplyBuilder.buildAssistantReply(result)
                if (text.isNotBlank()) {
                    aiService.recordSessionTurn(text.trim(), reply)
                }
                _uiState.update { it.copy(aiResult = result, assistantReply = reply) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al procesar con IA", assistantReply = null)
                }
            }
        }
    }

    fun saveCommand(result: AIResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            try {
                val incomePayload = result.queryResult?.let { IncomeQueryResultParser.parse(it) }
                when {
                    result.invoice != null -> {
                        val invoice = result.invoice!!
                        val savedInvoiceId = invoiceRepository.insertInvoice(invoice)

                        if (result.products.isNotEmpty()) {
                            val productsWithInvoiceId = result.products.map {
                                it.copy(invoiceId = savedInvoiceId)
                            }
                            productRepository.insertProducts(productsWithInvoiceId)
                        }

                        val tipoLabel = if (invoice.tipo == com.gastos.domain.model.InvoiceType.GASTO) "Gasto" else "Ingreso"
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveResult = "$tipoLabel guardado correctamente"
                            )
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
        _uiState.value = VoiceCommandUiState()
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}