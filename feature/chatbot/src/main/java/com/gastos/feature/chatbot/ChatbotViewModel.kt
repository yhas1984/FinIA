package com.gastos.feature.chatbot

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.extension.SafeLog
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.domain.usecase.SaveIncomeUseCase
import com.gastos.domain.usecase.SaveInvoiceUseCase
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
    private val productRepository: ProductRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val saveInvoiceUseCase: SaveInvoiceUseCase,
    private val saveIncomeUseCase: SaveIncomeUseCase
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

        // Sin API key: mensaje guía en lugar de llamar al servicio.
        if (!aiService.isConfigured()) {
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage.AI(com.gastos.feature.ai.AIService.NO_API_KEY_MESSAGE),
                    isProcessing = false
                )
            }
            return
        }

        // Añadimos un mensaje AI vacío (placeholder) que iremos rellenando en
        // streaming. El índice se captura DENTRO del update para evitar races.
        var placeholderIndex = -1
        _uiState.update {
            placeholderIndex = it.messages.size
            it.copy(messages = it.messages + ChatMessage.AI(""))
        }

        viewModelScope.launch {
            try {
                val collected = StringBuilder()
                aiService.processCommandStreaming(text).collect { chunk ->
                    collected.append(chunk)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.toMutableList().apply {
                                if (placeholderIndex in indices) {
                                    this[placeholderIndex] = ChatMessage.AI(collected.toString())
                                }
                            }
                        )
                    }
                }

                val raw = collected.toString()
                // Si hay texto, parseamos el resultado final para ejecutar acciones
                // (registrar gasto/ingreso/consulta) o, si era chat, ya se mostró en vivo.
                if (raw.isNotBlank()) {
                    handleStreamingResult(raw, placeholderIndex)
                } else {
                    _uiState.update {
                        it.copy(
                            messages = it.messages.toMutableList().apply {
                                if (placeholderIndex in indices) {
                                    this[placeholderIndex] = ChatMessage.AI("No recibí respuesta. Inténtalo de nuevo.")
                                }
                            },
                            isProcessing = false
                        )
                    }
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error processing message", e)
                _uiState.update {
                    it.copy(
                        messages = it.messages.toMutableList().apply {
                            if (placeholderIndex in indices) {
                                this[placeholderIndex] = ChatMessage.AI("Error al procesar: ${e.message}")
                            }
                        },
                        isProcessing = false
                    )
                }
            }
        }
    }

    /**
     * Reemplaza el mensaje placeholder con el resultado final procesado.
     * - Si el modelo respondió en texto plano (chat), lo deja tal cual (ya mostrado).
     * - Si respondió con JSON de acción, ejecuta la acción y reemplaza el mensaje.
     */
    private suspend fun handleStreamingResult(raw: String, placeholderIndex: Int) {
        val result = aiService.parseStreamingResult(raw)
        applyAIResult(result, placeholderIndex)
    }

    /**
     * Aplica un AIResult reemplazando el mensaje placeholder. Lógica unificada
     * para streaming (chat) y para resultados síncronos (imagen escaneada).
     */
    private suspend fun applyAIResult(result: AIResult, placeholderIndex: Int) {
        val replacePlaceholder: (String) -> Unit = { text ->
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.toMutableList().apply {
                        if (placeholderIndex in indices) {
                            this[placeholderIndex] = ChatMessage.AI(text)
                        }
                    },
                    isProcessing = false
                )
            }
        }

        when {
            // Gasto (factura)
            result.invoice != null && result.invoice!!.tipo != InvoiceType.INGRESO -> {
                val invoice = result.invoice!!
                val invoiceId = saveInvoiceUseCase(invoice, result.products)
                sheetsSyncManager.upsertExpense(invoice.copy(id = invoiceId))
                sheetsSyncManager.syncProducts(
                    result.products.map { it.copy(invoiceId = invoiceId) },
                    invoice.proveedor
                )
                replacePlaceholder("✅ Gasto registrado: ${invoice.proveedor} - ${invoice.total} ${invoice.moneda}")
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
                val incomeId = saveIncomeUseCase(income)
                sheetsSyncManager.upsertIncome(income.copy(id = incomeId))
                replacePlaceholder("✅ Ingreso registrado: ${income.concepto} - ${income.monto} ${income.moneda}")
            }
            // Ingreso detectado por texto
            result.income != null -> {
                val income = result.income!!
                val incomeId = saveIncomeUseCase(income)
                sheetsSyncManager.upsertIncome(income.copy(id = incomeId))
                val display = if (income.totalDevengado > 0 && income.totalNeto > 0) {
                    "Devengado: ${income.totalDevengado} ${income.moneda} / Neto: ${income.totalNeto} ${income.moneda}"
                } else {
                    "${income.monto} ${income.moneda}"
                }
                replacePlaceholder("✅ Ingreso registrado: ${income.concepto} - $display")
            }
            // Consulta de datos (JSON con action=query)
            result.queryResult != null -> {
                try {
                    val json = JSONObject(result.queryResult!!)
                    if (json.optString("action") == "query") {
                        val queryType = json.optString("query_type", "balance")
                        val periodo = json.optString("periodo", "mes")
                        val categoria = json.optString("categoria", "").takeIf { it.isNotEmpty() && it != "null" }
                        val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                        replacePlaceholder(executeQuery(queryType, periodo, categoria, item))
                    } else {
                        replacePlaceholder(result.message)
                    }
                } catch (e: Exception) {
                    replacePlaceholder(result.message)
                }
            }
            result.success -> replacePlaceholder(result.message)
            else -> replacePlaceholder("❌ ${result.message}")
        }
    }

    private fun getDateRange(periodo: String): Pair<Long, Long> {
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
        // Fin de la semana = inicio + 7 días - 1 ms (fin del domingo)
        val semanaEnd = semanaStart + (7L * 24 * 60 * 60 * 1000) - 1

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
            "semana" -> semanaStart to semanaEnd
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

        SafeLog.d(TAG, "Query: type=$queryType, period=$periodo, gastos=$totalGastos, ingresos=$totalIngresos, products=${periodProducts.size}")

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
                        when {
                            // Errores del reconocedor (sin permisos, timeout,
                            // "no match"...): se muestran como aviso y NUNCA
                            // se envían al asistente como si fueran un comando.
                            voiceResult.isError -> _uiState.update {
                                it.copy(messages = it.messages + ChatMessage.AI("⚠️ ${voiceResult.text}. Usa el campo de texto."))
                            }
                            voiceResult.text.isNotBlank() -> sendMessage(voiceResult.text)
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
                // Reutilizamos la lógica unificada añadiendo un placeholder AI.
                var placeholderIndex = -1
                _uiState.update {
                    placeholderIndex = it.messages.size
                    it.copy(messages = it.messages + ChatMessage.AI(""))
                }
                applyAIResult(result, placeholderIndex)
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
        aiService.resetChat()
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
