package com.gastos.feature.chatbot

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import com.gastos.extension.SafeLog
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.domain.usecase.SaveIncomeUseCase
import com.gastos.domain.usecase.SaveInvoiceUseCase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.ChatMessageRepository
import com.gastos.repository.PremiumStatusProvider
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import java.util.*
import javax.inject.Inject
import com.gastos.domain.model.SUPPORTED_CURRENCIES

private const val TAG = "ChatbotVM"

internal data class ProductMatchResult(
    val matches: List<com.gastos.domain.model.Product>,
    val variants: List<String>,
    val requiresClarification: Boolean,
    val usedGroupMode: Boolean
)

private data class PendingProductClarification(
    val periodo: String,
    val requestedItem: String,
    val provider: String?,
    val category: String?,
    val variants: List<String>
)

internal data class ResolvedFinancialQuery(
    val queryType: String?,
    val item: String?,
    val matchMode: String?,
    val category: String?,
    val provider: String?
)

private data class ExecutedFinancialQuery(
    val text: String,
    val contextText: String
)

internal data class ResolvedProductClarification(
    val item: String,
    val matchMode: String
)

internal object FinancialQueryResolver {
    fun normalizePeriod(value: String?): String = when (normalizeProductName(value.orEmpty())) {
        "hoy" -> "hoy"
        "semana", "esta semana", "semana actual", "lo que va de semana" -> "semana"
        "ano", "este ano", "ano actual", "lo que va de ano" -> "año"
        "mes", "este mes", "mes actual", "lo que va de mes" -> "mes"
        else -> "mes"
    }

    fun resolve(
        queryType: String?,
        item: String?,
        matchMode: String?,
        originalQuestion: String?,
        productNames: List<String>,
        category: String? = null,
        provider: String? = null,
        providerNames: List<String> = emptyList(),
        categoryNames: List<String> = emptyList()
    ): ResolvedFinancialQuery {
        val normalizedType = queryType?.lowercase(Locale.ROOT)
        val explicitItem = item?.trim()?.takeIf { it.isNotEmpty() }
        val explicitCategory = category?.trim()?.takeIf { it.isNotEmpty() }
        val explicitProvider = provider?.trim()?.takeIf { it.isNotEmpty() }
        val knownProduct = explicitItem?.let { findKnownName(it, productNames) }
        val knownCategory = explicitCategory?.let { findKnownExactName(it, categoryNames) }
        val providerFromCategory = explicitCategory
            ?.takeIf { !questionRequestsCategory(originalQuestion) }
            ?.takeIf { findKnownName(it, providerNames) != null }
        val providerFromItem = explicitItem
            ?.takeIf { knownProduct == null }
            ?.takeIf { findKnownName(it, providerNames) != null }
        val inferredProvider = inferKnownName(originalQuestion, providerNames)
        val resolvedProvider = explicitProvider
            ?: providerFromCategory
            ?: providerFromItem
            ?: inferredProvider
        val inferredProduct = explicitItem ?: inferKnownProduct(originalQuestion, productNames)
        val resolvedItem = when {
            knownProduct != null -> explicitItem
            explicitItem != null && normalizedType in PRODUCT_QUERY_TYPES && resolvedProvider == null -> explicitItem
            explicitItem == null && inferredProduct != null && resolvedProvider == null -> inferredProduct
            else -> null
        }
        val resolvedCategory = if (providerFromCategory != null) {
            null
        } else {
            knownCategory ?: explicitCategory?.takeIf {
                resolvedProvider == null && questionRequestsCategory(originalQuestion)
            }
        }
        val inferredMetric = inferMetric(originalQuestion)
        val resolvedType = when {
            resolvedItem != null -> "productos"
            inferredMetric != null -> inferredMetric
            resolvedProvider != null && normalizedType in PRODUCT_QUERY_TYPES -> "gastos"
            normalizedType in SUPPORTED_QUERY_TYPES -> normalizedType
            resolvedProvider != null -> "gastos"
            else -> null
        }
        val resolvedMode = resolveProductMatchMode(
            item = resolvedItem,
            requestedMode = matchMode,
            originalQuestion = originalQuestion,
            productNames = productNames
        )
        return ResolvedFinancialQuery(
            queryType = resolvedType,
            item = resolvedItem,
            matchMode = resolvedMode,
            category = resolvedCategory,
            provider = resolvedProvider
        )
    }

