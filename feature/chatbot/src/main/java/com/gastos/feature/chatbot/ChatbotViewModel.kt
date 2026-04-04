package com.gastos.feature.chatbot

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.FinanceChatReplyBuilder
import com.gastos.feature.ai.IncomeQueryResultParser
import com.gastos.feature.voice.VoiceRecognitionService
import com.gastos.feature.voice.VoiceResult
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

private const val TAG = "ChatbotVM"

data class ChatbotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val isListening: Boolean = false
)

@HiltViewModel
class ChatbotViewModel @Inject constructor(
    private val aiService: AIService,
    private val financeChatReplyBuilder: FinanceChatReplyBuilder,
    private val voiceRecognitionService: VoiceRecognitionService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatbotUiState())
    val uiState: StateFlow<ChatbotUiState> = _uiState.asStateFlow()

    init {
        addSystemMessage(
            "¡Hola! Soy FinAI. Escríbeme como si fuéramos conocidos: puedo resumirte gastos, ingresos o apuntar lo que compres."
        )
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        _uiState.update {
            it.copy(messages = it.messages + ChatMessage.User(text), isProcessing = true)
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Sending to AI: $text")

                // ALL messages go through the AI — no hardcoded keyword interception
                val result = aiService.processCommand(text)
                Log.d(TAG, "AI result: success=${result.success}, msg=${result.message}, qr=${result.queryResult}")
                processAIResult(result, text)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                val err = "Uy, algo ha fallado al procesar eso: ${e.message}. ¿Lo intentamos de nuevo?"
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage.AI(err),
                        isProcessing = false
                    )
                }
                if (shouldRecordSessionForText(text)) {
                    aiService.recordSessionTurn(text, err)
                }
            }
        }
    }

    /** No guardar en memoria escaneos OCR ni placeholders sin texto del usuario. */
    private fun shouldRecordSessionForText(originalText: String): Boolean {
        val t = originalText.trim()
        if (t.isEmpty()) return false
        if (t.equals("imagen escaneada", ignoreCase = true)) return false
        if (t.startsWith("📷")) return false
        return true
    }

    private suspend fun processAIResult(result: AIResult, originalText: String) {
        val parsedIncome = result.queryResult?.let { IncomeQueryResultParser.parse(it) }
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
                } else {
                    val savedId = invoiceRepository.insertInvoice(invoice)
                    if (result.products.isNotEmpty()) {
                        val fe = invoice.fecha
                        val prov = invoice.proveedor
                        productRepository.insertProducts(
                            result.products.map {
                                it.copy(
                                    invoiceId = savedId,
                                    comercio = it.comercio ?: prov,
                                    fechaCompra = it.fechaCompra ?: fe
                                )
                            }
                        )
                    }
                }
            }
            parsedIncome != null -> {
                incomeRepository.insertIncome(parsedIncome)
            }
        }
        val reply = financeChatReplyBuilder.buildAssistantReply(result)
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage.AI(reply),
                isProcessing = false
            )
        }
        if (shouldRecordSessionForText(originalText)) {
            aiService.recordSessionTurn(originalText, reply)
        }
    }

    fun startVoiceInput() {
        _uiState.update { it.copy(isListening = true) }

        viewModelScope.launch {
            try {
                voiceRecognitionService.startListening().collect { voiceResult ->
                    if (voiceResult.isFinal) {
                        _uiState.update { it.copy(isListening = false) }
                        if (voiceResult.text.isNotBlank() && !voiceResult.text.contains("Escuchando") && !voiceResult.text.contains("Error")) {
                            sendMessage(voiceResult.text)
                        } else if (voiceResult.text.contains("Error") || voiceResult.text.contains("no disponible")) {
                            _uiState.update {
                                it.copy(
                                    messages = it.messages + ChatMessage.AI(
                                        "Ahora mismo no puedo usar el micrófono; escríbelo en el cuadro de texto y lo mismo vale."
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        messages = it.messages + ChatMessage.AI("La voz ha dado un fallo: ${e.message}. Mejor escribe un momento.")
                    )
                }
            }
        }
    }

    fun stopVoiceInput() {
        voiceRecognitionService.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    fun processImage(uri: Uri) {
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage.User("📷 Escaneando imagen..."), isProcessing = true)
        }

        viewModelScope.launch {
            try {
                val result = aiService.processInvoiceFromImage(uri)
                processAIResult(result, "imagen escaneada")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage.AI("No he podido leer bien la imagen: ${e.message}. ¿Otra foto más nítida o desde otra app?"),
                        isProcessing = false
                    )
                }
            }
        }
    }

    fun clearChat() {
        aiService.clearSessionConversation()
        _uiState.update { it.copy(messages = emptyList()) }
        addSystemMessage("Vale, empezamos conversación nueva. Cuando quieras.")
    }

    private fun addSystemMessage(text: String) {
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage.System(text))
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}
