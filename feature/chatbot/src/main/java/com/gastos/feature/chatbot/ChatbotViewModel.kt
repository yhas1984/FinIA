package com.gastos.feature.chatbot

import android.content.Context
import android.net.Uri
import com.gastos.domain.model.sumAvailable
import com.gastos.domain.model.MoneyRecord
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Nombres de mes aceptados (español e inglés) → número de mes. */
    private val NOMBRES_DE_MES: Map<String, Int> = mapOf(
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6,
        "julio" to 7, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10,
        "noviembre" to 11, "diciembre" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11,
        "december" to 12
    )
    private val MESES_ES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )
    private val MESES_EN = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    private val MES_ISO = Regex("""^(\d{4})[-/](\d{1,2})$""")
    private val MES_ISO_INVERSO = Regex("""^(\d{1,2})[-/](\d{4})$""")
    private val RANGO_ISO = Regex("""^(\d{4}-\d{2}-\d{2})\.\.(\d{4}-\d{2}-\d{2})$""")
    private val RANGO_FECHAS = Regex("""(\d{4}-\d{2}-\d{2})[^0-9]{1,12}(\d{4}-\d{2}-\d{2})""")

    private fun claveDeMes(anio: Int, mes: Int): String = "mes:$anio-" + mes.toString().padStart(2, '0')

    private fun desglosarMes(clave: String): Pair<Int, Int>? {
        val partes = clave.removePrefix("mes:").split("-")
        val anio = partes.getOrNull(0)?.toIntOrNull() ?: return null
        val mes = partes.getOrNull(1)?.toIntOrNull() ?: return null
        return if (mes in 1..12) anio to mes else null
    }

    private fun calendario(hoy: Long, zona: TimeZone): Calendar =
        Calendar.getInstance(zona).apply { timeInMillis = hoy }

    private fun instante(zona: TimeZone, anio: Int, mes: Int, dia: Int, hora: Int, minuto: Int, segundo: Int, milis: Int): Long =
        Calendar.getInstance(zona).apply {
            clear()
            set(anio, mes - 1, dia, hora, minuto, segundo)
            set(Calendar.MILLISECOND, milis)
        }.timeInMillis

    private fun primerDiaDelMes(zona: TimeZone, anio: Int, mes: Int): Long =
        instante(zona, anio, mes, 1, 0, 0, 0, 0)

    private fun ultimoDiaDelMes(zona: TimeZone, anio: Int, mes: Int): Long {
        val cal = Calendar.getInstance(zona).apply { timeInMillis = primerDiaDelMes(zona, anio, mes) }
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    private fun fechaIso(texto: String, zona: TimeZone, finDelDia: Boolean): Long? {
        val partes = texto.split("-")
        val anio = partes.getOrNull(0)?.toIntOrNull() ?: return null
        val mes = partes.getOrNull(1)?.toIntOrNull() ?: return null
        val dia = partes.getOrNull(2)?.toIntOrNull() ?: return null
        if (mes !in 1..12 || dia !in 1..31) return null
        return if (finDelDia) instante(zona, anio, mes, dia, 23, 59, 59, 999)
        else instante(zona, anio, mes, dia, 0, 0, 0, 0)
    }

    /**
     * Convierte lo que pide el usuario (o el modelo) en un periodo canónico:
     * `hoy`, `semana`, `mes`, `año`, `mes:2026-08` (un mes concreto) o
     * `rango:2026-08-01:2026-08-15` (fechas explícitas).
     *
     * Importante: si reconoce un mes concreto NO lo sustituye por el mes actual; y si no
     * reconoce nada devuelve `mes` (comportamiento de siempre), nunca un mes inventado.
     */
    fun normalizePeriod(
        value: String?,
        hoy: Long = System.currentTimeMillis(),
        zona: TimeZone = TimeZone.getDefault()
    ): String {
        val texto = value?.trim().orEmpty()
        if (texto.isEmpty() || texto.equals("null", ignoreCase = true)) return "mes"
        if (texto.startsWith("mes:") || texto.startsWith("rango:")) return texto

        // 1) formas con dígitos (se leen del texto original, que conserva guiones y barras)
        MES_ISO.find(texto)?.let { m ->
            val anio = m.groupValues[1].toInt()
            val mes = m.groupValues[2].toInt()
            if (mes in 1..12) return claveDeMes(anio, mes)
        }
        MES_ISO_INVERSO.find(texto)?.let { m ->
            val mes = m.groupValues[1].toInt()
            val anio = m.groupValues[2].toInt()
            if (mes in 1..12) return claveDeMes(anio, mes)
        }
        RANGO_ISO.find(texto.replace(" ", ""))?.let { m ->
            return "rango:${m.groupValues[1]}:${m.groupValues[2]}"
        }
        RANGO_FECHAS.find(texto)?.let { m ->
            return "rango:${m.groupValues[1]}:${m.groupValues[2]}"
        }

        val cal = calendario(hoy, zona)
        val norm = normalizeProductName(texto)

        // 2) expresiones relativas (exactas o dentro de una frase)
        when (norm) {
            "hoy", "today" -> return "hoy"
            "semana", "esta semana", "semana actual", "lo que va de semana", "week", "this week" -> return "semana"
            "ano", "este ano", "ano actual", "lo que va de ano", "year", "this year" -> return "año"
            "mes", "este mes", "mes actual", "lo que va de mes", "month", "this month" -> return "mes"
            "todo", "todo el historico", "historico", "en total", "todos los periodos", "all time", "all", "ever" -> return "todo"
        }
        if (norm.contains("mes pasado") || norm.contains("mes anterior") || norm.contains("last month")) {
            val anterior = cal.clone() as Calendar
            anterior.add(Calendar.MONTH, -1)
            return claveDeMes(anterior.get(Calendar.YEAR), anterior.get(Calendar.MONTH) + 1)
        }
        if (norm.contains("mes que viene") || norm.contains("proximo mes") || norm.contains("next month")) {
            val siguiente = cal.clone() as Calendar
            siguiente.add(Calendar.MONTH, 1)
            return claveDeMes(siguiente.get(Calendar.YEAR), siguiente.get(Calendar.MONTH) + 1)
        }

        // 3) nombre de mes con año opcional: «agosto», «en agosto», «agosto de 2026», «mes de agosto»
        val tokens = norm.split(" ").filter { it.isNotBlank() }
        val numeroDeMes = tokens.firstNotNullOfOrNull { NOMBRES_DE_MES[it] }
        if (numeroDeMes != null) {
            val anioExplicito = tokens.firstOrNull { it.length == 4 && it.all { c -> c.isDigit() } }?.toInt()
            val anio = anioExplicito ?: run {
                // el año más reciente que no sea futuro
                val anioActual = cal.get(Calendar.YEAR)
                val mesActual = cal.get(Calendar.MONTH) + 1
                if (numeroDeMes <= mesActual) anioActual else anioActual - 1
            }
            return claveDeMes(anio, numeroDeMes)
        }

        return "mes"
    }

    /** Devuelve el periodo canónico solo si la expresión es reconocible; si no, null. */
    fun periodOrNull(
        value: String?,
        hoy: Long = System.currentTimeMillis(),
        zona: TimeZone = TimeZone.getDefault()
    ): String? {
        val texto = value?.trim().orEmpty()
        if (texto.isEmpty() || texto.equals("null", ignoreCase = true)) return null
        val canon = normalizePeriod(texto, hoy, zona)
        // normalizePeriod devuelve «mes» tanto para «este mes» como para algo que no entiende:
        // aquí distingo los dos casos comprobando si el texto encaja con las formas conocidas.
        if (canon != "mes") return canon
        val norm = normalizeProductName(texto)
        val esMesActual = norm == "mes" || norm.contains("este mes") || norm.contains("mes actual") ||
            norm.contains("lo que va de mes") || norm == "month" || norm.contains("this month")
        return if (esMesActual) "mes" else null
    }

    /** Convierte dos fechas ISO (desde/hasta) en un periodo canónico de rango, si son válidas. */
    fun rangeFromIso(
        desde: String?,
        hasta: String?,
        zona: TimeZone = TimeZone.getDefault()
    ): String? {
        fun valida(f: String?): String? {
            val limpio = f?.trim().orEmpty()
            if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(limpio)) return null
            val p = limpio.split("-")
            val anio = p[0].toIntOrNull() ?: return null
            val mes = p[1].toIntOrNull() ?: return null
            val dia = p[2].toIntOrNull() ?: return null
            if (mes !in 1..12 || dia !in 1..31) return null
            return limpio
        }
        val d = valida(desde)
        val h = valida(hasta)
        return when {
            d != null && h != null && d <= h -> "rango:$d:$h"
            d != null && h == null -> "rango:$d:$d"
            d == null && h != null -> "rango:$h:$h"
            else -> null
        }
    }

    /** Si la pregunta nombra un periodo concreto (mes, rango, «el mes pasado»), lo devuelve. */
    fun periodFromQuestion(
        question: String?,
        hoy: Long = System.currentTimeMillis(),
        zona: TimeZone = TimeZone.getDefault()
    ): String? {
        if (question.isNullOrBlank()) return null
        val periodo = normalizePeriod(question, hoy, zona)
        return periodo.takeIf { it != "mes" && it != "semana" && it != "año" && it != "hoy" && it != "todo" }
    }

    /** Rango [inicio, fin] en milisegundos del periodo pedido. */
    fun periodRange(
        periodo: String?,
        hoy: Long = System.currentTimeMillis(),
        zona: TimeZone = TimeZone.getDefault()
    ): Pair<Long, Long> {
        val canon = normalizePeriod(periodo, hoy, zona)
        val cal = calendario(hoy, zona)
        val anio = cal.get(Calendar.YEAR)
        val mes = cal.get(Calendar.MONTH) + 1
        return when {
            canon == "hoy" -> {
                val dia = cal.get(Calendar.DAY_OF_MONTH)
                instante(zona, anio, mes, dia, 0, 0, 0, 0) to instante(zona, anio, mes, dia, 23, 59, 59, 999)
            }
            canon == "semana" -> {
                val lunes = cal.clone() as Calendar
                lunes.firstDayOfWeek = Calendar.MONDAY
                lunes.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val inicio = instante(
                    zona, lunes.get(Calendar.YEAR), lunes.get(Calendar.MONTH) + 1,
                    lunes.get(Calendar.DAY_OF_MONTH), 0, 0, 0, 0
                )
                inicio to (inicio + 7L * 24 * 60 * 60 * 1000 - 1)
            }
            canon == "año" -> instante(zona, anio, 1, 1, 0, 0, 0, 0) to instante(zona, anio, 12, 31, 23, 59, 59, 999)
            canon == "todo" -> 0L to instante(zona, anio, 12, 31, 23, 59, 59, 999)
            canon.startsWith("mes:") -> {
                val (a, m) = desglosarMes(canon) ?: (anio to mes)
                primerDiaDelMes(zona, a, m) to ultimoDiaDelMes(zona, a, m)
            }
            canon.startsWith("rango:") -> {
                val partes = canon.removePrefix("rango:").split(":")
                val desde = partes.getOrNull(0)?.let { fechaIso(it, zona, finDelDia = false) }
                val hasta = partes.getOrNull(1)?.let { fechaIso(it, zona, finDelDia = true) }
                if (desde != null && hasta != null) desde to hasta else primerDiaDelMes(zona, anio, mes) to ultimoDiaDelMes(zona, anio, mes)
            }
            else -> primerDiaDelMes(zona, anio, mes) to ultimoDiaDelMes(zona, anio, mes)
        }
    }


    fun normalizeQueryType(value: String?): String? = when (normalizeProductName(value.orEmpty())) {
        "gastos", "expense", "expenses" -> "gastos"
        "ingreso", "ingresos", "income", "incomes" -> "ingresos"
        "balance", "neto", "profit", "net income" -> "balance"
        "productos", "producto", "product", "products" -> "productos"
        "productos por comercio", "productos en comercio", "products by store", "product by store" -> "productos_por_comercio"
        else -> value?.takeIf { it.isNotBlank() }
    }

    fun periodLabel(periodo: String, language: String = Locale.getDefault().language): String {
        val en = isEnglishLanguage(language)
        val canon = normalizePeriod(periodo)
        return when {
            canon == "hoy" -> if (en) "today" else "hoy"
            canon == "semana" -> if (en) "this week" else "esta semana"
            canon == "año" -> if (en) "this year" else "este año"
            canon == "todo" -> if (en) "all time" else "todo el histórico"
            canon.startsWith("mes:") -> {
                val (anio, mes) = desglosarMes(canon) ?: return if (en) "this month" else "este mes"
                if (en) "${MESES_EN[mes - 1]} $anio" else "${MESES_ES[mes - 1]} de $anio"
            }
            canon.startsWith("rango:") -> {
                val partes = canon.removePrefix("rango:").split(":")
                val desde = partes.getOrNull(0).orEmpty()
                val hasta = partes.getOrNull(1) ?: desde
                if (en) "$desde – $hasta" else "del $desde al $hasta"
            }
            else -> if (en) "this month" else "este mes"
        }
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

    /** ¿La pregunta pide comparar contra el periodo anterior? («más que el mes pasado») */
    fun comparaContraElPeriodoAnterior(pregunta: String): Boolean {
        val p = pregunta.lowercase(Locale.ROOT)
        val mencionaAnterior = listOf("mes pasado", "mes anterior", "pasado", "anterior", "last month")
            .any { it in p }
        val mencionaActual = listOf("este mes", "este año", "esta semana", "este periodo", "this month")
            .any { it in p }
        return mencionaAnterior && mencionaActual
    }

    /**
     * Periodo con el que se compara, RELATIVO al periodo consultado: si se pregunta por agosto y se
     * compara con «el mes anterior», el otro periodo es julio (no el mes anterior a hoy).
     */
    fun periodoComparable(
        peticion: String,
        inicioActual: Long,
        finActual: Long = inicioActual,
        ahora: Long = System.currentTimeMillis(),
        zona: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Pair<Long, Long> {
        val p = peticion.lowercase(Locale.ROOT)
        val calendario = java.util.Calendar.getInstance(zona).apply { timeInMillis = inicioActual }
        return when {
            "año" in p || "year" in p -> {
                val inicio = java.util.Calendar.getInstance(zona).apply {
                    timeInMillis = inicioActual
                    set(java.util.Calendar.YEAR, get(java.util.Calendar.YEAR) - 1)
                    set(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val fin = java.util.Calendar.getInstance(zona).apply {
                    timeInMillis = inicio
                    set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER)
                    set(java.util.Calendar.DAY_OF_MONTH, 31)
                    set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
                }.timeInMillis
                inicio to fin
            }
            "semana" in p || "week" in p -> (inicioActual - 7L * 24 * 3600 * 1000) to (inicioActual - 1)
            "mes" in p || "month" in p -> {
                val inicio = java.util.Calendar.getInstance(zona).apply {
                    timeInMillis = inicioActual
                    add(java.util.Calendar.MONTH, -1)
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val fin = java.util.Calendar.getInstance(zona).apply {
                    timeInMillis = inicio
                    set(java.util.Calendar.DAY_OF_MONTH, getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                    set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
                }.timeInMillis
                inicio to fin
            }
            else -> periodRange(normalizePeriod(peticion, ahora, zona), ahora, zona)
        }
    }

    /**
     * ¿La pregunta pide comparar de verdad? Sin lenguaje de comparación, `operacion="comparar"`
     * es un error del modelo: una corrección («dije agosto, no este mes») solo rehace la consulta.
     */
    fun pideComparacion(pregunta: String?): Boolean {
        if (pregunta.isNullOrBlank()) return false
        val p = pregunta.lowercase(Locale.ROOT)
        return listOf(
            "mas que", "más que", "menos que", "comparad", "comparar", "en comparacion", "en comparación",
            "diferencia", "respecto a", "frente a", "que el mes pasado", "que el mes anterior",
            "que el ano pasado", "que el año pasado", "que la semana pasada", "more than", "less than",
            "compared", "versus", " vs "
        ).any { it in p }
    }

    /**
     * ¿La pregunta pide de verdad el histórico completo? Sin una palabra que lo diga
     * («en total», «histórico», «alguna vez», «desde siempre»), el periodo por defecto
     * sigue siendo el mes en curso, como siempre ha hecho la app.
     */
    fun pideTodoElHistorico(pregunta: String?): Boolean {
        if (pregunta.isNullOrBlank()) return false
        val p = pregunta.lowercase(Locale.ROOT)
        return listOf(
            "en total", "total", "todo el historico", "todo el histórico", "historico", "histórico",
            "todos los periodos", "todas las veces", "alguna vez", "desde siempre", "desde el principio",
            "all time", "overall", "ever", "sin contar", "sin incluir", "excepto"
        ).any { it in p }
    }

    /**
     * Producto mencionado en la pregunta, buscado entre los productos que existen de verdad.
     * Red de seguridad para cuando el modelo no rellena `item`: si el usuario dice «bananas»
     * y hay un producto «BANANA», se usa ese. Se exige palabra completa para no confundir
     * «pan» con «paneles» ni «sal» con «salario».
     */
    fun productoMencionado(pregunta: String?, productos: List<String>): String? {
        if (pregunta.isNullOrBlank() || productos.isEmpty()) return null
        val palabras = normalizeProductName(pregunta).split(" ").filter { it.length >= 3 }.toSet()
        if (palabras.isEmpty()) return null
        // candidatos: productos cuyo nombre completo está en la pregunta (el más largo primero)
        val candidatos = productos
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .filter { nombre ->
                val normalizado = normalizeProductName(nombre)
                normalizado.isNotBlank() && (
                    (" " + normalizeProductName(pregunta) + " ").contains(" " + normalizado + " ") ||
                        // singular/plural: «bananas» contiene «banana»
                        normalizado.split(" ").all { palabra ->
                            palabras.any { p -> p == palabra || p == palabra + "s" || p + "s" == palabra }
                        }
                    )
            }
        return candidatos.firstOrNull()
    }

    /** Comercios a excluir deducidos de la propia pregunta, cuando el modelo no los devuelve. */
    fun exclusionesDeLaPregunta(pregunta: String?): List<String> {
        if (pregunta.isNullOrBlank()) return emptyList()
        val patron = Regex(
            "(?:sin contar|sin incluir|excepto|quitando|menos|not counting|excluding)\\s+([\\p{L}0-9 .,\\-']{2,40})",
            RegexOption.IGNORE_CASE
        )
        val bruto = patron.find(pregunta)?.groupValues?.get(1) ?: return emptyList()
        val limpio = bruto.split(",", " y ", " en ", "?", ".", ";").first().trim()
        return if (limpio.length >= 2) listOf(limpio) else emptyList()
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
    val canRetryIncomplete: Boolean = false,
    val isListening: Boolean = false,
    val correction: PendingCorrection? = null,
    val undoMutationId: String? = null,
    val suggestedRule: com.gastos.domain.model.CategoryRule? = null
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
    private val currencyPreference: CurrencyPreference,
    private val commandOperations: com.gastos.storage.CommandOperationStore,
    private val automation: AutomationCommands? = null,
    private val bankImports: com.gastos.storage.BankImportStore? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatbotUiState(isProcessing = true))
    val uiState: StateFlow<ChatbotUiState> = _uiState.asStateFlow()
    private var pendingProductClarification: PendingProductClarification? = null
    private var hasRestoredMessages = false
    private var incompletePrompt: String? = null
    private var replacingIncomplete = false
    private var activeOperationUuid: String? = null
    private var retryOperationUuid: String? = null
    private var lastFinancialQuery: (suspend () -> ExecutedFinancialQuery)? = null

    init {
        observeCapturedDocuments()
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

    private fun observeCapturedDocuments() {
        viewModelScope.launch {
            combine(chatMessageRepository.observeDocumentMessages(),
                uiState.map { it.isProcessing }.distinctUntilChanged()) { messages, processing ->
                messages.takeUnless { processing }
            }.collect { messages ->
                if (messages == null) return@collect
                val documents: List<ChatMessage> = messages.toUiMessages()
                _uiState.update { state ->
                    // Keep the streaming placeholder untouched until the response is finished.
                    if (state.isProcessing) state else state.copy(messages =
                        (state.messages.filterNot { it is ChatMessage.Document } + documents)
                            .sortedBy { it.timestamp }.takeLast(200))
                }
            }
        }
    }

    private suspend fun restoreChat() {
        val messages = chatMessageRepository.getMessages()
        if (!hasRestoredMessages) {
            incompletePrompt = messages.lastOrNull { it.role != "document" }?.takeIf { it.role == "model_incomplete" }?.contextText
            _uiState.update {
                it.copy(messages = messages.toUiMessages().takeLast(200), isProcessing = false, canRetryIncomplete = incompletePrompt != null)
            }
            commandOperations.latestPending()?.let { pending ->
                retryOperationUuid = pending.uuid
                incompletePrompt = pending.text
                _uiState.update { it.copy(canRetryIncomplete = true) }
            }
            val undoId = automation?.latestUndoId()
            _uiState.update { it.copy(undoMutationId = undoId) }
            hasRestoredMessages = true
        }
        aiService.replaceChatHistory(messages.filterNot {
            it.role in setOf("model", "model_incomplete") && ChatResponsePresentation.isStructured(it.visibleText)
        })
    }

    private fun List<com.gastos.domain.model.ChatMessageRecord>.toUiMessages(): List<ChatMessage> = mapNotNull { it.toUiMessage() }
    private fun com.gastos.domain.model.ChatMessageRecord.toUiMessage(): ChatMessage? = when (role) {
        "user" -> ChatMessage.User(visibleText, createdAt)
        "model", "model_incomplete" -> ChatMessage.AI(visibleModelText(visibleText), createdAt)
        "document" -> ChatMessage.Document(id, visibleText, createdAt, documentUuid, documentKind)
        else -> ChatMessage.System(visibleText, createdAt)
    }

    private fun visibleModelText(text: String): String = if (ChatResponsePresentation.isStructured(text)) {
        context.getString(R.string.chatbot_response_unavailable)
    } else text

    private suspend fun persistMessage(
        role: String,
        visibleText: String,
        contextText: String? = visibleText,
        includeInContext: Boolean = true
    ) {
        if (visibleText.isBlank()) return
        val safeText: String = if (role in setOf("model", "model_incomplete")) visibleModelText(visibleText) else visibleText
        val message = com.gastos.domain.model.ChatMessageRecord(
            role = role,
            visibleText = safeText,
            contextText = if (safeText == visibleText || role == "model_incomplete") contextText else null,
            includeInContext = includeInContext && safeText == visibleText
        )
        if (replacingIncomplete && role in setOf("model", "model_incomplete")) {
            chatMessageRepository.replaceLastIncomplete(message)
            replacingIncomplete = false
        } else chatMessageRepository.addMessage(message)
    }

    fun chooseCorrection(uuid: String) {
        val pending = _uiState.value.correction ?: return
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                val outcome = requireNotNull(automation).apply(pending, uuid)
                _uiState.update { it.copy(correction = null, undoMutationId = outcome.mutationId, suggestedRule = outcome.suggestedRule,
                    messages = it.messages + ChatMessage.AI(outcome.message.orEmpty())) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(context.getString(R.string.correction_failed))) } }
            finally { _uiState.update { it.copy(isProcessing = false) } }
        }
    }
    fun dismissCorrection() { _uiState.update { it.copy(correction = null) } }
    fun saveSuggestedRule() {
        val rule = _uiState.value.suggestedRule ?: return
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                requireNotNull(automation).saveRule(rule)
                val text = context.getString(R.string.rule_saved)
                persistMessage("model", text, includeInContext = false)
                _uiState.update { it.copy(suggestedRule = null, messages = it.messages + ChatMessage.AI(text)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(context.getString(R.string.correction_failed))) } }
            finally { _uiState.update { it.copy(isProcessing = false) } }
        }
    }
    fun undoCorrection() {
        val mutationId = _uiState.value.undoMutationId ?: return
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                val message = requireNotNull(automation).undo(mutationId)
                _uiState.update { it.copy(undoMutationId = null, suggestedRule = null, messages = it.messages + ChatMessage.AI(message)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(context.getString(R.string.correction_failed))) } }
            finally { _uiState.update { it.copy(isProcessing = false) } }
        }
    }

    fun retryIncompleteResponse() {
        incompletePrompt?.let { sendMessage(it, replaceIncomplete = true) }
    }

    fun sendMessage(text: String, replaceIncomplete: Boolean = false) {
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

        activeOperationUuid = if (replaceIncomplete) retryOperationUuid ?: java.util.UUID.randomUUID().toString()
            else java.util.UUID.randomUUID().toString()
        retryOperationUuid = activeOperationUuid
        replacingIncomplete = replaceIncomplete
        incompletePrompt = null
        _uiState.update { it.copy(messages = if (replaceIncomplete) it.messages.toMutableList().apply {
            val responseIndex: Int = indexOfLast { message -> message is ChatMessage.AI }
            if (responseIndex >= 0) removeAt(responseIndex)
        } else it.messages + ChatMessage.User(text),
            isProcessing = true, canRetryIncomplete = false) }

        val bankQuery = com.gastos.domain.model.BankQuery.detect(text)
        if (bankQuery != null && bankImports != null) {
            viewModelScope.launch {
                try {
                    val uuid = requireNotNull(activeOperationUuid)
                    commandOperations.begin(uuid, text)
                    val selected = bankQuery.select(bankImports.transactions(), bankImports.movements())
                    val answer = buildString {
                        append(context.getString(R.string.bank_query_count, selected.size))
                        selected.take(20).forEach { row -> append("\n• ${row.description} · ${row.amount} ${row.currency} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(row.date))}") }
                        append("\n\n" + context.getString(R.string.bank_query_hint))
                    }
                    commandOperations.completeLocal(uuid, answer)
                    _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(answer)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(context.getString(R.string.bank_query_failed))) } }
                finally { _uiState.update { it.copy(isProcessing = false) } }
            }
            return
        }

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
            val collected = StringBuilder()
            try {
                val operation = commandOperations.begin(requireNotNull(activeOperationUuid), text)
                if (operation.status == "SAVED" && operation.resultKind in setOf("LOCAL", "UPDATE")) {
                    _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(operation.resultText.orEmpty()), isProcessing = false) }
                    return@launch
                }
                if (operation.status == "SAVED") {
                    val saved = commandOperations.commit(operation.uuid, null, null, emptyList())
                    displayCommandReceipt(saved.receipt, streaming = false)
                    return@launch
                }
                responseMode = chatResponseMode(premiumStatusProvider.isPremium.value)
                if (responseMode == ChatResponseMode.PREMIUM_STREAM) {
                    appendPlaceholder()
                    aiService.processCommandStreaming(text).collect { chunk ->
                        collected.append(chunk)
                        updateStreamingPlaceholder(collected.toString())
                    }
                    val raw = collected.toString()
                    if (raw.isNotBlank()) {
                        handleFinalResult(raw, text, streaming = true)
                    } else {
                        throw IllegalStateException(context.getString(R.string.chatbot_no_response_retry))
                    }
                } else {
                    val result = aiService.processCommand(text)
                    check(result.success) { result.message }
                    handleAIResult(result, text)
                }
                commandOperations.finish(requireNotNull(activeOperationUuid))
                retryOperationUuid = null
            } catch (cancelled: CancellationException) {
                if (collected.isNotEmpty()) {
                    val partial = ChatResponsePresentation.visiblePartial(collected.toString())
                        ?.let { it + "\n\n" + context.getString(R.string.chatbot_response_incomplete) }
                        ?: context.getString(R.string.chatbot_response_incomplete)
                    replacePlaceholder(partial)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        persistMessage("model_incomplete", partial, text, includeInContext = false)
                    }
                } else _uiState.update { it.copy(isProcessing = false) }
                throw cancelled
            } catch (e: Exception) {
                SafeLog.w(TAG, "Message processing interrupted")
                val message = context.getString(R.string.chatbot_processing_error, when (e.message) {
                    "DATE_INVALID" -> context.getString(R.string.automation_invalid_date)
                    "MOVEMENT_MISSING" -> context.getString(R.string.automation_movement_missing)
                    "MOVEMENT_CHANGED", "UNDO_MISSING", "UNDO_EXPIRED" -> context.getString(R.string.correction_failed)
                    "PATCH_INVALID", "AMOUNT_INVALID", "CURRENCY_INVALID", "CURRENCY_BREAKDOWN", "CATEGORY_PARENT_INVALID", "RULE_INVALID", "CATEGORY_NAME_INVALID" -> context.getString(R.string.automation_invalid_fields)
                    else -> e.message.orEmpty()
                })
                if (responseMode == ChatResponseMode.PREMIUM_STREAM) {
                    val partial = ChatResponsePresentation.visiblePartial(collected.toString())
                    val visible = partial?.let { it + "\n\n" + context.getString(R.string.chatbot_response_incomplete) } ?: message
                    replacePlaceholder(visible)
                    incompletePrompt = text
                    _uiState.update { it.copy(canRetryIncomplete = true) }
                    persistMessage("model_incomplete", visible, text, includeInContext = false)
                } else {
                    incompletePrompt = text
                    _uiState.update {
                        it.copy(messages = it.messages + ChatMessage.AI(visibleModelText(message)), isProcessing = false, canRetryIncomplete = true)
                    }
                }
            }
        }
    }

    private fun displayCommandReceipt(receipt: com.gastos.domain.model.ChatMessageRecord, streaming: Boolean) {
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            if (streaming && messages.lastOrNull() is ChatMessage.AI) messages.removeAt(messages.lastIndex)
            if (messages.none { it is ChatMessage.Document && it.id == receipt.id }) {
                receipt.toUiMessage()?.let(messages::add)
            }
            state.copy(messages = messages, isProcessing = false, canRetryIncomplete = false)
        }
        replacingIncomplete = false
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
        check(result.success) { result.message }
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
        contextTextOverride: String? = null,
        onLocalSaved: () -> Unit = {}
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
                    it.copy(messages = it.messages + ChatMessage.AI(visibleModelText(text)), isProcessing = false)
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

        result.automationCommand?.let { raw ->
            val outcome = requireNotNull(automation).execute(requireNotNull(activeOperationUuid), raw)
            _uiState.update { it.copy(correction = outcome.pending, undoMutationId = outcome.mutationId, suggestedRule = outcome.suggestedRule, isProcessing = false) }
            if (outcome.message != null) showResult(outcome.message, shouldPersist = false)
            return
        }
        validateAction(result)?.let { message ->
            showResult(context.getString(R.string.chatbot_validation_error_prefix, message), shouldPersist = false)
            return
        }

        val operationUuid: String? = activeOperationUuid
        if (operationUuid != null && (result.invoice != null || result.income != null)) {
            val expense: Invoice? = result.invoice?.takeIf { it.tipo == InvoiceType.GASTO }
            val income: Income? = result.income ?: result.invoice?.takeIf { it.tipo == InvoiceType.INGRESO }?.toIncome()
            val saved = commandOperations.commit(operationUuid, expense, income, result.products)
            onLocalSaved()
            displayCommandReceipt(saved.receipt, streaming)
            saved.invoice?.let(::syncInvoiceInBackground)
            saved.income?.let(::syncIncomeInBackground)
            return
        }

        when {
            // Gasto (factura)
            result.invoice != null && result.invoice!!.tipo != InvoiceType.INGRESO -> {
                val invoice = result.invoice!!.copy(
                    driveUploadPending = result.invoice!!.imagenUri != null
                )
                val invoiceId = saveInvoiceUseCase(invoice, result.products)
                onLocalSaved()
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
                onLocalSaved()
                syncIncomeInBackground(income.copy(id = incomeId))
                showResult(
                    context.getString(R.string.chatbot_income_saved, income.concepto, income.monto.toString(), income.moneda),
                    shouldIncludeInContext = false
                )
            }
            // Ingreso detectado por texto
            result.income != null -> {
                val income = result.income!!
                val incomeId = saveIncomeUseCase(income)
                onLocalSaved()
                syncIncomeInBackground(income.copy(id = incomeId))
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
                        // Periodo: 1) lo que diga la pregunta del usuario, 2) las fechas ISO que
                        // mande el modelo (desde/hasta), 3) el periodo que mande el modelo.
                        val periodoDelModelo = FinancialQueryResolver.periodOrNull(
                            json.optString("periodo", json.optString("period", ""))
                        )
                        val rangoDelModelo = FinancialQueryResolver.rangeFromIso(
                            json.optString("desde", json.optString("from", "")),
                            json.optString("hasta", json.optString("to", ""))
                        )
                        val periodoDeLaPregunta = FinancialQueryResolver.periodFromQuestion(originalQuestion)
                        val periodo = periodoDeLaPregunta ?: rangoDelModelo ?: periodoDelModelo ?: "mes"
                        if (periodoDeLaPregunta == null && rangoDelModelo == null && periodoDelModelo == null) {
                            SafeLog.w(TAG, "Periodo no reconocido en la consulta; se usa el mes en curso")
                        } else if (periodo != periodoDelModelo) {
                            SafeLog.w(TAG, "Periodo resuelto localmente: ${periodoDelModelo ?: "?"} -> $periodo")
                        }
                        val categoria = json.optString("categoria", json.optString("category", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val subcategoria = json.optString("subcategoria", json.optString("subcategory", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val provider = json.optString("proveedor", json.optString("provider", "")).takeIf { it.isNotEmpty() && it != "null" }
                        val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                        val matchMode = json.optString("match_mode", "").takeIf { it.isNotEmpty() && it != "null" }
                        val operacion = json.optString("operacion", "").takeIf { it.isNotEmpty() && it != "null" }
                        val agruparPor = json.optString("agrupar_por", "").takeIf { it.isNotEmpty() && it != "null" }
                        val excluir = json.optString("excluir", "")
                            .split(",", ";", " y ")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "null" }
                        val compararCon = json.optString("comparar_con", "").takeIf { it.isNotEmpty() && it != "null" }
                        // «sin contar X», «excepto X»: si el modelo no lo devolvió, se aplica en local
                        val excluirFinal = (excluir + FinancialQueryResolver.exclusionesDeLaPregunta(originalQuestion))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        val response = executeQuery(
                            queryType = queryType,
                            periodo = periodo,
                            categoria = categoria,
                            subcategoria = subcategoria,
                            provider = provider,
                            item = item,
                            matchMode = matchMode,
                            originalQuestion = originalQuestion,
                            operacion = operacion,
                            agruparPor = agruparPor,
                            excluir = excluirFinal,
                            compararCon = compararCon
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
            try { sheetsSyncManager.upsertExpense(invoice) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SafeLog.w(TAG, "Expense sync pending reconciliation") }
        }
    }

    private fun syncIncomeInBackground(income: Income) {
        viewModelScope.launch {
            try { sheetsSyncManager.upsertIncome(income) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SafeLog.w(TAG, "Income sync pending reconciliation") }
        }
    }

    private fun appendPlaceholder() {
        _uiState.update { it.copy(messages = it.messages + ChatMessage.AI(""), isProcessing = true) }
    }

    private fun updateStreamingPlaceholder(text: String) {
        // Commands are rendered as their persisted receipt, never as raw JSON.
        if (ChatResponsePresentation.isStructured(text)) return
        _uiState.update { state ->
            val lastIndex = state.messages.lastIndex
            if (lastIndex >= 0 && state.messages[lastIndex] is ChatMessage.AI) {
                state.copy(messages = state.messages.toMutableList().apply { this[lastIndex] = ChatMessage.AI(text) })
            } else state
        }
    }

    private fun replacePlaceholder(text: String) {
        val visibleText: String = visibleModelText(text)
        _uiState.update { state ->
            val lastIndex = state.messages.lastIndex
            if (lastIndex >= 0 && state.messages[lastIndex] is ChatMessage.AI) {
                state.copy(messages = state.messages.toMutableList().apply { this[lastIndex] = ChatMessage.AI(visibleText) }, isProcessing = false)
            } else state.copy(messages = state.messages + ChatMessage.AI(visibleText), isProcessing = false)
        }
    }

    private fun validateAction(result: AIResult): String? {
        result.invoice?.let { invoice ->
            if (!invoice.total.isFinite() || invoice.total <= 0.0) {
                return context.getString(R.string.chatbot_invalid_amount)
            }
            if ((invoice.ivaPercent?.let { rate -> !rate.isFinite() || rate !in 0.0..100.0 } == true) ||
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
            if ((income.ivaPercent?.let { rate -> !rate.isFinite() || rate !in 0.0..100.0 } == true) ||
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
                    (it.ivaPercent?.let { rate -> !rate.isFinite() || rate !in 0.0..100.0 } == true)
            }
        ) {
            return context.getString(R.string.chatbot_invalid_product_lines)
        }
        return null
    }

    private fun getDateRange(periodo: String): Pair<Long, Long> =
        FinancialQueryResolver.periodRange(periodo)

    private fun isEnglishLocale(): Boolean = activeLanguage().equals("en", ignoreCase = true)

    private suspend fun executeQuery(
        queryType: String?,
        periodo: String,
        categoria: String?,
        subcategoria: String?,
        provider: String?,
        item: String?,
        matchMode: String?,
        originalQuestion: String?,
        operacion: String? = null,
        agruparPor: String? = null,
        excluir: List<String> = emptyList(),
        compararCon: String? = null
    ): ExecutedFinancialQuery {
        // «todo el histórico» solo si la pregunta lo pide (o si la operación lo exige por definición:
        // un «al mes de media» sin histórico no tiene sentido); si no, el mes en curso
        val exigeHistorico = operacion == "resumen_mensual" || operacion == "mayor" || operacion == "ultimo"
        val periodo = if (periodo == "todo" && !exigeHistorico &&
            !FinancialQueryResolver.pideTodoElHistorico(originalQuestion)
        ) "mes" else periodo
        lastFinancialQuery = {
            executeQuery(queryType, periodo, categoria, subcategoria, provider, item, matchMode, originalQuestion,
                operacion, agruparPor, excluir, compararCon)
        }
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
        val filteredInvoicesBase = periodInvoices.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory) &&
                TransactionCategories.matchesCategory(it.subcategoria, normalizedSubcategory) &&
                (resolvedProvider == null || FinancialQueryResolver.matchesProvider(it.proveedor, resolvedProvider))
        }
        val filteredIncomesBase = periodIncomes.filter {
            TransactionCategories.matchesCategory(it.categoria, normalizedCategory) &&
                TransactionCategories.matchesCategory(it.subcategoria, normalizedSubcategory)
        }
        // Exclusiones («sin contar Mercadona»): afectan a todo lo que se calcula después
        val exclusiones = excluir.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val filteredInvoices = if (exclusiones.isEmpty()) filteredInvoicesBase else filteredInvoicesBase.filter { inv ->
            exclusiones.none { FinancialQueryResolver.matchesProvider(inv.proveedor, it) }
        }
        val filteredIncomes = if (exclusiones.isEmpty()) filteredIncomesBase else filteredIncomesBase.filter { inc ->
            exclusiones.none { FinancialQueryResolver.matchesProvider(inc.concepto, it) }
        }
        val filteredInvoiceIds = filteredInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in filteredInvoiceIds }
        val invoiceById = filteredInvoices.associateBy { it.id }
        val language = activeLanguage()
        val periodLabelText = FinancialQueryResolver.periodLabel(periodo, language)
        // ── Operaciones que hacen el cálculo en local (fase 2) ──────────────────
        // «comparar» solo si la pregunta compara de verdad; si no, se trata como consulta normal
        val operacion = if (operacion == "comparar" && !FinancialQueryResolver.pideComparacion(originalQuestion)) {
            "total"
        } else operacion
        if (operacion == "comparar" && !originalQuestion.isNullOrBlank() &&
            FinancialQueryResolver.comparaContraElPeriodoAnterior(originalQuestion) &&
            periodo != "mes"
        ) {
            // «¿he gastado más este mes que el pasado?»: el periodo consultado es ESTE mes
            return executeQuery(queryType, "mes", categoria, subcategoria, provider, item, matchMode, originalQuestion,
                operacion, agruparPor, excluir, compararCon)
        }
        if (!operacion.isNullOrBlank() && operacion != "total") {
            // Si la pregunta nombra un producto, manda el producto (aunque el modelo diga «gastos»
            // o no lo haya rellenado: se busca en los productos que existen de verdad)
            val itemLocal = resolvedQuery.item?.takeIf { it.isNotBlank() }
                ?: FinancialQueryResolver.productoMencionado(originalQuestion, allProducts.map { it.descripcion })
            val tipoEfectivo = when {
                resolvedQueryType == "ingresos" -> "ingresos"
                itemLocal != null -> "productos"
                else -> resolvedQueryType ?: "gastos"
            }
            val movimientos: List<ConsultasAvanzadas.Movimiento> = when (tipoEfectivo) {
                "ingresos" -> filteredIncomes.map {
                    ConsultasAvanzadas.Movimiento(it.fecha, it.monto, it.concepto, it.categoria.orEmpty(), "")
                }
                "producto", "productos", "productos_por_comercio" -> {
                    val itemTexto = itemLocal
                    val base = if (itemTexto.isNullOrBlank()) {
                        periodProducts
                    } else {
                        val primero = FinancialQueryResolver.matchProducts(periodProducts, itemTexto, resolvedQuery.matchMode).matches
                        // «bananas» → «BANANA»: segundo intento en singular si no hubo coincidencias
                        if (primero.isEmpty() && itemTexto.endsWith("s", ignoreCase = true)) {
                            // «bananas» → «banana»
                            FinancialQueryResolver.matchProducts(periodProducts, itemTexto.dropLast(1), resolvedQuery.matchMode).matches
                        } else primero
                    }
                    base.mapNotNull { producto ->
                        val factura = invoiceById[producto.invoiceId] ?: return@mapNotNull null
                        ConsultasAvanzadas.Movimiento(
                            factura.fecha, producto.subtotal, factura.proveedor,
                            factura.categoria.orEmpty(), producto.descripcion
                        )
                    }
                }
                else -> filteredInvoices.map {
                    ConsultasAvanzadas.Movimiento(it.fecha, it.total, it.proveedor, it.categoria.orEmpty(), "")
                }
            }
            val avisoSinDatos = if (movimientos.isEmpty()) {
                context.getString(R.string.chatbot_no_movements_period, periodLabelText)
            } else null
            val texto = when {
                avisoSinDatos != null -> avisoSinDatos
                operacion == "resumen_mensual" -> {
                    val resumen = ConsultasAvanzadas.resumenMensual(movimientos)
                    val titulo = itemLocal
                        ?: resolvedProvider ?: normalizedCategory?.let { TransactionCategories.displayCategory(it, language) }
                        ?: tipoEfectivo
                    buildString {
                        appendLine(context.getString(
                            R.string.chatbot_monthly_summary, titulo, fmt.money(resumen.mediaPorMes),
                            fmt.money(resumen.total), resumen.compras, resumen.mesesConDatos
                        ))
                        resumen.meses.take(12).forEach { fila ->
                            appendLine(context.getString(R.string.chatbot_monthly_line, fila.etiqueta, fmt.money(fila.importe)))
                        }
                    }.trimEnd()
                }
                operacion == "ranking" -> {
                    val por = agruparPor ?: "categoria"
                    val filas = ConsultasAvanzadas.ranking(movimientos, { movimiento -> when (por) {
                        "comercio" -> movimiento.comercio
                        "producto" -> movimiento.producto
                        else -> movimiento.categoria
                    } })
                    val nombre = when (por) {
                        "comercio" -> context.getString(R.string.chatbot_ranking_by_store)
                        "producto" -> context.getString(R.string.chatbot_ranking_by_product)
                        else -> context.getString(R.string.chatbot_ranking_by_category)
                    }
                    buildString {
                        appendLine(context.getString(R.string.chatbot_ranking_title, nombre, periodLabelText))
                        filas.forEachIndexed { indice, fila ->
                            appendLine(context.getString(R.string.chatbot_ranking_line, indice + 1, fila.etiqueta, fmt.money(fila.importe), fila.movimientos))
                        }
                    }.trimEnd()
                }
                operacion == "comparar" -> {
                    val otro = compararCon ?: "mes anterior"
                    val (otroInicio, otroFin) = FinancialQueryResolver.periodoComparable(
                        otro, start, end, System.currentTimeMillis()
                    )
                    val otroTotal = when (resolvedQueryType) {
                        "ingresos" -> incomeRepository.getAllIncomes().first()
                            .filter { it.fecha in otroInicio..otroFin }
                            .sumAvailable { exchangeRateProvider.convert(it.monto, it.moneda, target) }
                        else -> invoiceRepository.getAllInvoices().first()
                            .filter { it.fecha in otroInicio..otroFin && it.tipo == InvoiceType.GASTO }
                            .sumAvailable { exchangeRateProvider.convert(it.total, it.moneda, target) }
                    }
                    val actual = ConsultasAvanzadas.total(movimientos)
                    val comparacion = ConsultasAvanzadas.comparar(actual, otroTotal ?: 0.0)
                    val signo = if (comparacion.diferencia >= 0) "+" else "−"
                    val porcentaje = comparacion.porcentaje?.let { String.format(activeLocale(), "%+.0f%%", it) }
                        ?: context.getString(R.string.chatbot_compare_no_baseline)
                    buildString {
                        appendLine(context.getString(R.string.chatbot_compare_line, periodLabelText, fmt.money(actual)))
                        appendLine(context.getString(R.string.chatbot_compare_line, FinancialQueryResolver.periodLabel(otro), fmt.money(comparacion.anterior)))
                        append(context.getString(R.string.chatbot_compare_result, "$signo${fmt.money(kotlin.math.abs(comparacion.diferencia))}", porcentaje))
                    }
                }
                operacion == "mayor" -> ConsultasAvanzadas.mayor(movimientos)?.let { m ->
                    context.getString(R.string.chatbot_movement_line, "🔝 " + context.getString(R.string.chatbot_biggest_movement),
                        m.comercio, fmt.money(m.importe), monthOf(m.fecha))
                } ?: avisoSinDatos
                operacion == "ultimo" -> ConsultasAvanzadas.ultimo(movimientos)?.let { m ->
                    context.getString(R.string.chatbot_movement_line, "🧾 " + context.getString(R.string.chatbot_last_movement),
                        m.comercio, fmt.money(m.importe), monthOf(m.fecha))
                } ?: avisoSinDatos
                else -> null
            }
            if (texto != null) {
                return ExecutedFinancialQuery(
                    texto,
                    "Consulta financiera ejecutada: operacion=${operacion}; periodo=$periodo; " +
                        "proveedor=${resolvedProvider ?: "ninguno"}; item=${resolvedQuery.item ?: "ninguno"}."
                )
            }
        }

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
        val exclusions = linkedMapOf<String, MoneyRecord>()
        val unknownProductTaxes = linkedMapOf<Long, com.gastos.domain.model.Product>()
        fun tracked(id: String, label: String, amount: Double, currency: String): Double? {
            val converted = exchangeRateProvider.convert(amount, currency, target)
            if (converted == null) exclusions[id] = MoneyRecord(id, label, amount, currency)
            return converted
        }
        fun result(text: String): ExecutedFinancialQuery {
            val relevant = exclusions.values.filter { row -> when (resolvedQueryType) {
                "gastos" -> row.id.startsWith("GASTO:")
                "ingresos" -> row.id.startsWith("INGRESO:")
                "producto", "productos", "productos_por_comercio" -> row.id.startsWith("PRODUCT:")
                else -> true
            } }
            val warning = if (relevant.isEmpty()) "" else buildString {
                appendLine(context.getString(R.string.total_partial))
                relevant.groupBy { it.currency }.forEach { (currency, rows) ->
                    appendLine("${rows.size} · ${rows.sumOf { it.amount }} $currency")
                }
                relevant.forEach { appendLine("${it.description}: ${it.amount} ${it.currency}") }
                appendLine(context.getString(R.string.refresh_exchange_rates))
            }
            val taxWarning = if (unknownProductTaxes.isEmpty()) "" else buildString {
                appendLine(context.getString(com.gastos.common.R.string.taxes_partial_products))
                unknownProductTaxes.values.forEach { product -> appendLine("${product.descripcion}: ${product.subtotal} ${invoiceById[product.invoiceId]?.moneda.orEmpty()} (before tax)") }
            }
            val taxContext = buildString {
                appendLine("Recorded tax components, original currency. Bases may overlap; do not add them. Missing detail is unknown, not zero:")
                filteredInvoices.filter { it.taxes.isNotEmpty() }.forEach { appendLine("${it.documentUuid}: ${com.gastos.domain.model.DocumentTaxes.describe(it.taxes, it.moneda)}") }
                filteredIncomes.filter { it.taxes.isNotEmpty() }.forEach { appendLine("${it.documentUuid}: ${com.gastos.domain.model.DocumentTaxes.describe(it.taxes, it.moneda)}") }
            }
            return ExecutedFinancialQuery(warning + taxWarning + text, contextText + "\n" + warning + taxWarning + taxContext)
        }
        fun convertedInvoiceAmount(invoice: com.gastos.domain.model.Invoice): Double? =
            tracked("${invoice.tipo}:${invoice.documentUuid}", invoice.proveedor, invoice.total, invoice.moneda)
        fun convertedIncomeAmount(income: Income): Double? =
            tracked("INGRESO:${income.documentUuid}", income.concepto, income.monto, income.moneda)
        fun convertedProductAmount(product: com.gastos.domain.model.Product): Double? {
            val amount: Double = product.totalIncludingTax ?: run { unknownProductTaxes[product.id] = product; return null }
            return tracked("PRODUCT:${product.id}", product.descripcion, amount, invoiceById[product.invoiceId]?.moneda.orEmpty())
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
            .sumAvailable(::convertedInvoiceAmount)
        val totalIngresos = (filteredInvoices.filter { it.tipo == InvoiceType.INGRESO }.map(::convertedInvoiceAmount) +
            filteredIncomes.map(::convertedIncomeAmount)).sumAvailable { it }
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
                     .append(context.getString(R.string.chatbot_report_total_line, fmt.money(totalGastos)))
                     .append("\n")
                sb.append("\n").append(if (resolvedProvider != null) context.getString(R.string.chatbot_report_count_purchases, countGastos) else context.getString(R.string.chatbot_report_count_transactions, countGastos)).append("\n")
                if (resolvedProvider == null && filteredInvoices.isNotEmpty()) {
                    val byProvider = filteredInvoices.filter { it.tipo == InvoiceType.GASTO }
                        .groupBy { it.proveedor }
                        .mapValues { (_, values) -> values.sumAvailable(::convertedInvoiceAmount) }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (byProvider.isNotEmpty()) {
                        sb.append("\n").append(context.getString(R.string.chatbot_report_top_providers)).append("\n")
                        byProvider.forEach { (name, total) ->
                            sb.append(context.getString(R.string.chatbot_report_breakdown_line, name, fmt.money(total)))
                                .append('\n')
                        }
                    }
                }
                result(sb.toString().trimEnd())
            }
            "ingresos" -> {
                val sb = StringBuilder(context.getString(R.string.chatbot_report_income_title, scopeLabel)).append("\n")
                sb.append(context.getString(R.string.chatbot_report_total_line, fmt.money(totalIngresos))).append("\n")
                sb.append(context.getString(R.string.chatbot_report_income_count, countIngresos)).append("\n")
                if (filteredIncomes.isNotEmpty()) {
                    val bySource = filteredIncomes.groupBy { it.fuente ?: it.concepto }
                        .mapValues { (_, values) -> values.sumAvailable(::convertedIncomeAmount) }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (bySource.isNotEmpty()) {
                        sb.append("\n").append(context.getString(R.string.chatbot_report_top_sources)).append("\n")
                        bySource.forEach { (name, total) ->
                            sb.append(context.getString(R.string.chatbot_report_breakdown_line, name, fmt.money(total)))
                                .append('\n')
                        }
                    }
                }
                result(sb.toString().trimEnd())
            }
            "balance" -> {
                val balance = (filteredInvoices.map { invoice -> convertedInvoiceAmount(invoice)?.let {
                    if (invoice.tipo == InvoiceType.GASTO) -it else it
                } } + filteredIncomes.map(::convertedIncomeAmount)).sumAvailable { it }
                val emoji = if (balance != null && balance >= 0) "✅" else "⚠️"
                val title = if (FinancialQueryResolver.requestsNetBalance(originalQuestion)) {
                    context.getString(R.string.chatbot_report_net_income_title, scopeLabel)
                } else {
                    context.getString(R.string.chatbot_report_balance_title, scopeLabel)
                }
                result(
                    "$title\n${context.getString(R.string.chatbot_report_balance_income, fmt.money(totalIngresos), countIngresos)}\n" +
                        "${context.getString(R.string.chatbot_report_balance_expenses, fmt.money(totalGastos), countGastos)}\n" +
                        context.getString(R.string.chatbot_report_balance_remaining, emoji, fmt.money(balance))
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
                    val total = matchResult.matches.sumAvailable(::convertedProductAmount)
                    val totalUnits = matchResult.matches.sumOf { it.cantidad }
                    val intro = if (matchResult.usedGroupMode) {
                        context.getString(R.string.chatbot_report_product_intro_group, resolvedItem, scopeLabel)
                    } else {
                        context.getString(R.string.chatbot_report_product_intro_exact, resolvedItem, scopeLabel)
                    }
                    return result(buildString {
                        appendLine(intro)
                        appendLine(context.getString(R.string.chatbot_report_total_line, fmt.money(total)))
                        appendLine(context.getString(R.string.chatbot_report_units_line, if (totalUnits % 1.0 == 0.0) totalUnits.toInt() else totalUnits))
                        append(context.getString(R.string.chatbot_report_matches, matchResult.matches.size, matchResult.variants.size))
                        if (matchResult.usedGroupMode) {
                            val byProduct = matchResult.matches
                                .groupBy { FinancialQueryResolver.normalizeProductName(it.descripcion) }
                                .map { (_, products) ->
                                    Triple(
                                        products.first().descripcion.trim(),
                                        products.sumOf { it.cantidad },
                                        products.sumAvailable(::convertedProductAmount)
                                    )
                                }
                                .sortedByDescending { it.third }
                            val byProvider = matchResult.matches
                                .groupBy { product -> invoiceById[product.invoiceId]?.proveedor ?: context.getString(R.string.chatbot_report_no_store) }
                                .mapValues { (_, products) -> products.sumAvailable(::convertedProductAmount) }
                                .toList()
                                .sortedByDescending { it.second }
                            appendLine("\n\n" + context.getString(R.string.chatbot_report_breakdown_product))
                            byProduct.take(5).forEach { (name, units, amount) ->
                                val unitsText = if (units % 1.0 == 0.0) units.toInt() else units
                                appendLine(context.getString(R.string.chatbot_report_product_line, name, unitsText, fmt.money(amount)))
                            }
                            if (byProduct.size > 5) appendLine(context.getString(R.string.chatbot_report_more_variants, byProduct.size - 5))
                            appendLine("\n" + context.getString(R.string.chatbot_report_by_store))
                            byProvider.take(5).forEach { (name, amount) ->
                                appendLine("  • $name: ${fmt.money(amount)}")
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
                            values.sumAvailable(::convertedProductAmount)
                    }
                    .toList().sortedByDescending { it.second.first }.take(5)

                sb.append("\n").append(context.getString(R.string.chatbot_report_most_bought)).append("\n")
                byFrequency.forEachIndexed { i, (name, pair) ->
                    sb.append(context.getString(R.string.chatbot_report_ranked_product, (i + 1).toString(), name.replaceFirstChar { it.uppercase() }, pair.first, fmt.money(pair.second)))
                    sb.append('\n')
                }
                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { (_, values) -> values.sumAvailable(::convertedProductAmount) }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\n").append(context.getString(R.string.chatbot_report_highest_spend)).append("\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append(context.getString(R.string.chatbot_report_ranked_product_amount, (i + 1).toString(), name.replaceFirstChar { it.uppercase() }, fmt.money(total)))
                    sb.append('\n')
                }
                sb.append("\n").append(context.getString(R.string.chatbot_report_total_products, periodProducts.size, fmt.money(periodProducts.sumAvailable(::convertedProductAmount))))
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

    private fun java.text.NumberFormat.money(amount: Number?): String =
        if (amount == null) context.getString(R.string.total_unavailable) else format(amount)

    fun refreshExchangeRates() {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                exchangeRateProvider.refresh()
                lastFinancialQuery?.invoke()?.let { result ->
                    handleAIResult(AIResult(success = true, message = result.text), contextTextOverride = result.contextText)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(messages = it.messages + ChatMessage.System(context.getString(R.string.exchange_refresh_failed))) }
            } finally { _uiState.update { it.copy(isProcessing = false) } }
        }
    }

    private fun buildProductsByProviderReport(
        products: List<com.gastos.domain.model.Product>,
        invoicesById: Map<Long, com.gastos.domain.model.Invoice>,
        periodoLabel: String,
        providerLabel: String?,
        fmt: java.text.NumberFormat,
        convertedProductAmount: (com.gastos.domain.model.Product) -> Double?
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
            .sortedByDescending { (_, items) -> items.sumAvailable(convertedProductAmount) }
            .forEach { (provider, items) ->
                val providerTotal = items.sumAvailable(convertedProductAmount)
                val providerUnits = items.sumOf { it.cantidad }
                val providerUnitsText = if (providerUnits % 1.0 == 0.0) providerUnits.toInt() else providerUnits
                sb.append('\n')
                    .append(context.getString(R.string.chatbot_report_provider_total, provider, fmt.money(providerTotal), context.getString(R.string.chatbot_units_short, providerUnitsText)))
                    .append('\n')
                val byProduct = items
                    .groupBy { FinancialQueryResolver.normalizeProductName(it.descripcion) }
                    .map { (_, groupItems) ->
                        Triple(
                            groupItems.first().descripcion.trim(),
                            groupItems.sumOf { it.cantidad },
                            groupItems.sumAvailable(convertedProductAmount)
                        )
                    }
                    .sortedByDescending { it.third }
                byProduct.forEach { (name, units, amount) ->
                    val unitsText = if (units % 1.0 == 0.0) units.toInt() else units
                    sb.append(context.getString(R.string.chatbot_report_product_line, name, unitsText, fmt.money(amount)))
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
        convertedAmount: (com.gastos.domain.model.Invoice) -> Double?,
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
            .mapValues { (_, items) -> items.sumAvailable(convertedAmount) }
            .filter { it.value == null || it.value!! > 0.0 }
            .toList()
            .sortedByDescending { it.first }
        val totalAllTime = byMonth.sumAvailable { it.second }
        val sb = StringBuilder(context.getString(R.string.chatbot_no_purchases_other_periods_prefix, resolvedProvider, requestedPeriod, fmt.money(totalAllTime)))
        sb.append("\n")
        byMonth.forEach { (month, amount) ->
            sb.append(context.getString(R.string.chatbot_report_month_amount, month, fmt.money(amount)))
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

    fun clearChat() {
        viewModelScope.launch {
            aiService.resetChat()
            lastFinancialQuery = null
            incompletePrompt = null
            chatMessageRepository.clearAll()
            _uiState.update { it.copy(messages = emptyList(), isProcessing = false, canRetryIncomplete = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognitionService.destroy()
    }

}