    fun normalizeProductName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
        .replace("\\s+".toRegex(), " ")

    fun isRelatedProductName(candidate: String, requestedItem: String): Boolean {
        return containsTokenSequence(candidate, requestedItem)
    }

    fun matchProducts(
        products: List<com.gastos.domain.model.Product>,
        item: String,
        matchMode: String?
    ): ProductMatchResult {
        val normalizedItem = normalizeProductName(item)
        if (normalizedItem.isBlank()) {
            return ProductMatchResult(emptyList(), emptyList(), requiresClarification = false, usedGroupMode = false)
        }
        val exactMatches = products.filter { normalizeProductName(it.descripcion) == normalizedItem }
        val relatedMatches = products.filter { isRelatedProductName(it.descripcion, normalizedItem) }
        val relatedVariants = relatedMatches
            .map { it.descripcion.trim() }
            .distinct()
            .sortedBy(::normalizeProductName)
        return when (matchMode?.lowercase(Locale.ROOT)) {
            "group" -> ProductMatchResult(relatedMatches, relatedVariants, false, true)
            "exact" -> when {
                exactMatches.isNotEmpty() -> ProductMatchResult(
                    exactMatches,
                    exactMatches.map { it.descripcion.trim() }.distinct(),
                    false,
                    false
                )
                relatedVariants.isNotEmpty() -> ProductMatchResult(emptyList(), relatedVariants, true, false)
                else -> ProductMatchResult(emptyList(), emptyList(), false, false)
            }
            else -> when {
                exactMatches.isNotEmpty() -> ProductMatchResult(
                    exactMatches,
                    exactMatches.map { it.descripcion.trim() }.distinct(),
                    false,
                    false
                )
                relatedVariants.size == 1 -> ProductMatchResult(relatedMatches, relatedVariants, false, true)
                relatedVariants.size > 1 -> ProductMatchResult(emptyList(), relatedVariants, true, false)
                else -> ProductMatchResult(emptyList(), emptyList(), false, false)
            }
        }
    }

    fun matchesProvider(candidate: String, requestedProvider: String): Boolean {
        return containsTokenSequence(candidate, requestedProvider) ||
            containsTokenSequence(requestedProvider, candidate)
    }

    private fun resolveProductMatchMode(
        item: String?,
        requestedMode: String?,
        originalQuestion: String?,
        productNames: List<String>
    ): String? {
        if (item.isNullOrBlank()) return null
        if (questionRequestsExactProduct(originalQuestion)) return "exact"
        if (requestedMode.equals("group", ignoreCase = true)) return "group"
        val normalizedItem = normalizeProductName(item)
        val relatedNames = productNames
            .map(::normalizeProductName)
            .filter { it.isNotBlank() && containsTokenSequence(it, normalizedItem) }
            .distinct()
        val hasExactName = normalizedItem in relatedNames
        return if (hasExactName) "exact" else "group"
    }

    fun requestsNetBalance(question: String?): Boolean {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        return NET_TERMS.any(normalizedQuestion::contains)
    }

    fun resolveClarification(
        answer: String,
        requestedItem: String,
        variants: List<String>
    ): ResolvedProductClarification? {
        val normalizedAnswer = normalizeProductName(answer)
        if (normalizedAnswer in setOf("todas", "todos", "todas las variantes", "todos los productos")) {
            return ResolvedProductClarification(requestedItem, "group")
        }
        if (normalizedAnswer in setOf("si", "correcto", "esa", "ese") && variants.size == 1) {
            return ResolvedProductClarification(variants.single(), "exact")
        }
        val number = normalizedAnswer.toIntOrNull()
        if (number != null && number in 1..variants.size) {
            return ResolvedProductClarification(variants[number - 1], "exact")
        }
        val selected = variants.firstOrNull { variant ->
            normalizeProductName(variant) == normalizedAnswer
        } ?: return null
        return ResolvedProductClarification(selected, "exact")
    }

