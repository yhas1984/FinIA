package com.gastos.domain.model

enum class BankQuery { MISSING, WITHOUT_DOCUMENT, DIFFERENCES;
    companion object {
        fun detect(text: String): BankQuery? {
            val value = BankMatching.key(text)
            if (!Regex("\\b(banco|bancarios|bancarias|extracto|extractos|bank|statement|statements)\\b").containsMatchIn(value)) return null
            if (listOf("sin recibo", "sin documento", "sin factura", "without receipt", "without document", "missing receipt").any(value::contains)) return WITHOUT_DOCUMENT
            if (listOf("diferencia", "no cuadr", "mismatch", "discrepanc").any(value::contains)) return DIFFERENCES
            if (listOf("faltan", "sin registrar", "pendiente", "missing", "unrecorded", "pending").any(value::contains)) return MISSING
            return null
        }
    }
    fun select(rows: List<BankTransaction>, movements: List<BankMovement>): List<BankTransaction> {
        val byId = movements.associateBy { it.source to it.uuid }
        return rows.filter { row ->
            val movement = byId[row.source to row.documentUuid]
            when (this) {
                MISSING -> row.resolution == BankResolution.PENDING
                WITHOUT_DOCUMENT -> movement != null && !movement.hasDocument
                DIFFERENCES -> row.documentUuid != null && (movement == null || movement.expense != row.isExpense || movement.currency != row.currency ||
                    java.math.BigDecimal.valueOf(movement.amount).compareTo(row.signedAmount.abs()) != 0)
            }
        }.sortedByDescending { it.date }
    }
}
