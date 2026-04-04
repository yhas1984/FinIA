package com.gastos.feature.ai

import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.domain.model.Invoice
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.text.Normalizer
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Construye el texto que ve el usuario (chat o voz) a partir de [AIResult], incluyendo consultas
 * contra la base de datos. Así la memoria de sesión y la UI comparten la misma respuesta.
 */
@Singleton
class FinanceChatReplyBuilder @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
) {

    suspend fun buildAssistantReply(result: AIResult): String {
        val parsedIncome = result.queryResult?.let { IncomeQueryResultParser.parse(it) }
        return when {
            result.invoice != null -> {
                val invoice = result.invoice!!
                if (invoice.tipo == InvoiceType.INGRESO) {
                    "Perfecto, ya te apunté el ingreso «${invoice.proveedor}»: ${invoice.total} ${invoice.moneda}."
                } else {
                    "Hecho: guardé el gasto en ${invoice.proveedor} por ${invoice.total} ${invoice.moneda}."
                }
            }
            parsedIncome != null ->
                "Listo: ingreso «${parsedIncome.concepto}» por ${parsedIncome.monto} ${parsedIncome.moneda}."
            result.queryResult != null -> {
                val queryResult = result.queryResult!!
                when {
                    queryResult.startsWith("CHAT:") ->
                        queryResult.substringAfter("CHAT:")
                    queryResult.startsWith("QUERY:") -> {
                        val parts = queryResult.split(":")
                        val queryType = parts.getOrNull(1) ?: "balance"
                        val periodo = parts.getOrNull(2) ?: "mes"
                        val categoria = parts.getOrNull(3)
                        val item = parts.getOrNull(4)
                        executeQuery(queryType, periodo, categoria, item)
                    }
                    else -> {
                        try {
                            val json = JSONObject(queryResult)
                            when (json.optString("action", "")) {
                                "chat" -> json.optString("response", result.message)
                                "query" -> {
                                    val queryType = json.optString("query_type", "balance")
                                    val periodo = json.optString("periodo", "mes")
                                    val categoria = json.optString("categoria", "").takeIf { it.isNotEmpty() && it != "null" }
                                    val item = json.optString("item", "").takeIf { it.isNotEmpty() && it != "null" }
                                    executeQuery(queryType, periodo, categoria, item)
                                }
                                else -> result.message
                            }
                        } catch (_: Exception) {
                            result.message
                        }
                    }
                }
            }
            result.success -> result.message
            else ->
                "No he podido hacer eso bien: ${result.message}. ¿Reformulas la pregunta o lo intentamos otra vez?"
        }
    }

    private fun periodoNatural(periodo: String): String = when (periodo.lowercase(Locale.getDefault())) {
        "hoy" -> "hoy"
        "semana" -> "esta semana"
        "mes" -> "este mes"
        "año" -> "este año"
        else -> "este período"
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
        val fmt = NumberFormat.getCurrencyInstance(Locale("es", "ES"))

        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val allProducts = productRepository.getAllProducts().first()

        val periodInvoices = invoices.filter { it.fecha in start..end }
        val periodIncomes = incomes.filter { it.fecha in start..end }
        val periodInvoiceIds = periodInvoices.map { it.id }.toSet()
        val periodProducts = allProducts.filter { it.invoiceId in periodInvoiceIds }

        val itemQuery = item?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (itemQuery != null) {
            val qt = queryType.lowercase(Locale.getDefault())
            if (qt != "ingresos" && qt != "balance") {
                return buildProductSpendAnswer(itemQuery, periodo, periodInvoices, periodProducts, fmt)
            }
        }

        val totalGastos = periodInvoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { it.total }
        val totalIngresos = periodInvoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { it.total } +
            periodIncomes.sumOf { it.monto }
        val countGastos = periodInvoices.count { it.tipo == InvoiceType.GASTO }
        val countIngresos = periodInvoices.count { it.tipo == InvoiceType.INGRESO } + periodIncomes.size

        val pLabel = periodoNatural(periodo)
        val periodoLc = periodo.lowercase(Locale.getDefault())
        return when (queryType.lowercase()) {
            "gastos" -> {
                val sb = StringBuilder()
                val intro = if (periodoLc == "hoy") {
                    "Hoy llevas un total de ${fmt.format(totalGastos)} en gastos"
                } else {
                    "En $pLabel llevas un total de ${fmt.format(totalGastos)} en gastos"
                }
                sb.append(intro)
                sb.append(" ($countGastos movimientos apuntados).\n")
                if (periodInvoices.isNotEmpty()) {
                    val byProvider = periodInvoices.filter { it.tipo == InvoiceType.GASTO }
                        .groupBy { it.proveedor }
                        .mapValues { it.value.sumOf { inv -> inv.total } }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (byProvider.isNotEmpty()) {
                        sb.append("\nDonde más se nota: ")
                        sb.append(
                            byProvider.joinToString(", ") { (name, total) ->
                                "$name (${fmt.format(total)})"
                            }
                        )
                        sb.append(".")
                    }
                }
                sb.toString().trimEnd()
            }
            "ingresos" -> {
                val sb = StringBuilder()
                val ingIntro = if (periodoLc == "hoy") {
                    "Hoy has ingresado en torno a ${fmt.format(totalIngresos)}"
                } else {
                    "${pLabel.replaceFirstChar { it.uppercase() }} has ingresado en torno a ${fmt.format(totalIngresos)}"
                }
                sb.append(ingIntro)
                sb.append(" (sumando $countIngresos entradas).\n")
                if (periodIncomes.isNotEmpty()) {
                    val bySource = periodIncomes.groupBy { it.fuente ?: it.concepto }
                        .mapValues { it.value.sumOf { inc -> inc.monto } }
                        .toList().sortedByDescending { it.second }.take(5)
                    if (bySource.isNotEmpty()) {
                        sb.append("\nLo que más pesa: ")
                        sb.append(
                            bySource.joinToString(", ") { (name, total) ->
                                "$name (${fmt.format(total)})"
                            }
                        )
                        sb.append(".")
                    }
                }
                sb.toString().trimEnd()
            }
            "balance" -> {
                val balance = totalIngresos - totalGastos
                val cierre = when {
                    balance > 0 -> "Te sobran unos ${fmt.format(balance)}; vas sobrado respecto a lo que gastas."
                    balance < 0 -> "Vas por debajo unos ${fmt.format(-balance)}; en este tramo gastas más de lo que entra."
                    else -> "Entradas y salidas cuadran casi al céntimo."
                }
                buildString {
                    val head = if (periodoLc == "hoy") {
                        "Hoy los números quedan así: "
                    } else {
                        "${pLabel.replaceFirstChar { it.uppercase() }} los números quedan así: "
                    }
                    append(head)
                    append("${fmt.format(totalIngresos)} de ingresos ($countIngresos), ")
                    append("${fmt.format(totalGastos)} de gastos ($countGastos). ")
                    append(cierre)
                }
            }
            "productos", "producto" -> {
                if (periodProducts.isEmpty()) {
                    val emptyCtx = if (periodoLc == "hoy") {
                        "Hoy no veo líneas de producto en las facturas"
                    } else {
                        "En $pLabel no veo líneas de producto en las facturas"
                    }
                    return "$emptyCtx: a veces el ticket solo trae el total. " +
                        "Si escaneas uno con artículos desglosados, aquí podré enseñarte rankings."
                }
                val sb = StringBuilder()
                val cestaLead = if (periodoLc == "hoy") {
                    "Te cuento qué se repite más hoy en la cesta:\n\n"
                } else {
                    "Te cuento qué se repite más en la cesta $pLabel:\n\n"
                }
                sb.append(cestaLead)
                val byFrequency = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { it.value.sumOf { p -> p.cantidad }.toInt() to it.value.sumOf { p -> p.subtotal } }
                    .toList().sortedByDescending { it.second.first }.take(5)

                sb.append("Lo que más veces aparece:\n")
                byFrequency.forEachIndexed { i, (name, pair) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }} — ${pair.first} uds, ${fmt.format(pair.second)}\n")
                }

                val byAmount = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                    .mapValues { it.value.sumOf { p -> p.subtotal } }
                    .toList().sortedByDescending { it.second }.take(5)

                sb.append("\nY donde más dinero se va solo por producto:\n")
                byAmount.forEachIndexed { i, (name, total) ->
                    sb.append("  ${i + 1}. ${name.replaceFirstChar { it.uppercase() }} — ${fmt.format(total)}\n")
                }

                sb.append(
                    "\nEn total son ${periodProducts.size} apuntes de producto " +
                        "(${fmt.format(periodProducts.sumOf { it.subtotal })})."
                )
                sb.toString().trimEnd()
            }
            else -> {
                val balance = totalIngresos - totalGastos
                val sb = StringBuilder()
                val resHead = if (periodoLc == "hoy") {
                    "Resumen rápido de hoy: "
                } else {
                    "Resumen rápido $pLabel: "
                }
                sb.append(resHead)
                sb.append("${fmt.format(totalIngresos)} entrando por $countIngresos conceptos, ")
                sb.append("${fmt.format(totalGastos)} saliendo en $countGastos gastos; ")
                sb.append("el neto queda en ${fmt.format(balance)}.\n")
                if (periodProducts.isNotEmpty()) {
                    sb.append("Tienes ${periodProducts.size} líneas de producto registradas.\n")
                    val topProduct = periodProducts.groupBy { it.descripcion.lowercase().trim() }
                        .mapValues { it.value.sumOf { p -> p.cantidad }.toInt() }
                        .toList().sortedByDescending { it.second }.firstOrNull()
                    if (topProduct != null) {
                        sb.append(
                            "El que más suele repetirse es ${topProduct.first.replaceFirstChar { it.uppercase() }} " +
                                "(${topProduct.second} uds en total)."
                        )
                    }
                }
                sb.toString().trimEnd()
            }
        }
    }

    private fun normalizeForProductSearch(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{M}+"), "")
    }

    private fun productMatchesItemDescription(descripcion: String, item: String): Boolean {
        val d = normalizeForProductSearch(descripcion)
        val normalizedItem = normalizeForProductSearch(item.trim())
        if (normalizedItem.isEmpty()) return false
        val tokens = normalizedItem.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
        if (tokens.isEmpty()) {
            val short = normalizedItem.filter { !it.isWhitespace() }
            return short.isNotEmpty() && d.contains(short)
        }
        return tokens.all { d.contains(it) }
    }

    private fun buildProductSpendAnswer(
        item: String,
        periodo: String,
        periodInvoices: List<Invoice>,
        periodProducts: List<Product>,
        fmt: NumberFormat,
    ): String {
        val gastoInvoicesById = periodInvoices.filter { it.tipo == InvoiceType.GASTO }.associateBy { it.id }
        val candidates = periodProducts.filter { it.invoiceId in gastoInvoicesById.keys }
        val matched = candidates.filter { productMatchesItemDescription(it.descripcion, item) }
        val pLabel = periodoNatural(periodo)
        if (matched.isEmpty()) {
            return "No encuentro nada que encaje con «$item» $pLabel en las líneas de los tickets. " +
                "A veces el nombre en la factura es distinto — prueba con otra palabra clave — " +
                "o mira si ese gasto tiene productos desglosados."
        }
        val total = matched.sumOf { it.subtotal }
        val unidades = matched.sumOf { it.cantidad }
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))
        val sb = StringBuilder()
        sb.append("Sobre «$item» $pLabel sumas ${fmt.format(total)} ")
        sb.append("(${matched.size} líneas, ${formatQuantitySum(unidades)} uds en total).\n\n")
        sb.append("Te lo desgloso por ticket:\n")
        val byInvoice = matched.groupBy { it.invoiceId }.entries.sortedByDescending { gastoInvoicesById[it.key]?.fecha ?: 0L }
        for ((invId, lines) in byInvoice) {
            val inv = gastoInvoicesById[invId] ?: continue
            sb.appendLine("${inv.proveedor}, ${dateFmt.format(Date(inv.fecha))}:")
            lines.sortedBy { it.descripcion.lowercase(Locale.getDefault()) }.forEach { p ->
                sb.appendLine("   · ${p.descripcion}: ${fmt.format(p.subtotal)} (${formatQuantitySum(p.cantidad)} uds)")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun formatQuantitySum(q: Double): String {
        return if (abs(q - q.toLong()) < 1e-6) q.toLong().toString()
        else String.format(Locale.getDefault(), "%.2f", q)
    }
}
