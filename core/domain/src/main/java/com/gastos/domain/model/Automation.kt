package com.gastos.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

@Serializable
data class Category(val id: String, val kind: DocumentKind, val name: String, val parentId: String = "", val archived: Boolean = false)

@Serializable
enum class RuleMatch { EXACT, CONTAINS }

@Serializable
data class CategoryRule(val id: String, val kind: DocumentKind, val merchant: String, val categoryId: String,
    val subcategoryId: String? = null, val match: RuleMatch = RuleMatch.EXACT, val priority: Int = 0, val enabled: Boolean = true)

@Serializable
data class FinancialMutation(val id: String, val documentUuid: String, val kind: DocumentKind,
    val beforeInvoice: Invoice? = null, val beforeIncome: Income? = null, val expectedRevision: Long,
    val createdAt: Long, val undone: Boolean = false, val resultText: String = "")

@Serializable
data class SyncIntent(val id: String, val documentUuid: String, val accountId: String?, val spreadsheetId: String?, val revisionStamp: Long = System.currentTimeMillis())

@Serializable
data class PaymentEvent(val id: String, val notificationKey: String, val documentUuid: String,
    val postedAt: Long, val undone: Boolean = false, val merchant: String? = null,
    val amount: Double? = null, val currency: String? = null)

@Serializable
data class MonthlyLimit(val id: String, val categoryId: String, val month: String, val amount: Double,
    val currency: String, val repeat: Boolean = false, val notify: Boolean = false, val notified: Boolean = false)

@Serializable
data class AutomationRecord(val id: String, val type: String, val payload: String)

@Serializable
data class AutomationData(val categories: List<Category> = emptyList(), val records: List<AutomationRecord> = emptyList(),
    val limits: List<MonthlyLimit> = emptyList())

object AutomationCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun key(value: String): String = Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun selectRule(rules: List<CategoryRule>, categories: List<Category>, kind: DocumentKind, merchant: String): CategoryRule? {
        val available: Set<String> = categories.filterNot { it.archived }.map { it.id }.toSet()
        val source: String = key(merchant)
        return rules.filter { it.enabled && it.kind == kind && it.categoryId in available &&
            (it.subcategoryId == null || it.subcategoryId in available) && key(it.merchant).isNotEmpty() &&
            if (it.match == RuleMatch.EXACT) source == key(it.merchant) else source.contains(key(it.merchant)) }
            .sortedWith(compareByDescending<CategoryRule> { it.priority }.thenBy { it.match != RuleMatch.EXACT }.thenBy { it.id }).firstOrNull()
    }
}

data class MovementPatch(val amount: Double? = null, val date: Long? = null, val description: String? = null,
    val merchant: String? = null, val category: String? = null, val subcategory: String? = null, val notes: String? = null,
    val currency: String? = null)

data class ReportFilter(val startInclusive: Long? = null, val endExclusive: Long? = null,
    val kind: DocumentKind? = null, val category: String? = null, val subcategory: String? = null) {
    fun includes(date: Long, recordKind: DocumentKind, recordCategory: String?, recordSubcategory: String?): Boolean =
        (startInclusive == null || date >= startInclusive) && (endExclusive == null || date < endExclusive) &&
            (kind == null || kind == recordKind) && (category == null || category == recordCategory) &&
            (subcategory == null || subcategory == recordSubcategory)
}