    private fun inferKnownProduct(question: String?, productNames: List<String>): String? {
        return inferKnownName(question, productNames)
    }

    private fun inferKnownName(question: String?, knownNames: List<String>): String? {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        if (normalizedQuestion.isBlank()) return null
        val paddedQuestion = " $normalizedQuestion "
        return knownNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { normalizeProductName(it).length }
            .firstOrNull { name ->
                val normalizedName = normalizeProductName(name)
                normalizedName.isNotBlank() && paddedQuestion.contains(" $normalizedName ")
            }
    }

    private fun findKnownName(value: String, knownNames: List<String>): String? {
        return knownNames.firstOrNull { matchesProvider(it, value) }
    }

    private fun findKnownExactName(value: String, knownNames: List<String>): String? {
        val normalizedValue = normalizeProductName(value)
        return knownNames.firstOrNull { normalizeProductName(it) == normalizedValue }
    }

    private fun containsTokenSequence(candidate: String, requested: String): Boolean {
        val candidateTokens = normalizeProductName(candidate).split(' ').filter(String::isNotBlank)
        val requestedTokens = normalizeProductName(requested).split(' ').filter(String::isNotBlank)
        if (requestedTokens.isEmpty() || requestedTokens.size > candidateTokens.size) return false
        return candidateTokens.windowed(requestedTokens.size).any { it == requestedTokens }
    }

    private fun questionRequestsCategory(question: String?): Boolean {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        return normalizedQuestion.contains("categoria")
    }

    private fun questionRequestsExactProduct(question: String?): Boolean {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        return EXACT_PRODUCT_TERMS.any { term ->
            normalizedQuestion == term || normalizedQuestion.contains("$term ")
        }
    }

    private fun inferMetric(question: String?): String? {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        if (normalizedQuestion.isBlank()) return null
        return when {
            NET_TERMS.any(normalizedQuestion::contains) -> "balance"
            INCOME_TERMS.any(normalizedQuestion::contains) -> "ingresos"
            EXPENSE_TERMS.any(normalizedQuestion::contains) -> "gastos"
            else -> null
        }
    }

    private val SUPPORTED_QUERY_TYPES = setOf(
        "gastos", "ingresos", "balance", "productos", "producto"
    )
    private val PRODUCT_QUERY_TYPES = setOf("productos", "producto")
    private val EXACT_PRODUCT_TERMS = setOf("solo", "solamente", "unicamente", "exactamente", "producto exacto")
    private val NET_TERMS = setOf(
        "balance", "ganado", "ganancia", "beneficio", "neto", "lo que me queda",
        "ingresos menos gastos"
    )
    private val INCOME_TERMS = setOf(
        "ingreso", "ingresado", "cobrado", "recibido", "salario", "sueldo", "nomina"
    )
    private val EXPENSE_TERMS = setOf("gasto", "gastado", "comprado", "pagado")
}

data class ChatbotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val isListening: Boolean = false
)

