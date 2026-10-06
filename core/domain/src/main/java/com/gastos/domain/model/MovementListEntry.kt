package com.gastos.domain.model

import java.text.Normalizer
import java.util.Locale

/** Read-only list projection. Details are loaded only when a movement is opened. */
data class MovementListEntry(
    val id: Long,
    val documentUuid: String,
    val source: MovementSource,
    val isIncome: Boolean,
    val description: String,
    val date: Long,
    val amount: Double,
    val currency: String,
    val category: String?,
    val subcategory: String?,
    val documentNumber: String? = null,
    val notes: String? = null,
    val issuer: String? = null
) {
    fun movementReference(): MovementReference = MovementReference(source, documentUuid)
    fun moneyRecord(): MoneyRecord = MoneyRecord("${if (isIncome) "INCOME" else "EXPENSE"}:$documentUuid", description, amount, currency)
    private val searchText: String by lazy(LazyThreadSafetyMode.NONE) {
        normalizeMovementSearch(listOfNotNull(description, issuer, documentNumber, notes).joinToString(" "))
    }
    fun matchesSearch(query: String): Boolean = matchesTerms(normalizeMovementSearch(query).split(' ').filter(String::isNotEmpty))
    internal fun matchesTerms(terms: List<String>): Boolean = terms.isEmpty() || terms.all(searchText::contains)
}

data class MovementListFilter(
    val query: String = "",
    val category: String? = null,
    val subcategory: String? = null,
    val start: Long? = null,
    val end: Long? = null
) {
    private val terms: List<String> = normalizeMovementSearch(query).split(' ').filter(String::isNotEmpty)
    fun includes(row: MovementListEntry): Boolean = isWithinPeriod(row.date, start, end) &&
        when (category) {
            null -> true
            UNCATEGORIZED -> TransactionCategories.normalizeCategory(row.category) == null
            else -> TransactionCategories.matchesCategory(row.category, category)
        } && (subcategory.isNullOrBlank() || TransactionCategories.matchesCategory(row.subcategory, subcategory)) &&
        row.matchesTerms(terms)

    companion object { const val UNCATEGORIZED: String = "__uncategorized__"; const val PAGE_SIZE: Int = 50 }
}

private val accents: Regex = Regex("\\p{M}+")
private val whitespace: Regex = Regex("\\s+")
fun normalizeMovementSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(accents, "").lowercase(Locale.ROOT).trim().replace(whitespace, " ")

fun Invoice.listEntry(): MovementListEntry = MovementListEntry(
    id, documentUuid, MovementSource.INVOICE, tipo == InvoiceType.INGRESO, proveedor, fecha, total, moneda,
    categoria, subcategoria, numeroFactura, notas)

fun Income.listEntry(): MovementListEntry = MovementListEntry(
    id, documentUuid, if (id < 0) MovementSource.INVOICE else MovementSource.INCOME, true, concepto, fecha, monto, moneda,
    categoria, subcategoria, evidence?.document?.number, notas, fuente)
