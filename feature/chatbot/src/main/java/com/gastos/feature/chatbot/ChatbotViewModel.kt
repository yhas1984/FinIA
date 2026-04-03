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
        addSystemMessage("¡Hola! Soy FinAI, tu asistente financiero personal.")
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

                        when (action) {
                            "chat" -> {
                                val chatResponse = json.optString("response", result.message)
                                _uiState.update {
                                    it.copy(
                                        messages = it.messages + ChatMessage.AI(chatResponse),
                                        isProcessing = false
                                    )
                                }
                            }
                            "query" -> {
                                val queryType = json.optString("query_type", "balance")
                                val periodo = json.optString("periodo", "mes")
                                val categoria = json.optString("categoria", "").takeIf { it.isNotEmpty() && it != "null" }
                                val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                                val response = executeQuery(queryType, periodo, categoria, item)
                                _uiState.update {
                                    it.copy(
                                        messages = it.messages + ChatMessage.AI(response),
                                        isProcessing = false
                                    )
                                }
                            }
                            else -> {
                                _uiState.update {
                                    it.copy(
                                        messages = it.messages + ChatMessage.AI(result.message),
                                        isProcessing = false
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // If we can't parse JSON, show the raw AI response
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

    private fun getDateRange(periodo: String): Pair<Long, Long> {
        val now = System.currentTimeMillis()

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

        return when (periodo.lowercase()) {
            "hoy" -> hoyStart to hoyEnd
            "semana" -> semanaStart to now
            "mes" -> mesStart to mesEnd
            "año" -> anoStart to anoEnd
            else -> mesStart to mesEnd
        }
    }

    private suspend fun executeQuery(queryType: String, periodo: String, categoria: String?, item: String?): String {
        val (start, end) = getDateRange(periodo)
        val fmt = java.text.NumberFormat.getCurrencyInstance(Locale("es", "ES"))

        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val allProducts = productRepository.getAllProducts().first()

        // Filter by date range
        val periodInvoices = invoices.filter { it.fecha in start..end }
        val periodIncomes = incomes.filter { it.fecha in start..end }
        val periodInvoiceIds = periodInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in periodInvoiceIds }

        val totalGastos = periodInvoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { it.total }
        val totalIngresos = periodInvoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { it.total } +
                periodIncomes.sumOf { it.monto }
        val countGastos = periodInvoices.count { it.tipo == InvoiceType.GASTO }
        val countIngresos = periodInvoices.count { it.tipo == InvoiceType.INGRESO } + periodIncomes.size

        Log.d(TAG, "Query: type=$queryType, period=$periodo, gastos=$totalGastos, ingresos=$totalIngresos, products=${periodProducts.size}")

        return when (queryType.lowercase()) {
            "gastos" -> {
                val sb = StringBuilder("💰 Gastos del $periodo:\n")
                sb.append("• Total: ${fmt.format(totalGastos)}\n")
                sb.append("• Cantidad: $countGastos transacciones\n")
                if (periodInvoices.isNotEmpty()) {
                    val byProvider = periodInvoices.filter { it.tipo == InvoiceType.GASTO }
                        .groupBy { it.proveedor }
                        .mapValues { it.value.sumOf { inv -> inv.total } }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (byProvider.isNotEmpty()) {
                        sb.append("\n📋 Top proveedores:\n")
                        byProvider.forEach { (name, total) ->
                            sb.append("  • $name: ${fmt.format(total)}\n")
                        }
                    }
                }
                sb.toString().trimEnd()
            }
            "ingresos" -> {
                val sb = StringBuilder("💵 Ingresos del $periodo:\n")
                sb.append("• Total: ${fmt.format(totalIngresos)}\n")
                sb.append("• Cantidad: $countIngresos transacciones\n")
                if (periodIncomes.isNotEmpty()) {
                    val bySource = periodIncomes.groupBy { it.fuente ?: it.concepto }
                        .mapValues { it.value.sumOf { inc -> inc.monto } }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (bySource.isNotEmpty()) {
                        sb.append("\n📋 Fuentes principales:\n")
                        bySource.forEach { (name, total) ->
                            sb.append("  • $name: ${fmt.format(total)}\n")
                        }
                    }
                }
                sb.toString().trimEnd()
            }
            "balance" -> {
                val balance = totalIngresos - totalGastos
                val emoji = if (balance >= 0) "✅" else "⚠️"
                "📊 Balance del $periodo:\n• Ingresos: ${fmt.format(totalIngresos)} ($countIngresos)\n• Gastos: ${fmt.format(totalGastos)} ($countGastos)\n• Balance: $emoji ${fmt.format(balance)}"
            }
            "productos", "producto" -> {
                if (periodProducts.isEmpty()) {
                    return "📦 No hay productos registrados en el periodo: $periodo"
                }
                val sb = StringBuilder("📦 Productos del $periodo:\n")
                // Most bought by frequency
                val byFrequency = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { it.value.sumOf { p -> p.cantidad }.toInt() to it.value.sumOf { p -> p.subtotal } }
                    .toList().sortedByDescending { it.second.first }.take(5)

                sb.append("\n🏆 Más comprados (por frecuencia):\n")
                byFrequency.forEachIndexed { i, (name, pair) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }}: ${pair.first} uds - ${fmt.format(pair.second)}\n")
                }

                // Most expensive
                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { it.value.sumOf { p -> p.subtotal } }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\n💸 Mayor gasto por producto:\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }}: ${fmt.format(total)}\n")
                }

                sb.append("\n📊 Total productos: ${periodProducts.size} items - ${fmt.format(periodProducts.sumOf { it.subtotal })}")
                sb.toString().trimEnd()
            }
            else -> {
                // Full summary for unknown query types
                val balance = totalIngresos - totalGastos
                val sb = StringBuilder("📊 Resumen completo del $periodo:\n")
                sb.append("• Ingresos: ${fmt.format(totalIngresos)} ($countIngresos)\n")
                sb.append("• Gastos: ${fmt.format(totalGastos)} ($countGastos)\n")
                sb.append("• Balance: ${fmt.format(balance)}\n")
                if (periodProducts.isNotEmpty()) {
                    sb.append("• Productos registrados: ${periodProducts.size}\n")
                    val topProduct = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                        .mapValues { it.value.sumOf { p -> p.cantidad }.toInt() }
                        .toList().sortedByDescending { it.second }.firstOrNull()
                    if (topProduct != null) {
                        sb.append("• Producto más comprado: ${topProduct.first.replaceFirstChar { it.uppercase() }} (${topProduct.second} uds)")
                    }
                }
                sb.toString().trimEnd()
            }
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