@HiltViewModel
class ChatbotViewModel @Inject constructor(
    private val aiService: AIService,
    private val chatMessageRepository: ChatMessageRepository,
    private val premiumStatusProvider: PremiumStatusProvider,
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

    private val _uiState = MutableStateFlow(ChatbotUiState(isProcessing = true))
    val uiState: StateFlow<ChatbotUiState> = _uiState.asStateFlow()
    private var pendingProductClarification: PendingProductClarification? = null
    private var hasRestoredMessages = false

    init {
        viewModelScope.launch {
            premiumStatusProvider.isPremium.collectLatest { premium ->
                try {
                    aiService.setPremiumLimits(premium)
                    restoreChat()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SafeLog.e(TAG, "Error restoring chat", e)
                    _uiState.update { it.copy(isProcessing = false) }
                }
            }
        }
    }

    private suspend fun restoreChat() {
        val messages = chatMessageRepository.getMessages()
        if (!hasRestoredMessages) {
            _uiState.update {
                it.copy(messages = messages.toUiMessages().takeLast(200), isProcessing = false)
            }
            hasRestoredMessages = true
        }
        aiService.replaceChatHistory(messages)
    }

    private fun List<com.gastos.domain.model.ChatMessageRecord>.toUiMessages(): List<ChatMessage> = mapNotNull { it.toUiMessage() }
    private fun com.gastos.domain.model.ChatMessageRecord.toUiMessage(): ChatMessage? = when (role) {
        "user" -> ChatMessage.User(visibleText, createdAt)
        "model" -> ChatMessage.AI(visibleText, createdAt)
        else -> ChatMessage.System(visibleText, createdAt)
    }

    private suspend fun persistMessage(
        role: String,
        visibleText: String,
        contextText: String? = visibleText,
        includeInContext: Boolean = true
    ) {
        if (visibleText.isBlank()) return
        val message = com.gastos.domain.model.ChatMessageRecord(
            role = role,
            visibleText = visibleText,
            contextText = contextText,
            includeInContext = includeInContext
        )
        chatMessageRepository.addMessage(message)
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isProcessing) return

        val pending = pendingProductClarification
        if (pending != null) {
            val clarification = FinancialQueryResolver.resolveClarification(
                answer = text,
                requestedItem = pending.requestedItem,
                variants = pending.variants
            )
            if (clarification != null) {
                executeProductClarification(text, pending, clarification)
                return
            }
            pendingProductClarification = null
        }

        _uiState.update { it.copy(messages = it.messages + ChatMessage.User(text), isProcessing = true) }

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

        viewModelScope.launch {
            var responseMode = ChatResponseMode.FREE_COMPLETE
            try {
                persistMessage("user", text)
                responseMode = chatResponseMode(premiumStatusProvider.isPremium.value)
                if (responseMode == ChatResponseMode.PREMIUM_STREAM) {
                    val collected = StringBuilder()
                    appendPlaceholder()
                    aiService.processCommandStreaming(text).collect { chunk ->
                        collected.append(chunk)
                        updateStreamingPlaceholder(collected.toString())
                    }
                    val raw = collected.toString()
                    if (raw.isNotBlank()) {
                        handleFinalResult(raw, text, streaming = true)
                    } else {
                        replacePlaceholder("No recibí respuesta. Inténtalo de nuevo.")
                    }
                } else {
                    val result = aiService.processCommand(text)
                    handleAIResult(result, text)
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error processing message", e)
                val message = "Error al procesar: ${e.message}"
                if (responseMode == ChatResponseMode.PREMIUM_STREAM) {
                    replacePlaceholder(message)
                } else {
                    _uiState.update {
                        it.copy(messages = it.messages + ChatMessage.AI(message), isProcessing = false)
                    }
                }
            }
        }
    }

    private fun executeProductClarification(
        userAnswer: String,
        pending: PendingProductClarification,
        clarification: ResolvedProductClarification
    ) {
        pendingProductClarification = null
        _uiState.update { it.copy(messages = it.messages + ChatMessage.User(userAnswer), isProcessing = true) }
        viewModelScope.launch {
            persistMessage("user", userAnswer)
            val response = executeQuery(
                queryType = "productos",
                periodo = pending.periodo,
                categoria = pending.category,
                provider = pending.provider,
                item = clarification.item,
                matchMode = clarification.matchMode,
                originalQuestion = userAnswer
            )
            handleAIResult(
                result = AIResult(success = true, message = response.text),
                originalQuestion = userAnswer,
                includeInContext = true,
                contextTextOverride = response.contextText
            )
        }
    }

    /**
     * Reemplaza el mensaje placeholder con el resultado final procesado.
     * - Si el modelo respondió en texto plano (chat), lo deja tal cual (ya mostrado).
     * - Si respondió con JSON de acción, ejecuta la acción y reemplaza el mensaje.
     */
    private suspend fun handleFinalResult(raw: String, originalQuestion: String, streaming: Boolean = false) {
        val result = aiService.parseStreamingResult(raw)
        handleAIResult(result, originalQuestion, streaming)
    }

    /**
     * Aplica un AIResult reemplazando el mensaje placeholder. Lógica unificada
     * para streaming (chat) y para resultados síncronos (imagen escaneada).
     */
    private suspend fun handleAIResult(
        result: AIResult,
        originalQuestion: String? = null,
        streaming: Boolean = false,
        includeInContext: Boolean = true,
        contextTextOverride: String? = null
    ) {
        suspend fun showResult(
            text: String,
            shouldPersist: Boolean = true,
            shouldIncludeInContext: Boolean = includeInContext,
            contextText: String? = contextTextOverride ?: text
        ) {
            if (streaming) {
                replacePlaceholder(text)
            } else {
                _uiState.update {
                    it.copy(messages = it.messages + ChatMessage.AI(text), isProcessing = false)
                }
            }
            if (shouldPersist) {
                persistMessage(
                    role = "model",
                    visibleText = text,
                    contextText = contextText,
                    includeInContext = shouldIncludeInContext
                )
            }
        }

        validateAction(result)?.let { message ->
            showResult("❌ $message", shouldPersist = false)
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
                showResult(
                    "✅ Gasto registrado: ${invoice.proveedor} - ${invoice.total} ${invoice.moneda}$driveMessage",
                    shouldIncludeInContext = false
                )
            }
            // Ingreso detectado por OCR (factura marcada como ingreso)
            result.invoice != null && result.invoice!!.tipo == InvoiceType.INGRESO -> {
                val invoice = result.invoice!!
                val income = invoice.toIncome().copy(
                    fuente = invoice.nifEmisor ?: invoice.proveedor
                )
                val incomeId = saveIncomeUseCase(income)
                sheetsSyncManager.upsertIncome(income.copy(id = incomeId))
                showResult(
                    "✅ Ingreso registrado: ${income.concepto} - ${income.monto} ${income.moneda}",
                    shouldIncludeInContext = false
                )
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
                showResult(
                    "✅ Ingreso registrado: ${income.concepto} - $display",
                    shouldIncludeInContext = false
                )
            }
            // Consulta de datos (JSON con action=query)
            result.queryResult != null -> {
                try {
                    val json = JSONObject(result.queryResult!!)
                    if (json.optString("action") == "query") {
                        val queryType = json.optString("query_type")
                        val periodo = FinancialQueryResolver.normalizePeriod(
                            json.optString("periodo", "mes")
                        )
                        val categoria = json.optString("categoria", "").takeIf { it.isNotEmpty() && it != "null" }
                        val provider = json.optString("proveedor", "").takeIf { it.isNotEmpty() && it != "null" }
                        val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                        val matchMode = json.optString("match_mode", "").takeIf { it.isNotEmpty() && it != "null" }
                        val response = executeQuery(
                            queryType = queryType,
                            periodo = periodo,
                            categoria = categoria,
                            provider = provider,
                            item = item,
                            matchMode = matchMode,
                            originalQuestion = originalQuestion
                        )
                        showResult(
                            text = response.text,
                            shouldIncludeInContext = true,
                            contextText = response.contextText
                        )
                    } else {
                        showResult(result.message)
                    }
                } catch (e: Exception) {
                    showResult(result.message)
                }
            }
            result.success -> showResult(result.message)
            else -> showResult("❌ ${result.message}", shouldPersist = false)
        }
    }

    private fun appendPlaceholder() {
        _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(""), isProcessing = true) }
    }

    private fun updateStreamingPlaceholder(text: String) {
        _uiState.update { state ->
            val lastIndex = state.messages.lastIndex
            if (lastIndex >= 0 && state.messages[lastIndex] is ChatMessage.AI) {
                state.copy(messages = state.messages.toMutableList().apply { this[lastIndex] = ChatMessage.AI(text) })
            } else state
        }
    }

    private fun replacePlaceholder(text: String) {
        _uiState.update { state ->
            val lastIndex = state.messages.lastIndex
            if (lastIndex >= 0 && state.messages[lastIndex] is ChatMessage.AI) {
                state.copy(messages = state.messages.toMutableList().apply { this[lastIndex] = ChatMessage.AI(text) }, isProcessing = false)
            } else state.copy(messages = state.messages + ChatMessage.AI(text), isProcessing = false)
        }
    }

    private fun validateAction(result: AIResult): String? {
        result.invoice?.let { invoice ->
            if (!invoice.total.isFinite() || invoice.total <= 0.0) {
                return "El importe detectado debe ser un número finito mayor que cero."
            }
            if (!invoice.ivaPercent.isFinite() || invoice.ivaPercent !in 0.0..100.0 ||
                !invoice.irpfPercent.isFinite() || invoice.irpfPercent !in 0.0..100.0
            ) return "Los porcentajes fiscales detectados no son válidos."
            if (invoice.proveedor.isBlank()) return "No se detectó un proveedor o concepto válido."
            if (invoice.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return "La moneda ${invoice.moneda} no está soportada."
            }
        }
        result.income?.let { income ->
            if (!income.monto.isFinite() || income.monto <= 0.0) {
                return "El importe detectado debe ser un número finito mayor que cero."
            }
            if (!income.ivaPercent.isFinite() || income.ivaPercent !in 0.0..100.0 ||
                !income.irpfPercent.isFinite() || income.irpfPercent !in 0.0..100.0 ||
                (income.totalDevengado != 0.0 && (!income.totalDevengado.isFinite() || income.totalDevengado <= 0.0)) ||
                (income.totalNeto != 0.0 && (!income.totalNeto.isFinite() || income.totalNeto <= 0.0))
            ) return "Los importes o porcentajes fiscales detectados no son válidos."
            if (income.concepto.isBlank()) return "No se detectó un concepto válido."
            if (income.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return "La moneda ${income.moneda} no está soportada."
            }
        }
        if (result.products.any {
                it.descripcion.isBlank() ||
                    !it.cantidad.isFinite() || it.cantidad <= 0.0 ||
                    !it.precioUnitario.isFinite() || it.precioUnitario < 0.0 ||
                    !it.subtotal.isFinite() || it.subtotal < 0.0 ||
                    !it.ivaPercent.isFinite() || it.ivaPercent !in 0.0..100.0
            }
        ) {
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
        provider: String?,
        item: String?,
        matchMode: String?,
        originalQuestion: String?
    ): ExecutedFinancialQuery {
        val (start, end) = getDateRange(periodo)
        val target = currencyPreference.defaultCurrency.value
        val fmt = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES")).apply {
            try { currency = java.util.Currency.getInstance(target) } catch (_: Exception) { /* fallback al locale */ }
        }
        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val allProducts = productRepository.getAllProducts().first()
        val categoryNames = TransactionCategories.defaultExpenseCategories +
            TransactionCategories.defaultIncomeCategories +
            invoices.mapNotNull { it.categoria } +
            incomes.mapNotNull { it.categoria }
        val resolvedQuery = FinancialQueryResolver.resolve(
            queryType = queryType,
            item = item,
            matchMode = matchMode,
            originalQuestion = originalQuestion,
            productNames = allProducts.map { it.descripcion },
            category = categoria,
            provider = provider,
            providerNames = invoices.map { it.proveedor },
            categoryNames = categoryNames
        )
        val resolvedQueryType = resolvedQuery.queryType
        val normalizedCategory = TransactionCategories.normalizeCategory(resolvedQuery.category)
        val resolvedProvider = resolvedQuery.provider
        val periodInvoices = invoices.filter { it.fecha in start..end }
        val periodIncomes = incomes.filter { it.fecha in start..end }
        val filteredInvoices = periodInvoices.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory) &&
                (resolvedProvider == null || FinancialQueryResolver.matchesProvider(it.proveedor, resolvedProvider))
        }
        val filteredIncomes = periodIncomes.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory)
        }
        val filteredInvoiceIds = filteredInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in filteredInvoiceIds }
        val invoiceById = filteredInvoices.associateBy { it.id }
        val periodLabel = when (periodo) {
            "hoy" -> "hoy"
            "semana" -> "esta semana"
            "año" -> "este año"
            else -> "este mes"
        }
        val scopeLabel = buildString {
            append(periodLabel)
            resolvedProvider?.let { append(" en $it") }
            normalizedCategory?.let { append(" · categoría ${TransactionCategories.displayCategory(it)}") }
        }
        val contextText = buildString {
            append("Consulta financiera ejecutada: tipo=${resolvedQueryType ?: "desconocido"}; periodo=$periodo")
            append("; proveedor=${resolvedProvider ?: "ninguno"}")
            append("; categoria=${normalizedCategory ?: "ninguna"}")
            append("; producto=${resolvedQuery.item ?: "ninguno"}.")
        }
        fun result(text: String): ExecutedFinancialQuery = ExecutedFinancialQuery(text, contextText)
        fun convertedInvoiceAmount(invoice: com.gastos.domain.model.Invoice): Double =
            exchangeRateProvider.convert(invoice.total, invoice.moneda, target) ?: 0.0
        fun convertedIncomeAmount(income: Income): Double =
            exchangeRateProvider.convert(income.monto, income.moneda, target) ?: 0.0
        fun convertedProductAmount(product: com.gastos.domain.model.Product): Double {
            val currency = invoiceById[product.invoiceId]?.moneda ?: return 0.0
            return exchangeRateProvider.convert(product.subtotal, currency, target) ?: 0.0
        }
        val totalGastos = filteredInvoices
            .filter { it.tipo == InvoiceType.GASTO }
            .sumOf(::convertedInvoiceAmount)
        val totalIngresos = filteredInvoices
                .filter { it.tipo == InvoiceType.INGRESO }
                .sumOf(::convertedInvoiceAmount) +
            filteredIncomes.sumOf(::convertedIncomeAmount)
        val countGastos = filteredInvoices.count { it.tipo == InvoiceType.GASTO }
        val countIngresos = filteredInvoices.count { it.tipo == InvoiceType.INGRESO } + filteredIncomes.size
        return when (resolvedQueryType) {
            "gastos" -> {
                if (resolvedProvider != null && countGastos == 0) {
                    return result("🛒 No encontré compras en $resolvedProvider $periodLabel.")
                }
                val title = if (resolvedProvider != null) {
                    "🛒 Gastos en $resolvedProvider $periodLabel:\n"
                } else {
                    "💰 Gastos de $scopeLabel:\n"
                }
                val sb = StringBuilder(title)
                sb.append("• Total: ${fmt.format(totalGastos)}\n")
                sb.append("• Cantidad: $countGastos ${if (resolvedProvider != null) "compras" else "transacciones"}\n")
                if (resolvedProvider == null && filteredInvoices.isNotEmpty()) {
                    val byProvider = filteredInvoices.filter { it.tipo == InvoiceType.GASTO }
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
                result(sb.toString().trimEnd())
            }
            "ingresos" -> {
                val sb = StringBuilder("💵 Ingresos de $scopeLabel:\n")
                sb.append("• Total: ${fmt.format(totalIngresos)}\n")
                sb.append("• Cantidad: $countIngresos transacciones\n")
                if (filteredIncomes.isNotEmpty()) {
                    val bySource = filteredIncomes.groupBy { it.fuente ?: it.concepto }
                        .mapValues { (_, values) -> values.sumOf(::convertedIncomeAmount) }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (bySource.isNotEmpty()) {
                        sb.append("\n📋 Fuentes principales:\n")
                        bySource.forEach { (name, total) ->
                            sb.append("  • $name: ${fmt.format(total)}\n")
                        }
                    }
                }
                result(sb.toString().trimEnd())
            }
            "balance" -> {
                val balance = totalIngresos - totalGastos
                val emoji = if (balance >= 0) "✅" else "⚠️"
                val title = if (FinancialQueryResolver.requestsNetBalance(originalQuestion)) {
                    "📊 Ganancia neta de $scopeLabel:"
                } else {
                    "📊 Balance de $scopeLabel:"
                }
                result(
                    "$title\n• Ingresos: ${fmt.format(totalIngresos)} ($countIngresos)\n" +
                        "• Gastos: ${fmt.format(totalGastos)} ($countGastos)\n" +
                        "• Te queda: $emoji ${fmt.format(balance)}"
                )
            }
            "productos", "producto" -> {
                val resolvedItem = resolvedQuery.item
                if (!resolvedItem.isNullOrBlank()) {
                    val matchResult = FinancialQueryResolver.matchProducts(
                        periodProducts,
                        resolvedItem,
                        resolvedQuery.matchMode
                    )
                    if (matchResult.requiresClarification) {
                        pendingProductClarification = PendingProductClarification(
                            periodo = periodo,
                            requestedItem = resolvedItem,
                            provider = resolvedProvider,
                            category = normalizedCategory,
                            variants = matchResult.variants
                        )
                        return result(buildProductClarification(periodo, resolvedItem, matchResult.variants))
                    }
                    if (matchResult.matches.isEmpty()) {
                        return result("📦 No encontré un producto exacto para '$resolvedItem' durante $scopeLabel.")
                    }
                    val total = matchResult.matches.sumOf(::convertedProductAmount)
                    val totalUnits = matchResult.matches.sumOf { it.cantidad }
                    val intro = if (matchResult.usedGroupMode) {
                        "📦 Gasto en variantes de '$resolvedItem' durante $scopeLabel:"
                    } else {
                        "📦 Gasto en '$resolvedItem' durante $scopeLabel:"
                    }
                    return result(buildString {
                        appendLine(intro)
                        appendLine("• Total: ${fmt.format(total)}")
                        appendLine("• Cantidad: ${if (totalUnits % 1.0 == 0.0) totalUnits.toInt() else totalUnits} uds")
                        append("• Coincidencias: ${matchResult.matches.size} líneas · ${matchResult.variants.size} variantes")
                        if (matchResult.usedGroupMode) {
                            val byProduct = matchResult.matches
                                .groupBy { FinancialQueryResolver.normalizeProductName(it.descripcion) }
                                .map { (_, products) ->
                                    Triple(
                                        products.first().descripcion.trim(),
                                        products.sumOf { it.cantidad },
                                        products.sumOf(::convertedProductAmount)
                                    )
                                }
                                .sortedByDescending { it.third }
                            val byProvider = matchResult.matches
                                .groupBy { product -> invoiceById[product.invoiceId]?.proveedor ?: "Sin comercio" }
                                .mapValues { (_, products) -> products.sumOf(::convertedProductAmount) }
                                .toList()
                                .sortedByDescending { it.second }
                            appendLine("\n\nDesglose por producto:")
                            byProduct.take(5).forEach { (name, units, amount) ->
                                val unitsText = if (units % 1.0 == 0.0) units.toInt() else units
                                appendLine("  • $name: $unitsText uds · ${fmt.format(amount)}")
                            }
                            if (byProduct.size > 5) appendLine("  • +${byProduct.size - 5} variantes más")
                            appendLine("\nPor comercio:")
                            byProvider.take(5).forEach { (name, amount) ->
                                appendLine("  • $name: ${fmt.format(amount)}")
                            }
                            if (byProvider.size > 5) append("  • +${byProvider.size - 5} comercios más")
                        }
                    })
                }
                if (periodProducts.isEmpty()) {
                    return result("📦 No hay productos registrados durante $scopeLabel.")
                }
                val sb = StringBuilder("📦 Productos de $scopeLabel:\n")
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
                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) -> values.sumOf(::convertedProductAmount) }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\n💸 Mayor gasto por producto:\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }}: ${fmt.format(total)}\n")
                }
                sb.append("\n📊 Total productos: ${periodProducts.size} items - ${fmt.format(periodProducts.sumOf(::convertedProductAmount))}")
                result(sb.toString().trimEnd())
            }
            else -> {
                result(
                    "No entendí del todo la consulta. Pídeme gastos por comercio, categoría o producto, " +
                        "tus ingresos o tu balance indicando el periodo."
                )
            }
        }
    }

    private fun buildProductClarification(periodo: String, item: String, variants: List<String>): String {
        val options = variants.take(5).mapIndexed { index, variant ->
            "${index + 1}. ${variant.trim()}"
        }.joinToString("\n")
        val instruction = if (variants.size == 1) {
            "¿Te refieres a ese producto? Responde “sí” o escribe su nombre exacto."
        } else {
            "Responde con el número, el nombre exacto o “todas”."
        }
        return "No encontré “$item” como producto exacto durante $periodo.\n\nEncontré:\n$options\n\n$instruction"
    }

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
                handleAIResult(result, includeInContext = false)
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
            chatMessageRepository.clearAll()
            _uiState.update { it.copy(messages = emptyList(), isProcessing = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }
}
