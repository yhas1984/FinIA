package com.gastos.domain.model

/** Negative presentation IDs route legacy income edits to their original invoice row. */
fun Invoice.asLegacyIncome(): Income {
    require(tipo == InvoiceType.INGRESO && id > 0)
    return toIncome().copy(id = -id)
}

fun mergeIncomes(invoices: List<Invoice>, incomes: List<Income>): List<Income> =
    (incomes + invoices.filter { it.tipo == InvoiceType.INGRESO }.map { it.asLegacyIncome() })
        .distinctBy { it.documentUuid }.sortedByDescending { it.fecha }
