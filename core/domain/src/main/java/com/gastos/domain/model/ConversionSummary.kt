package com.gastos.domain.model

import com.gastos.repository.ExchangeRateProvider

data class MoneyRecord(val id: String, val description: String, val amount: Double, val currency: String)

data class ConversionSummary(
    val amount: Double?,
    val excluded: List<MoneyRecord>,
    val missingCurrencies: Set<String>,
    val updatedAt: Long?,
    val recordCount: Int
) {
    val isPartial: Boolean get() = excluded.isNotEmpty()
    val isUnavailable: Boolean get() = amount == null
    val excludedByCurrency: Map<String, Double> get() = excluded.groupBy { it.currency }
        .mapValues { (_, values) -> values.sumOf { it.amount } }
}

fun ExchangeRateProvider.summarize(records: List<MoneyRecord>, target: String): ConversionSummary =
    summarizeConversions(records, target, lastUpdated.value, ::convert)

fun summarizeConversions(records: List<MoneyRecord>, target: String, updatedAt: Long?,
    convert: (Double, String, String) -> Double?): ConversionSummary {
    val excluded = mutableListOf<MoneyRecord>()
    val amounts = records.mapNotNull { record ->
        convert(record.amount, record.currency, target)?.takeIf(Double::isFinite)
            .also { if (it == null) excluded += record }
    }
    return ConversionSummary(if (records.isNotEmpty() && amounts.isEmpty()) null else amounts.sum(),
        excluded, excluded.map { it.currency }.toSet(), updatedAt, records.size)
}

fun Invoice.moneyRecord() = MoneyRecord("EXPENSE:$documentUuid", proveedor, total, moneda)
fun Income.moneyRecord() = MoneyRecord("INCOME:$documentUuid", concepto, monto, moneda)

/** Empty collections represent zero; collections with no available values do not. */
fun <T> Iterable<T>.sumAvailable(convert: (T) -> Double?): Double? {
    var count = 0
    var available = 0
    var total = 0.0
    for (item in this) {
        count++
        convert(item)?.takeIf(Double::isFinite)?.let { total += it; available++ }
    }
    return if (count > 0 && available == 0) null else total
}

/** Combine available money without treating a missing side as a complete zero. */
fun partialBalance(expenses: ConversionSummary, incomes: ConversionSummary): Double? {
    val count = expenses.recordCount + incomes.recordCount
    val available = count - expenses.excluded.size - incomes.excluded.size
    return if (count > 0 && available == 0) null else (incomes.amount ?: 0.0) - (expenses.amount ?: 0.0)
}
