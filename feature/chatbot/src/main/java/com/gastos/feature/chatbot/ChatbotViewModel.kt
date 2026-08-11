package com.gastos.feature.chatbot

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import com.gastos.extension.SafeLog
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.feature.backup.RemoteSyncAction
import com.gastos.feature.backup.RemoteSyncOutboxRepository
import com.gastos.feature.backup.RemoteSyncTarget
import com.gastos.domain.usecase.SaveIncomeUseCase
import com.gastos.domain.usecase.SaveInvoiceUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val subcategory: String?,
    val variants: List<String>
)

internal data class ResolvedFinancialQuery(
    val queryType: String?,
    val item: String?,
    val matchMode: String?,
    val category: String?,
    val subcategory: String?,
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
        "hoy", "today" -> "hoy"
        "semana", "esta semana", "semana actual", "lo que va de semana", "week", "this week" -> "semana"
        "ano", "este ano", "ano actual", "lo que va de ano", "year", "this year" -> "año"
        "mes", "este mes", "mes actual", "lo que va de mes", "month", "this month" -> "mes"
        else -> "mes"
    }

    fun normalizeQueryType(value: String?): String? = when (normalizeProductName(value.orEmpty())) {
        "gastos", "expense", "expenses" -> "gastos"
        "ingreso", "ingresos", "income", "incomes" -> "ingresos"
        "balance", "neto", "profit", "net income" -> "balance"
        "productos", "producto", "product", "products" -> "productos"
        "productos por comercio", "productos en comercio", "products by store", "product by store" -> "productos_por_comercio"
        else -> value?.takeIf { it.isNotBlank() }
    }

    fun periodLabel(periodo: String, language: String = Locale.getDefault().language): String = when (normalizePeriod(periodo)) {
        "hoy" -> if (isEnglishLanguage(language)) "today" else "hoy"
        "semana" -> if (isEnglishLanguage(language)) "this week" else "esta semana"
        "año" -> if (isEnglishLanguage(language)) "this year" else "este año"
        else -> if (isEnglishLanguage(language)) "this month" else "este mes"
    }

    private fun isEnglishLanguage(language: String): Boolean = language.equals("en", ignoreCase = true)

    fun resolve(
        queryType: String?,
        item: String?,
        matchMode: String?,
        originalQuestion: String?,
        productNames: List<String>,
        category: String? = null,
        subcategory: String? = null,
        provider: String? = null,
        providerNames: List<String> = emptyList(),
        categoryNames: List<String> = emptyList()
    ): ResolvedFinancialQuery {
        val normalizedType = normalizeQueryType(queryType)
        val explicitItem = item?.trim()?.takeIf { it.isNotEmpty() }
        val explicitCategory = category?.trim()?.takeIf { it.isNotEmpty() }
        val explicitSubcategory = subcategory?.trim()?.takeIf { it.isNotEmpty() }
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
        val normalizedQuestion = normalizeProductName(originalQuestion.orEmpty())
        val mentionsProduct = "producto" in normalizedQuestion || "productos" in normalizedQuestion
        val mentionsPurchaseVerb = PRODUCT_LIST_VERB_TERMS.any { normalizedQuestion.contains(it) }
        val askedForProductList = mentionsProduct && mentionsPurchaseVerb
        val providerProductList = resolvedProvider != null && resolvedItem == null &&
            (mentionsProduct || mentionsPurchaseVerb)
        val resolvedType = when {
            resolvedItem != null -> "productos"
            askedForProductList -> "productos_por_comercio"
            providerProductList -> "productos_por_comercio"
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
            productNames = productNames,
            hasResolvedProvider = resolvedProvider != null
        )
        return ResolvedFinancialQuery(
            queryType = resolvedType,
            item = resolvedItem,
            matchMode = resolvedMode,
            category = resolvedCategory,
            subcategory = explicitSubcategory,
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
        productNames: List<String>,
        hasResolvedProvider: Boolean
    ): String? {
        if (item.isNullOrBlank()) return null
        if (questionRequestsExactProduct(originalQuestion, hasResolvedProvider)) return "exact"
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
        return normalizedQuestion.contains("categoria") || normalizedQuestion.contains("category")
    }

    private fun questionRequestsExactProduct(question: String?, hasResolvedProvider: Boolean): Boolean {
        val normalizedQuestion = normalizeProductName(question.orEmpty())
        val hasExplicitModifier = EXACT_PRODUCT_TERMS.any { term ->
            normalizedQuestion == term || normalizedQuestion.contains("$term ")
        }
        if (!hasExplicitModifier) return false
        return !hasResolvedProvider
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
        "gastos", "ingresos", "balance", "productos", "producto", "productos_por_comercio"
    )
    private val PRODUCT_QUERY_TYPES = setOf("productos", "producto")
    private val PRODUCT_LIST_QUERY_TYPES = setOf("productos_por_comercio", "productos_en_comercio")
    private val EXACT_PRODUCT_TERMS = setOf("solo", "solamente", "unicamente", "exactamente", "producto exacto", "only", "exactly", "precisely")
    private val PRODUCT_LIST_VERB_TERMS = setOf(
        "he comprado",
        "has comprado",
        "hemos comprado",
        "tienes comprado",
        "compre",
        "comprado",
        "compramos",
        "did i buy",
        "what did i buy",
        "have i bought",
        "purchased"
    )
    private val NET_TERMS = setOf(
        "balance", "ganado", "ganancia", "beneficio", "neto", "lo que me queda",
        "ingresos menos gastos", "net income", "profit", "what's left", "whats left", "what s left", "remaining"
    )
    private val INCOME_TERMS = setOf(
        "ingreso", "ingresado", "cobrado", "recibido", "salario", "sueldo", "nomina", "income", "received", "receive", "salary", "wages"
    )
    private val EXPENSE_TERMS = setOf("gasto", "gastado", "comprado", "pagado", "expense", "spent", "paid", "bought")
}

