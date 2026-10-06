package com.gastos.data.local.entity

import com.gastos.domain.model.AutomationCodec
import com.gastos.domain.model.MovementListEntry
import com.gastos.domain.model.MovementSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Deliberately excludes image, OCR, tax and product payloads from list reads. */
data class MovementListProjection(
    val id: Long,
    val documentUuid: String,
    val description: String,
    val date: Long,
    val amount: Double,
    val currency: String,
    val category: String?,
    val subcategory: String?,
    val documentNumber: String?,
    val notes: String?,
    val issuer: String?,
    val evidenceJson: String?
) {
    fun toListEntry(income: Boolean, legacy: Boolean = false): MovementListEntry {
        val number: String? = documentNumber ?: evidenceJson?.let { raw ->
            // Older/native incomes store the number only in their evidence. Do not decode line models.
            runCatching {
                val evidence = AutomationCodec.json.parseToJsonElement(raw) as? JsonObject
                val document = evidence?.get("document") as? JsonObject
                (document?.get("number") as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
        }
        return MovementListEntry(if (legacy) -id else id, documentUuid,
            if (income && !legacy) MovementSource.INCOME else MovementSource.INVOICE, income,
            description, date, amount, currency, category, subcategory, number, notes, issuer)
    }
}
