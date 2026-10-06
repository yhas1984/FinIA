package com.gastos.domain.model

/** Excluded currencies always make the result partial; income never offsets category spending. */
data class MonthlyLimitProgress(val limit: MonthlyLimit, val category: String, val spent: Double?, val excluded: Int) {
    val partial: Boolean get() = excluded > 0
    val remaining: Double? get() = spent?.let { limit.amount - it }
    val ratio: Double? get() = spent?.div(limit.amount)
}

object MonthlyLimitCalculator {
    fun summarize(limit: MonthlyLimit, category: String, expenses: List<Invoice>, start: Long, end: Long,
        convert: (Double, String, String) -> Double?): MonthlyLimitProgress {
        val rows = expenses.filter { it.tipo == InvoiceType.GASTO && it.categoryId == limit.categoryId && it.fecha >= start && it.fecha < end }
        val converted = rows.map { convert(it.total, it.moneda, limit.currency)?.takeIf { value -> value.isFinite() } }
        val available = converted.filterNotNull()
        return MonthlyLimitProgress(limit, category, if (rows.isEmpty()) 0.0 else available.takeIf { it.isNotEmpty() }?.sum(), converted.count { it == null })
    }
}
