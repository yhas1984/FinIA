package com.gastos.storage

import android.content.Context
import com.gastos.data.R
import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.domain.model.DocumentIdentity
import com.gastos.domain.model.PayrollDateBasis
import java.text.NumberFormat
import java.util.Currency

/** A receipt of the local commit, built only from the values actually persisted. */
internal fun createDocumentChatMessage(context: Context, document: DocumentIdentity): ChatMessageEntity {
    val currency: Currency? = runCatching { Currency.getInstance(document.currency) }.getOrNull()
    val formatter: NumberFormat = (if (currency != null) NumberFormat.getCurrencyInstance(context.resources.configuration.locales[0])
        else NumberFormat.getNumberInstance(context.resources.configuration.locales[0])).apply {
        if (currency != null) this.currency = currency
        minimumFractionDigits = currency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
        maximumFractionDigits = minimumFractionDigits
    }
    val amount: String = formatter.format(document.amount) + if (currency == null) " ${document.currency}" else ""
    val text: String = buildString {
        append(context.getString(if (document.isIncome) R.string.document_chat_income else R.string.document_chat_expense))
        document.issuer.takeIf(String::isNotBlank)?.let { append('\n').append(it) }
        append('\n').append(amount).append(" · ").append(document.date)
        if (document.evidence?.document?.payroll?.dateBasis == PayrollDateBasis.PERIOD_END) {
            append('\n').append(context.getString(R.string.document_chat_period_date))
        }
        document.number?.takeIf(String::isNotBlank)?.let { number ->
            append('\n').append(context.getString(R.string.document_chat_reference, number))
        }
    }
    // Financial receipts are history, not instructions to the model to create another movement.
    return ChatMessageEntity(documentUuid = document.uuid, documentKind = if (document.isIncome) "INCOME" else "EXPENSE", role = "document", visibleText = text, includeInContext = false)
}
