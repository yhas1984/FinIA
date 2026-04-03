package com.gastos.feature.chatbot

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.voice.VoiceRecognitionService
import com.gastos.feature.voice.VoiceResult
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
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
    private val voiceRecognitionService: VoiceRecognitionService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatbotUiState())
    val uiState: StateFlow<ChatbotUiState> = _uiState.asStateFlow()

    init {
        addSystemMessage("¡Hola! Soy tu asistente FinAI. Puedo ayudarte a registrar gastos, ingresos y consultar tus finanzas.")
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        _uiState.update {
            it.copy(messages = it.messages + ChatMessage.User(text), isProcessing = true)
        }

        viewModelScope.launch {
            try {
                // Detect financial queries BEFORE sending to AI
                val lowerText = text.lowercase()
                Log.d(TAG, "Processing: $text")
                
                val isFinancialQuery = lowerText.contains("cuánto") || 
                                       lowerText.contains("cuanto") ||
                                       lowerText.contains("gastado") || 
                                       lowerText.contains("gasté") || 
                                       lowerText.contains("gasto") || 
                                       lowerText.contains("ingreso") ||
                                       lowerText.contains("ingresos") || 
                                       lowerText.contains("balance") || 
                                       lowerText.contains("total") || 
                                       lowerText.contains("dinero") || 
                                       lowerText.contains("mes") || 
                                       lowerText.contains("semana") || 
                                       lowerText.contains("año") || 
                                       lowerText.contains("anio") || 
                                       lowerText.contains("hoy") ||
                                       lowerText.contains("ahorrado") || 
                                       lowerText.contains("ahorro") || 
                                       lowerText.contains("debo") || 
                                       lowerText.contains("tengo") || 
                                       lowerText.contains("disponible") ||
                                       lowerText.contains("mi gasto") ||
                                       lowerText.contains("mis gastos") ||
                                       lowerText.contains("mis ingresos") ||
                                       lowerText.contains("mi balance")

                Log.d(TAG, "isFinancialQuery: $isFinancialQuery")

                if (isFinancialQuery) {
                    val periodo = when {
                        lowerText.contains("hoy") -> "hoy"
                        lowerText.contains("semana") -> "semana"
                        lowerText.contains("año") || lowerText.contains("anio") -> "año"
                        else -> "mes"
                    }
                    val queryType = when {
                        lowerText.contains("ingreso") -> "ingresos"
                        lowerText.contains("gasto") -> "gastos"
                        else -> "balance"
                    }
                    Log.d(TAG, "Executing query: type=$queryType, period=$periodo")
                    val response = executeQuery(queryType, periodo, null, null)
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI(response),
                            isProcessing = false
                        )
                    }
                } else {
                    val result = aiService.processCommand(text)
                    processAIResult(result, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage.AI("Error al procesar: ${e.message}"),
                        isProcessing = false
                    )
                }
            }
        }
    }

    private suspend fun processAIResult(result: AIResult, originalText: String) {
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
                        notas = invoice.notas
                    )
                    incomeRepository.insertIncome(income)
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI("✅ Ingreso registrado: ${income.concepto} - ${income.monto} ${income.moneda}"),
                            isProcessing = false
                        )
                    }
                } else {
                    val savedId = invoiceRepository.insertInvoice(invoice)
                    if (result.products.isNotEmpty()) {
                        productRepository.insertProducts(result.products.map { it.copy(invoiceId = savedId) })
                    }
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI("✅ Gasto registrado: ${invoice.proveedor} - ${invoice.total} ${invoice.moneda}"),
                            isProcessing = false
                        )
                    }
                }
            }
            result.queryResult != null && result.queryResult?.startsWith("INCOME:") == true -> {
                val parts = result.queryResult?.split(":") ?: emptyList()
                if (parts.size >= 4) {
                    val income = Income(
                        concepto = parts[1],
                        monto = parts[2].toDoubleOrNull() ?: 0.0,
                        moneda = parts.getOrNull(3) ?: "EUR",
                        fecha = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis(),
                        fuente = parts.getOrNull(5)
                    )
                    incomeRepository.insertIncome(income)
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI("✅ Ingreso registrado: ${income.concepto} - ${income.monto} ${income.moneda}"),
                            isProcessing = false
                        )
                    }
                }
            }
            result.queryResult != null -> {
                val queryResult = result.queryResult!!
                
                // Chat response from AI
                if (queryResult.startsWith("CHAT:")) {
                    val chatResponse = queryResult.substringAfter("CHAT:")
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI(chatResponse),
                            isProcessing = false
                        )
                    }
                } else if (queryResult.startsWith("QUERY:")) {
                    val parts = queryResult.split(":")
                    val queryType = parts.getOrNull(1) ?: "balance"
                    val periodo = parts.getOrNull(2) ?: "mes"
                    val categoria = parts.getOrNull(3)
                    val item = parts.getOrNull(4)
                    val response = executeQuery(queryType, periodo, categoria, item)
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage.AI(response),
                            isProcessing = false
                        )
                    }
                } else {
                    try {
                        val json = JSONObject(queryResult)
                        val action = json.optString("action", "")
                        
                        if (action == "chat") {
                            val chatResponse = json.optString("response", result.message)
                            _uiState.update {
                                it.copy(
                                    messages = it.messages + ChatMessage.AI(chatResponse),
                                    isProcessing = false
                                )
                            }
                        } else {
                            val queryType = json.optString("query_type", "balance")
                            val periodo = json.optString("periodo", "mes")
                            val categoria = json.optString("categoria", null).takeIf { it.isNotEmpty() }
                            val item = json.optString("item", null).takeIf { it.isNotEmpty() }
                            val response = executeQuery(queryType, periodo, categoria, item)
                            _uiState.update {
                                it.copy(
                                    messages = it.messages + ChatMessage.AI(response),
                                    isProcessing = false
                                )
                            }
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                messages = it.messages + ChatMessage.AI(result.message),
                                isProcessing = false
                            )
                        }
                    }
                }
            }
            result.success -> {
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage.AI(result.message),
                        isProcessing = false
                    )
                }
            }
            else -> {
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage.AI("❌ ${result.message}"),
                        isProcessing = false
                    )
                }
            }
        }
    }

    private suspend fun executeQuery(queryType: String, periodo: String, categoria: String?, item: String?): String {
        val now = System.currentTimeMillis()

        // Use fresh Calendar instances for each range
        val hoyCal = Calendar.getInstance()
        hoyCal.set(Calendar.HOUR_OF_DAY, 0)
        hoyCal.set(Calendar.MINUTE, 0)
        hoyCal.set(Calendar.SECOND, 0)
        hoyCal.set(Calendar.MILLISECOND, 0)
        val hoyStart = hoyCal.timeInMillis
        hoyCal.set(Calendar.HOUR_OF_DAY, 23)
        hoyCal.set(Calendar.MINUTE, 59)
        hoyCal.set(Calendar.SECOND, 59)
        val hoyEnd = hoyCal.timeInMillis

        val semCal = Calendar.getInstance()
        semCal.firstDayOfWeek = Calendar.MONDAY
        semCal.set(Calendar.DAY_OF_WEEK, semCal.firstDayOfWeek)
        semCal.set(Calendar.HOUR_OF_DAY, 0)
        semCal.set(Calendar.MINUTE, 0)
        semCal.set(Calendar.SECOND, 0)
        semCal.set(Calendar.MILLISECOND, 0)
        val semanaStart = semCal.timeInMillis

        val mesCal = Calendar.getInstance()
        mesCal.set(Calendar.DAY_OF_MONTH, 1)
        mesCal.set(Calendar.HOUR_OF_DAY, 0)
        mesCal.set(Calendar.MINUTE, 0)
        mesCal.set(Calendar.SECOND, 0)
        mesCal.set(Calendar.MILLISECOND, 0)
        val mesStart = mesCal.timeInMillis
        mesCal.set(Calendar.DAY_OF_MONTH, mesCal.getActualMaximum(Calendar.DAY_OF_MONTH))
        mesCal.set(Calendar.HOUR_OF_DAY, 23)
        mesCal.set(Calendar.MINUTE, 59)
        mesCal.set(Calendar.SECOND, 59)
        val mesEnd = mesCal.timeInMillis

        val anoCal = Calendar.getInstance()
        anoCal.set(Calendar.DAY_OF_YEAR, 1)
        anoCal.set(Calendar.HOUR_OF_DAY, 0)
        anoCal.set(Calendar.MINUTE, 0)
        anoCal.set(Calendar.SECOND, 0)
        anoCal.set(Calendar.MILLISECOND, 0)
        val anoStart = anoCal.timeInMillis
        anoCal.set(Calendar.DAY_OF_YEAR, anoCal.getActualMaximum(Calendar.DAY_OF_YEAR))
        anoCal.set(Calendar.HOUR_OF_DAY, 23)
        anoCal.set(Calendar.MINUTE, 59)
        anoCal.set(Calendar.SECOND, 59)
        val anoEnd = anoCal.timeInMillis

        val (start, end) = when (periodo.lowercase()) {
            "hoy" -> hoyStart to hoyEnd
            "semana" -> semanaStart to now
            "mes" -> mesStart to mesEnd
            "año" -> anoStart to anoEnd
            else -> mesStart to mesEnd
        }

        // Use first() to get the current value once, not collect() which blocks forever
        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()

        var totalGastos = 0.0
        var totalIngresos = 0.0
        var countGastos = 0
        var countIngresos = 0

        invoices.forEach { inv ->
            if (inv.fecha >= start && inv.fecha <= end) {
                if (inv.tipo == InvoiceType.GASTO) {
                    totalGastos += inv.total
                    countGastos++
                } else {
                    totalIngresos += inv.total
                    countIngresos++
                }
            }
        }

        incomes.forEach { inc ->
            if (inc.fecha >= start && inc.fecha <= end) {
                totalIngresos += inc.monto
                countIngresos++
            }
        }

        Log.d(TAG, "Query result: type=$queryType, period=$periodo, gastos=$totalGastos, ingresos=$totalIngresos")

        val fmt = java.text.NumberFormat.getCurrencyInstance(Locale("es", "ES"))

        return when (queryType.lowercase()) {
            "gastos" -> "💰 Gastos del $periodo:\n• Total: ${fmt.format(totalGastos)}\n• Cantidad: $countGastos transacciones"
            "ingresos" -> "💵 Ingresos del $periodo:\n• Total: ${fmt.format(totalIngresos)}\n• Cantidad: $countIngresos transacciones"
            "balance" -> "📊 Balance del $periodo:\n• Ingresos: ${fmt.format(totalIngresos)}\n• Gastos: ${fmt.format(totalGastos)}\n• Balance: ${fmt.format(totalIngresos - totalGastos)}"
            else -> "📊 Resumen del $periodo:\n• Ingresos: ${fmt.format(totalIngresos)}\n• Gastos: ${fmt.format(totalGastos)}\n• Balance: ${fmt.format(totalIngresos - totalGastos)}"
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
                                it.copy(messages = it.messages + ChatMessage.AI("⚠️ Reconocimiento de voz no disponible. Usa el campo de texto."))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        messages = it.messages + ChatMessage.AI("⚠️ Error de voz: ${e.message}")
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
                        messages = it.messages + ChatMessage.AI("❌ Error al escanear: ${e.message}"),
                        isProcessing = false
                    )
                }
            }
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
        addSystemMessage("Chat limpiado. ¿En qué puedo ayudarte?")
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
