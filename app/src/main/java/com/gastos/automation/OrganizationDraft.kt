package com.gastos.automation

import android.os.Bundle
import com.gastos.domain.model.DocumentKind
import com.gastos.domain.model.RuleMatch
import com.gastos.storage.MonthlyLimitStore

internal enum class OrganizationEditor { CATEGORY, RULE, LIMIT }

/** Only form fields are saved; database entities and successful results remain authoritative. */
internal data class OrganizationDraft(
    val editor: OrganizationEditor,
    val originalId: String = "",
    val kind: DocumentKind = DocumentKind.EXPENSE,
    val parentId: String = "",
    val childId: String = "",
    val name: String = "",
    val merchant: String = "",
    val match: RuleMatch = RuleMatch.EXACT,
    val priority: String = "0",
    val enabled: Boolean = true,
    val month: String = MonthlyLimitStore.currentMonth(),
    val amount: String = "",
    val currency: String = "EUR",
    val repeat: Boolean = false,
    val notify: Boolean = false
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("editor", editor.name); putString("originalId", originalId); putString("kind", kind.name)
        putString("parentId", parentId); putString("childId", childId); putString("name", name)
        putString("merchant", merchant); putString("match", match.name); putString("priority", priority)
        putBoolean("enabled", enabled); putString("month", month); putString("amount", amount)
        putString("currency", currency); putBoolean("repeat", repeat); putBoolean("notify", notify)
    }
    companion object {
        fun fromBundle(bundle: Bundle): OrganizationDraft = OrganizationDraft(
            editor = OrganizationEditor.valueOf(requireNotNull(bundle.getString("editor"))),
            originalId = bundle.getString("originalId").orEmpty(),
            kind = DocumentKind.valueOf(requireNotNull(bundle.getString("kind"))),
            parentId = bundle.getString("parentId").orEmpty(), childId = bundle.getString("childId").orEmpty(),
            name = bundle.getString("name").orEmpty(), merchant = bundle.getString("merchant").orEmpty(),
            match = RuleMatch.valueOf(requireNotNull(bundle.getString("match"))),
            priority = bundle.getString("priority").orEmpty(), enabled = bundle.getBoolean("enabled"),
            month = bundle.getString("month").orEmpty(), amount = bundle.getString("amount").orEmpty(),
            currency = bundle.getString("currency").orEmpty(), repeat = bundle.getBoolean("repeat"), notify = bundle.getBoolean("notify")
        )
    }
}
