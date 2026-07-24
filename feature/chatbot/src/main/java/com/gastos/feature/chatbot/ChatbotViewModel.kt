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
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.domain.usecase.SaveIncomeUseCase
import com.gastos.domain.usecase.SaveInvoiceUseCase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.feature.voice.VoiceRecognitionService
import com.gastos.feature.voice.VoiceResult
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import java.util.*
import javax.inject.Inject
import com.gastos.domain.model.SUPPORTED_CURRENCIES

private const val TAG = "ChatbotVM"

private data class ProductMatchResult(
    val matches: List<com.gastos.domain.model.Product>,
    val variants: List<String>,
    val requiresClarification: Boolean,
    val usedGroupMode: Boolean
)

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
    private val invoiceDriveService: InvoiceDriveService,
    private val invoiceImageStorage: InvoiceImageStorage,
    private val saveInvoiceUseCase: SaveInvoiceUseCase,
    private val saveIncomeUseCase: SaveIncomeUseCase,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatbotUiState())
    val uiState: StateFlow<ChatbotUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isProcessing) return

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

        validateAction(result)?.let { message ->
            replacePlaceholder("❌ $message")
            return
        }

        when {
            // Gasto (factura)
            result.invoice != null && result.invoice!!.tipo != InvoiceType.INGRESO -> {
                val invoice = result.invoice!!.copy(
                    driveUploadPending = result.invoice!!.imagenUri != null
                )
                val invoiceId = saveInvoiceUseCase(invoice, result.products)
                val savedInvoice = invoice.copy(id = invoiceId)
                val driveResult = if (savedInvoice.driveUploadPending) {
                    invoiceDriveService.upload(savedInvoice)
                } else {
                    null
                }
                val syncedInvoice = driveResult?.invoice ?: savedInvoice
                val savedProducts = productRepository.getProductsByInvoiceId(invoiceId).first()
                sheetsSyncManager.syncExpense(
                    syncedInvoice,
                    savedProducts
                )
                val driveMessage = driveResult?.let { "\n${if (it.uploaded) "☁️" else "⚠️"} ${it.message}" }.orEmpty()
                replacePlaceholder(
                    "✅ Gasto registrado: ${invoice.proveedor} - ${invoice.total} ${invoice.moneda}$driveMessage"
                )
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
                    imagenUri = invoice.imagenUri,
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
                        val queryType = json.optString("query_type")
                        val periodo = json.optString("periodo", "mes")
                        val categoria = json.optString("categoria", "").takeIf { it.isNotEmpty() && it != "null" }
                        val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                        val matchMode = json.optString("match_mode", "").takeIf { it.isNotEmpty() && it != "null" }
                        replacePlaceholder(executeQuery(queryType, periodo, categoria, item, matchMode))
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

    private fun validateAction(result: AIResult): String? {
        result.invoice?.let { invoice ->
            if (invoice.total <= 0.0) return "El importe detectado debe ser mayor que cero."
            if (invoice.proveedor.isBlank()) return "No se detectó un proveedor o concepto válido."
            if (invoice.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return "La moneda ${invoice.moneda} no está soportada."
            }
        }
        result.income?.let { income ->
            if (income.monto <= 0.0) return "El importe detectado debe ser mayor que cero."
            if (income.concepto.isBlank()) return "No se detectó un concepto válido."
            if (income.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return "La moneda ${income.moneda} no está soportada."
            }
        }
        if (result.products.any { it.descripcion.isBlank() || it.cantidad <= 0.0 || it.subtotal < 0.0 }) {
            return "Las líneas de producto detectadas contienen valores inválidos."
        }
        return null
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
        hoyCal.set(Calendar.MILLISECOND, 999)
        val hoyEnd = hoyCal.timeInMillis

        val semCal = Calendar.getInstance()
        semCal.firstDayOfWeek = Calendar.MONDAY
        semCal.set(Calendar.DAY_OF_WEEK, semCal.firstDayOfWeek)
        semCal.set(Calendar.HOUR_OF_DAY, 0)
        semCal.set(Calendar.MINUTE, 0)
        semCal.set(Calendar.SECOND, 0)
        semCal.set(Calendar.MILLISECOND, 0)
        val semanaStart = semCal.timeInMillis
        val semanaFinCal = semCal.clone() as Calendar
        semanaFinCal.add(Calendar.DAY_OF_YEAR, 7)
        val semanaEnd = semanaFinCal.timeInMillis - 1

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
        mesCal.set(Calendar.MILLISECOND, 999)
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
        anoCal.set(Calendar.MILLISECOND, 999)
        val anoEnd = anoCal.timeInMillis

        return when (periodo.lowercase()) {
            "hoy" -> hoyStart to hoyEnd
            "semana" -> semanaStart to semanaEnd
            "mes" -> mesStart to mesEnd
            "año" -> anoStart to anoEnd
            else -> mesStart to mesEnd
        }
    }

    private suspend fun executeQuery(
        queryType: String?,
        periodo: String,
        categoria: String?,
        item: String?,
        matchMode: String?
    ): String {
        val (start, end) = getDateRange(periodo)
        val target = currencyPreference.defaultCurrency.value
        val fmt = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES")).apply {
            try { currency = java.util.Currency.getInstance(target) } catch (_: Exception) { /* fallback al locale */ }
        }

        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val allProducts = productRepository.getAllProducts().first()

        // Filter by date range
        val periodInvoices = invoices.filter { it.fecha in start..end }
        val periodIncomes = incomes.filter { it.fecha in start..end }
        val periodInvoiceIds = periodInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in periodInvoiceIds }
        val invoiceById = periodInvoices.associateBy { it.id }

        fun convertedInvoiceAmount(invoice: com.gastos.domain.model.Invoice): Double =
            exchangeRateProvider.convert(invoice.total, invoice.moneda, target) ?: 0.0

        fun convertedIncomeAmount(income: Income): Double =
            exchangeRateProvider.convert(income.monto, income.moneda, target) ?: 0.0

        fun convertedProductAmount(product: com.gastos.domain.model.Product): Double {
            val currency = invoiceById[product.invoiceId]?.moneda ?: return 0.0
            return exchangeRateProvider.convert(product.subtotal, currency, target) ?: 0.0
        }

        // Totales convertidos a la moneda por defecto del usuario (mismo
        // mecanismo que el Dashboard): si falta la tasa de una moneda, su
        // importe se excluye y no se suma como si fuera la moneda destino.
        val totalGastos = periodInvoices
            .filter { it.tipo == InvoiceType.GASTO }
            .sumOf(::convertedInvoiceAmount)
        val totalIngresos = periodInvoices
                .filter { it.tipo == InvoiceType.INGRESO }
                .sumOf(::convertedInvoiceAmount) +
            periodIncomes.sumOf(::convertedIncomeAmount)
        val countGastos = periodInvoices.count { it.tipo == InvoiceType.GASTO }
        val countIngresos = periodInvoices.count { it.tipo == InvoiceType.INGRESO } + periodIncomes.size

        val resolvedQueryType = resolveQueryType(queryType, item)
        SafeLog.d(TAG, "Query: type=$resolvedQueryType, period=$periodo, gastos=$totalGastos, ingresos=$totalIngresos, products=${periodProducts.size}")

        return when (resolvedQueryType) {
            "gastos" -> {
                val sb = StringBuilder("💰 Gastos del $periodo:\n")
                sb.append("• Total: ${fmt.format(totalGastos)}\n")
                sb.append("• Cantidad: $countGastos transacciones\n")
                if (periodInvoices.isNotEmpty()) {
                    val byProvider = periodInvoices.filter { it.tipo == InvoiceType.GASTO }
                        .groupBy { it.proveedor }
                        .mapValues { (_, values) -> values.sumOf(::convertedInvoiceAmount) }
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
                        .mapValues { (_, values) -> values.sumOf(::convertedIncomeAmount) }
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
                if (!item.isNullOrBlank()) {
                    val matchResult = matchProducts(periodProducts, item, matchMode)
                    if (matchResult.requiresClarification) {
                        return buildProductClarification(periodo, item, matchResult.variants)
                    }
                    if (matchResult.matches.isEmpty()) {
                        return "📦 No encontré un producto exacto para '$item' en el periodo: $periodo"
                    }
                    val total = matchResult.matches.sumOf(::convertedProductAmount)
                    val totalUnits = matchResult.matches.sumOf { it.cantidad }
                    val variantsText = matchResult.variants.joinToString(", ") {
                        it.replaceFirstChar { ch -> ch.uppercase() }
                    }
                    val intro = if (matchResult.usedGroupMode) {
                        "📦 Gasto en variantes de '$item' durante $periodo:"
                    } else {
                        "📦 Gasto en '$item' durante $periodo:"
                    }
                    return buildString {
                        appendLine(intro)
                        appendLine("• Total: ${fmt.format(total)}")
                        appendLine("• Cantidad: ${if (totalUnits % 1.0 == 0.0) totalUnits.toInt() else totalUnits} uds")
                        append("• Coincidencias: $variantsText")
                    }
                }
                if (periodProducts.isEmpty()) {
                    return "📦 No hay productos registrados en el periodo: $periodo"
                }
                val sb = StringBuilder("📦 Productos del $periodo:\n")
                // Most bought by frequency
                val byFrequency = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) ->
                        values.sumOf { p -> p.cantidad }.toInt() to
                            values.sumOf(::convertedProductAmount)
                    }
                    .toList().sortedByDescending { it.second.first }.take(5)

                sb.append("\n🏆 Más comprados (por frecuencia):\n")
                byFrequency.forEachIndexed { i, (name, pair) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }}: ${pair.first} uds - ${fmt.format(pair.second)}\n")
                }

                // Most expensive
                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) -> values.sumOf(::convertedProductAmount) }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\n💸 Mayor gasto por producto:\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }}: ${fmt.format(total)}\n")
                }

                sb.append("\n📊 Total productos: ${periodProducts.size} items - ${fmt.format(periodProducts.sumOf(::convertedProductAmount))}")
                sb.toString().trimEnd()
            }
            else -> {
                "No entendí del todo la consulta. Pídeme gastos, ingresos, balance o un producto concreto indicando el periodo."
            }
        }
    }

    private fun resolveQueryType(queryType: String?, item: String?): String? {
        val normalized = queryType?.lowercase(Locale.ROOT)
        return when {
            normalized in setOf("gastos", "ingresos", "balance", "productos", "producto") -> normalized
            !item.isNullOrBlank() -> "productos"
            else -> null
        }
    }

    private fun matchProducts(
        products: List<com.gastos.domain.model.Product>,
        item: String,
        matchMode: String?
    ): ProductMatchResult {
        val normalizedItem = normalizeProductName(item)
        if (normalizedItem.isBlank()) {
            return ProductMatchResult(emptyList(), emptyList(), requiresClarification = false, usedGroupMode = false)
        }

        val exactMatches = products.filter { normalizeProductName(it.descripcion) == normalizedItem }
        val relatedMatches = products.filter { normalizeProductName(it.descripcion).contains(normalizedItem) }
        val relatedVariants = relatedMatches
            .map { it.descripcion.trim() }
            .distinct()
            .sortedBy { normalizeProductName(it) }
        val normalizedMode = matchMode?.lowercase(Locale.ROOT)

        return when (normalizedMode) {
            "group" -> ProductMatchResult(
                matches = relatedMatches,
                variants = relatedVariants,
                requiresClarification = false,
                usedGroupMode = true
            )
            "exact" -> when {
                exactMatches.isNotEmpty() -> ProductMatchResult(
                    matches = exactMatches,
                    variants = exactMatches.map { it.descripcion.trim() }.distinct(),
                    requiresClarification = false,
                    usedGroupMode = false
                )
                relatedVariants.isNotEmpty() -> ProductMatchResult(
                    matches = emptyList(),
                    variants = relatedVariants,
                    requiresClarification = true,
                    usedGroupMode = false
                )
                else -> ProductMatchResult(emptyList(), emptyList(), false, false)
            }
            else -> when {
                exactMatches.isNotEmpty() -> ProductMatchResult(
                    matches = exactMatches,
                    variants = exactMatches.map { it.descripcion.trim() }.distinct(),
                    requiresClarification = false,
                    usedGroupMode = false
                )
                relatedVariants.size == 1 -> ProductMatchResult(
                    matches = relatedMatches,
                    variants = relatedVariants,
                    requiresClarification = false,
                    usedGroupMode = true
                )
                relatedVariants.size > 1 -> ProductMatchResult(
                    matches = emptyList(),
                    variants = relatedVariants,
                    requiresClarification = true,
                    usedGroupMode = false
                )
                else -> ProductMatchResult(emptyList(), emptyList(), false, false)
            }
        }
    }

    private fun buildProductClarification(periodo: String, item: String, variants: List<String>): String {
        val options = variants.take(5).joinToString(", ") { "'${it.trim()}'" }
        return "No encontré '$item' como producto exacto en $periodo. Sí veo variantes como $options. Dime si quieres solo uno exacto o incluir todas esas variantes."
    }

    private fun normalizeProductName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
        .replace("\\s+".toRegex(), " ")

    fun startVoiceInput() {
        if (_uiState.value.isProcessing) return
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
        if (_uiState.value.isProcessing) return
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage.User("📷 Escaneando imagen..."), isProcessing = true)
        }

        viewModelScope.launch {
            var persistedUri: Uri? = null
            try {
                val stableUri = invoiceImageStorage.persist(uri)
                persistedUri = stableUri
                invoiceImageStorage.deleteTemporaryCameraCopy(uri)
                val result = aiService.processInvoiceFromImage(stableUri)
                // Reutilizamos la lógica unificada añadiendo un placeholder AI.
                var placeholderIndex = -1
                _uiState.update {
                    placeholderIndex = it.messages.size
                    it.copy(messages = it.messages + ChatMessage.AI(""))
                }
                applyAIResult(result, placeholderIndex)
                if (!result.success || (result.invoice == null && result.income == null)) {
                    invoiceImageStorage.delete(persistedUri.toString())
                }
            } catch (e: Exception) {
                invoiceImageStorage.delete(persistedUri?.toString())
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
        viewModelScope.launch {
            aiService.resetChat()
            _uiState.update { it.copy(messages = emptyList(), isProcessing = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}