data class ChatbotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val isListening: Boolean = false
)

@HiltViewModel
class ChatbotViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiService: AIService,
    private val chatMessageRepository: ChatMessageRepository,
    private val premiumStatusProvider: PremiumStatusProvider,
    private val voiceRecognitionService: VoiceRecognitionService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
    private val sheetsSyncManager: SheetsSyncManager,
    private val invoiceDriveService: InvoiceDriveService,
    private val remoteSyncOutboxRepository: RemoteSyncOutboxRepository,
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
                    messages = it.messages + ChatMessage.AI(context.getString(R.string.chatbot_no_api_key)),
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
                        replacePlaceholder(context.getString(R.string.chatbot_no_response_retry))
                    }
                } else {
                    val result = aiService.processCommand(text)
                    handleAIResult(result, text)
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error processing message", e)
                val message = context.getString(R.string.chatbot_processing_error, e.message ?: "")
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
                subcategoria = pending.subcategory,
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
        val result = aiService.parseStreamingResult(raw, originalQuestion)
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
            showResult(context.getString(R.string.chatbot_validation_error_prefix, message), shouldPersist = false)
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
                showResult(
                    context.getString(R.string.chatbot_expense_saved, invoice.proveedor, invoice.total.toString(), invoice.moneda),
                    shouldIncludeInContext = false
                )
                syncInvoiceInBackground(savedInvoice)
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
                    context.getString(R.string.chatbot_income_saved, income.concepto, income.monto.toString(), income.moneda),
                    shouldIncludeInContext = false
                )
            }
            // Ingreso detectado por texto
            result.income != null -> {
                val income = result.income!!
                val incomeId = saveIncomeUseCase(income)
                sheetsSyncManager.upsertIncome(income.copy(id = incomeId))
                val display = if (income.totalDevengado > 0 && income.totalNeto > 0) {
                    context.getString(
                        R.string.chatbot_income_breakdown,
                        income.totalDevengado.toString(),
                        income.moneda,
                        income.totalNeto.toString(),
                        income.moneda
                    )
                } else {
                    context.getString(R.string.chatbot_income_saved_with_breakdown, income.monto.toString(), income.moneda)
                }
                showResult(
                    context.getString(R.string.chatbot_income_saved_with_breakdown, income.concepto, display),
                    shouldIncludeInContext = false
                )
            }
            // Consulta de datos (JSON con action=query)
            result.queryResult != null -> {
                try {
                    val json = JSONObject(result.queryResult!!)
                    if (json.optString("action") == "query") {
                        val queryType = json.optString("query_type", json.optString("queryType"))
                        val periodo = FinancialQueryResolver.normalizePeriod(
                            json.optString("periodo", json.optString("period", "mes"))
                        )
                        val categoria = json.optString("categoria", json.optString("category", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val subcategoria = json.optString("subcategoria", json.optString("subcategory", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val provider = json.optString("proveedor", json.optString("provider", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                        val matchMode = json.optString("match_mode", "").takeIf { it.isNotEmpty() && it != "null" }
                        val response = executeQuery(
                            queryType = queryType,
                            periodo = periodo,
                            categoria = categoria,
                            subcategoria = subcategoria,
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
            else -> showResult(context.getString(R.string.chatbot_validation_error_prefix, result.message), shouldPersist = false)
        }
    }

    private fun syncInvoiceInBackground(invoice: Invoice) {
        viewModelScope.launch {
            if (invoice.driveUploadPending) {
                remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.INVOICE_DRIVE, invoice.id, RemoteSyncAction.UPSERT)
            }
            remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, RemoteSyncAction.UPSERT)
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
                return context.getString(R.string.chatbot_invalid_amount)
            }
            if (!invoice.ivaPercent.isFinite() || invoice.ivaPercent !in 0.0..100.0 ||
                !invoice.irpfPercent.isFinite() || invoice.irpfPercent !in 0.0..100.0
            ) return context.getString(R.string.chatbot_invalid_tax_percentages)
            if (invoice.proveedor.isBlank()) return context.getString(R.string.chatbot_invalid_provider)
            if (invoice.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return context.getString(R.string.chatbot_unsupported_currency, invoice.moneda)
            }
        }
        result.income?.let { income ->
            if (!income.monto.isFinite() || income.monto <= 0.0) {
                return context.getString(R.string.chatbot_invalid_amount)
            }
            if (!income.ivaPercent.isFinite() || income.ivaPercent !in 0.0..100.0 ||
                !income.irpfPercent.isFinite() || income.irpfPercent !in 0.0..100.0 ||
                (income.totalDevengado != 0.0 && (!income.totalDevengado.isFinite() || income.totalDevengado <= 0.0)) ||
                (income.totalNeto != 0.0 && (!income.totalNeto.isFinite() || income.totalNeto <= 0.0))
            ) return context.getString(R.string.chatbot_invalid_amounts_or_percentages)
            if (income.concepto.isBlank()) return context.getString(R.string.chatbot_invalid_concept)
            if (income.moneda.uppercase() !in SUPPORTED_CURRENCIES) {
                return context.getString(R.string.chatbot_unsupported_currency, income.moneda)
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
            return context.getString(R.string.chatbot_invalid_product_lines)
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

        return when (normalizePeriodLabel(periodo)) {
            "hoy" -> hoyStart to hoyEnd
            "semana" -> semanaStart to semanaEnd
            "mes" -> mesStart to mesEnd
            "año" -> anoStart to anoEnd
            else -> mesStart to mesEnd
        }
    }

    private fun normalizePeriodLabel(periodo: String): String = when (periodo.lowercase(Locale.ROOT)) {
        "today", "hoy" -> "hoy"
        "week", "this week", "semana", "esta semana" -> "semana"
        "year", "this year", "año", "ano", "este año", "este ano" -> "año"
        else -> "mes"
    }

    private fun isEnglishLocale(): Boolean = activeLanguage().equals("en", ignoreCase = true)

    private suspend fun executeQuery(
        queryType: String?,
        periodo: String,
        categoria: String?,
        subcategoria: String?,
        provider: String?,
        item: String?,
        matchMode: String?,
        originalQuestion: String?
    ): ExecutedFinancialQuery {
        val (start, end) = getDateRange(periodo)
        val target = currencyPreference.defaultCurrency.value
        val fmt = java.text.NumberFormat.getCurrencyInstance(activeLocale()).apply {
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
            subcategory = subcategoria,
            provider = provider,
            providerNames = invoices.map { it.proveedor },
            categoryNames = categoryNames
        )
        val resolvedQueryType = resolvedQuery.queryType
        val normalizedCategory = TransactionCategories.normalizeCategory(resolvedQuery.category)
        val normalizedSubcategory = TransactionCategories.normalizeCategory(resolvedQuery.subcategory)
        val resolvedProvider = resolvedQuery.provider
        val periodInvoices = invoices.filter { it.fecha in start..end }
        val periodIncomes = incomes.filter { it.fecha in start..end }
        val filteredInvoices = periodInvoices.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory) &&
                TransactionCategories.matchesCategory(it.subcategoria, normalizedSubcategory) &&
                (resolvedProvider == null || FinancialQueryResolver.matchesProvider(it.proveedor, resolvedProvider))
        }
        val filteredIncomes = periodIncomes.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory) &&
                TransactionCategories.matchesCategory(it.subcategoria, normalizedSubcategory)
        }
        val filteredInvoiceIds = filteredInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in filteredInvoiceIds }
        val invoiceById = filteredInvoices.associateBy { it.id }
        val language = activeLanguage()
        val periodLabelText = FinancialQueryResolver.periodLabel(periodo, language)
        val scopeLabel = buildString {
            append(periodLabelText)
            resolvedProvider?.let { append(context.getString(R.string.chatbot_scope_provider, it)) }
            normalizedCategory?.let { append(context.getString(R.string.chatbot_scope_category, TransactionCategories.displayCategory(it, language))) }
            normalizedSubcategory?.let { append(context.getString(R.string.chatbot_scope_subcategory, TransactionCategories.displayCategory(it, language))) }
        }
        val contextText = buildString {
            append("Consulta financiera ejecutada: tipo=${resolvedQueryType ?: "desconocido"}; periodo=$periodo")
            append("; proveedor=${resolvedProvider ?: "ninguno"}")
            append("; categoria=${normalizedCategory ?: "ninguna"}")
            append("; subcategoria=${normalizedSubcategory ?: "ninguna"}")
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
        if (resolvedQueryType == "productos_por_comercio") {
            if (periodProducts.isEmpty() && resolvedProvider != null) {
                val fallback = lookupOutsideRange(
                    invoices = invoices.filter { it.tipo == InvoiceType.GASTO },
                    resolvedProvider = resolvedProvider,
                    normalizedCategory = normalizedCategory,
                    requestedPeriod = periodLabelText,
                    convertedAmount = ::convertedInvoiceAmount,
                    fmt = fmt
                )
                return result(fallback)
            }
            return result(
                buildProductsByProviderReport(
                    products = periodProducts,
                    invoicesById = invoiceById,
                    periodoLabel = periodLabelText,
                    providerLabel = resolvedProvider,
                    fmt = fmt,
                    convertedProductAmount = ::convertedProductAmount
                )
            )
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
                    return result(lookupOutsideRange(
                        invoices = invoices,
                        resolvedProvider = resolvedProvider,
                        normalizedCategory = normalizedCategory,
                        requestedPeriod = periodLabelText,
                        convertedAmount = ::convertedInvoiceAmount,
                        fmt = fmt
                    ))
                }
                val title = if (resolvedProvider != null) {
                    context.getString(R.string.chatbot_report_spending_title, resolvedProvider, periodLabelText)
                } else {
                    context.getString(R.string.chatbot_report_spending_scope_title, scopeLabel)
                }
                val sb = StringBuilder(title)
                 sb.append("\n")
                     .append(context.getString(R.string.chatbot_report_total_line, fmt.format(totalGastos)))
                     .append("\n")
                sb.append("\n").append(if (resolvedProvider != null) context.getString(R.string.chatbot_report_count_purchases, countGastos) else context.getString(R.string.chatbot_report_count_transactions, countGastos)).append("\n")
                if (resolvedProvider == null && filteredInvoices.isNotEmpty()) {
                    val byProvider = filteredInvoices.filter { it.tipo == InvoiceType.GASTO }
                        .groupBy { it.proveedor }
                        .mapValues { (_, values) -> values.sumOf(::convertedInvoiceAmount) }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (byProvider.isNotEmpty()) {
                        sb.append("\n").append(context.getString(R.string.chatbot_report_top_providers)).append("\n")
                        byProvider.forEach { (name, total) ->
                            sb.append(context.getString(R.string.chatbot_report_breakdown_line, name, fmt.format(total)))
                                .append('\n')
                        }
                    }
                }
                result(sb.toString().trimEnd())
            }
            "ingresos" -> {
                val sb = StringBuilder(context.getString(R.string.chatbot_report_income_title, scopeLabel)).append("\n")
                sb.append(context.getString(R.string.chatbot_report_total_line, fmt.format(totalIngresos))).append("\n")
                sb.append(context.getString(R.string.chatbot_report_income_count, countIngresos)).append("\n")
                if (filteredIncomes.isNotEmpty()) {
                    val bySource = filteredIncomes.groupBy { it.fuente ?: it.concepto }
                        .mapValues { (_, values) -> values.sumOf(::convertedIncomeAmount) }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (bySource.isNotEmpty()) {
                        sb.append("\n").append(context.getString(R.string.chatbot_report_top_sources)).append("\n")
                        bySource.forEach { (name, total) ->
                            sb.append(context.getString(R.string.chatbot_report_breakdown_line, name, fmt.format(total)))
                                .append('\n')
                        }
                    }
                }
                result(sb.toString().trimEnd())
            }
            "balance" -> {
                val balance = totalIngresos - totalGastos
                val emoji = if (balance >= 0) "✅" else "⚠️"
                val title = if (FinancialQueryResolver.requestsNetBalance(originalQuestion)) {
                    context.getString(R.string.chatbot_report_net_income_title, scopeLabel)
                } else {
                    context.getString(R.string.chatbot_report_balance_title, scopeLabel)
                }
                result(
                    "$title\n${context.getString(R.string.chatbot_report_balance_income, fmt.format(totalIngresos), countIngresos)}\n" +
                        "${context.getString(R.string.chatbot_report_balance_expenses, fmt.format(totalGastos), countGastos)}\n" +
                        context.getString(R.string.chatbot_report_balance_remaining, emoji, fmt.format(balance))
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
                            subcategory = normalizedSubcategory,
                            variants = matchResult.variants
                        )
                        return result(buildProductClarification(periodo, resolvedItem, matchResult.variants, normalizedCategory, normalizedSubcategory, resolvedProvider))
                    }
                    if (matchResult.matches.isEmpty()) {
                        return result(context.getString(R.string.chatbot_product_not_found, resolvedItem, scopeLabel))
                    }
                    val total = matchResult.matches.sumOf(::convertedProductAmount)
                    val totalUnits = matchResult.matches.sumOf { it.cantidad }
                    val intro = if (matchResult.usedGroupMode) {
                        context.getString(R.string.chatbot_report_product_intro_group, resolvedItem, scopeLabel)
                    } else {
                        context.getString(R.string.chatbot_report_product_intro_exact, resolvedItem, scopeLabel)
                    }
                    return result(buildString {
                        appendLine(intro)
                        appendLine(context.getString(R.string.chatbot_report_total_line, fmt.format(total)))
                        appendLine(context.getString(R.string.chatbot_report_units_line, if (totalUnits % 1.0 == 0.0) totalUnits.toInt() else totalUnits))
                        append(context.getString(R.string.chatbot_report_matches, matchResult.matches.size, matchResult.variants.size))
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
                                .groupBy { product -> invoiceById[product.invoiceId]?.proveedor ?: context.getString(R.string.chatbot_report_no_store) }
                                .mapValues { (_, products) -> products.sumOf(::convertedProductAmount) }
                                .toList()
                                .sortedByDescending { it.second }
                            appendLine("\n\n" + context.getString(R.string.chatbot_report_breakdown_product))
                            byProduct.take(5).forEach { (name, units, amount) ->
                                val unitsText = if (units % 1.0 == 0.0) units.toInt() else units
                                appendLine(context.getString(R.string.chatbot_report_product_line, name, unitsText, fmt.format(amount)))
                            }
                            if (byProduct.size > 5) appendLine(context.getString(R.string.chatbot_report_more_variants, byProduct.size - 5))
                            appendLine("\n" + context.getString(R.string.chatbot_report_by_store))
                            byProvider.take(5).forEach { (name, amount) ->
                                appendLine("  • $name: ${fmt.format(amount)}")
                            }
                            if (byProvider.size > 5) append(context.getString(R.string.chatbot_report_more_stores, byProvider.size - 5))
                        }
                    })
                }
                if (periodProducts.isEmpty()) {
                    return result(context.getString(R.string.chatbot_report_no_products, scopeLabel))
                }
                val sb = StringBuilder(context.getString(R.string.chatbot_report_products_header, scopeLabel)).append("\n")
                val byFrequency = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) ->
                        values.sumOf { p -> p.cantidad }.toInt() to
                            values.sumOf(::convertedProductAmount)
                    }
                    .toList().sortedByDescending { it.second.first }.take(5)

                sb.append("\n").append(context.getString(R.string.chatbot_report_most_bought)).append("\n")
                byFrequency.forEachIndexed { i, (name, pair) ->
                    sb.append(context.getString(R.string.chatbot_report_ranked_product, (i + 1).toString(), name.replaceFirstChar { it.uppercase() }, pair.first, fmt.format(pair.second)))
                    sb.append('\n')
                }
                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) -> values.sumOf(::convertedProductAmount) }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\n").append(context.getString(R.string.chatbot_report_highest_spend)).append("\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append(context.getString(R.string.chatbot_report_ranked_product_amount, (i + 1).toString(), name.replaceFirstChar { it.uppercase() }, fmt.format(total)))
                    sb.append('\n')
                }
                sb.append("\n").append(context.getString(R.string.chatbot_report_total_products, periodProducts.size, fmt.format(periodProducts.sumOf(::convertedProductAmount))))
                result(sb.toString().trimEnd())
            }
            else -> {
                result(context.getString(R.string.chatbot_unknown_query))
            }
        }
    }

    private fun buildProductClarification(
        periodo: String,
        item: String,
        variants: List<String>,
        category: String?,
        subcategory: String?,
        provider: String?
    ): String {
        val options = variants.take(5).mapIndexed { index, variant ->
            "${index + 1}. ${variant.trim()}"
        }.joinToString("\n")
        val scope = buildString {
            provider?.let { append(context.getString(R.string.chatbot_scope_provider, it)) }
            category?.let { append(context.getString(R.string.chatbot_scope_category, TransactionCategories.displayCategory(it, activeLanguage()))) }
            subcategory?.let { append(context.getString(R.string.chatbot_scope_subcategory, TransactionCategories.displayCategory(it, activeLanguage()))) }
        }
        val instruction = if (variants.size == 1) {
            context.getString(R.string.chatbot_clarification_single)
        } else {
            context.getString(R.string.chatbot_clarification_multiple)
        }
        return context.getString(
            R.string.chatbot_clarification_message,
            item,
            FinancialQueryResolver.periodLabel(periodo, activeLanguage()),
            scope,
            options,
            instruction
        )
    }

    private fun buildProductsByProviderReport(
        products: List<com.gastos.domain.model.Product>,
        invoicesById: Map<Long, com.gastos.domain.model.Invoice>,
        periodoLabel: String,
        providerLabel: String?,
        fmt: java.text.NumberFormat,
        convertedProductAmount: (com.gastos.domain.model.Product) -> Double
    ): String {
        if (products.isEmpty()) {
            val scope = providerLabel?.let { context.getString(R.string.chatbot_scope_provider, it) }.orEmpty()
            return context.getString(R.string.chatbot_no_products_scope, "$periodoLabel$scope")
        }
        val grouped = if (providerLabel != null) {
            mapOf(providerLabel to products)
        } else {
            products.groupBy { invoicesById[it.invoiceId]?.proveedor ?: context.getString(R.string.chatbot_report_no_store) }
        }
        val scopePrefix = providerLabel?.let { context.getString(R.string.chatbot_scope_provider, it) }.orEmpty()
        val sb = StringBuilder("🧾 ${context.getString(R.string.chatbot_products_bought_prefix, periodoLabel + scopePrefix)}:\n")
        grouped.entries
            .sortedByDescending { (_, items) -> items.sumOf(convertedProductAmount) }
            .forEach { (provider, items) ->
                val providerTotal = items.sumOf(convertedProductAmount)
                val providerUnits = items.sumOf { it.cantidad }
                val providerUnitsText = if (providerUnits % 1.0 == 0.0) providerUnits.toInt() else providerUnits
                sb.append('\n')
                    .append(context.getString(R.string.chatbot_report_provider_total, provider, fmt.format(providerTotal), context.getString(R.string.chatbot_units_short, providerUnitsText)))
                    .append('\n')
                val byProduct = items
                    .groupBy { FinancialQueryResolver.normalizeProductName(it.descripcion) }
                    .map { (_, groupItems) ->
                        Triple(
                            groupItems.first().descripcion.trim(),
                            groupItems.sumOf { it.cantidad },
                            groupItems.sumOf(convertedProductAmount)
                        )
                    }
                    .sortedByDescending { it.third }
                byProduct.forEach { (name, units, amount) ->
                    val unitsText = if (units % 1.0 == 0.0) units.toInt() else units
                    sb.append(context.getString(R.string.chatbot_report_product_line, name, unitsText, fmt.format(amount)))
                    sb.append('\n')
                }
            }
        return sb.toString().trimEnd()
    }

    private fun lookupOutsideRange(
        invoices: List<com.gastos.domain.model.Invoice>,
        resolvedProvider: String,
        normalizedCategory: String?,
        requestedPeriod: String,
        convertedAmount: (com.gastos.domain.model.Invoice) -> Double,
        fmt: java.text.NumberFormat
    ): String {
        val matching = invoices.filter { invoice ->
            (resolvedProvider.isBlank() || FinancialQueryResolver.matchesProvider(invoice.proveedor, resolvedProvider)) &&
                (normalizedCategory == null || TransactionCategories.matchesCategory(invoice.categoria, normalizedCategory))
        }
        if (matching.isEmpty()) {
            return context.getString(R.string.chatbot_no_purchases_any_period, resolvedProvider)
        }
        val expenses = matching.filter { it.tipo == InvoiceType.GASTO }
        val byMonth = expenses.groupBy { monthOf(it.fecha) }
            .mapValues { (_, items) -> items.sumOf(convertedAmount) }
            .filter { it.value > 0.0 }
            .toList()
            .sortedByDescending { it.first }
        val totalAllTime = byMonth.sumOf { it.second }
        val sb = StringBuilder(context.getString(R.string.chatbot_no_purchases_other_periods_prefix, resolvedProvider, requestedPeriod, fmt.format(totalAllTime)))
        sb.append("\n")
        byMonth.forEach { (month, amount) ->
            sb.append(context.getString(R.string.chatbot_report_month_amount, month, fmt.format(amount)))
                .append('\n')
        }
        sb.append("\n").append(context.getString(R.string.chatbot_try_wider_period))
        return sb.toString().trimEnd()
    }

    private fun monthOf(timestamp: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        return java.text.SimpleDateFormat("MMMM yyyy", activeLocale()).format(cal.time)
    }

    private fun activeLocale(): Locale = context.resources.configuration.locales[0]
        ?: Locale.getDefault()

    private fun activeLanguage(): String = activeLocale().language

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
                            it.copy(messages = it.messages + ChatMessage.AI(context.getString(R.string.chatbot_voice_error, voiceResult.text)))
                            }
                            voiceResult.text.isNotBlank() -> sendMessage(voiceResult.text)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        messages = it.messages + ChatMessage.AI(context.getString(R.string.chatbot_voice_error_generic, e.message ?: ""))
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
            it.copy(messages = it.messages + ChatMessage.User(context.getString(R.string.chatbot_scanning_image)), isProcessing = true)
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
                        messages = it.messages + ChatMessage.AI(context.getString(R.string.chatbot_scan_error, e.message ?: "")),
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
