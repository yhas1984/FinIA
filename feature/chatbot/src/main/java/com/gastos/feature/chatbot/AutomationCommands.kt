package com.gastos.feature.chatbot

import android.content.Context
import androidx.room.withTransaction
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.storage.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class MovementChoice(val identity: DocumentIdentity, val revision: Long)
data class PendingCorrection(val operationId: String, val patch: MovementPatch, val choices: List<MovementChoice>)
data class AutomationOutcome(val message: String? = null, val pending: PendingCorrection? = null, val mutationId: String? = null, val suggestedRule: CategoryRule? = null)

class AutomationCommands @Inject constructor(@ApplicationContext private val context: Context,
    private val database: AppDatabase, private val catalog: CategoryCatalog,
    private val mutations: FinancialMutationStore, private val operations: CommandOperationStore) {
    suspend fun latestUndoId(): String? = mutations.latestUndoId()
    suspend fun execute(operationId: String, raw: String): AutomationOutcome {
        val command = JSONObject(raw)
        return when (command.getString("action")) {
            "undo_movement" -> AutomationOutcome(mutations.undo(operationId))
            "create_category", "create_rule" -> database.withTransaction {
                val kind = DocumentKind.valueOf(command.getString("kind"))
                catalog.initialize()
                val parentName = command.optional("parent")
                val parent = parentName?.let { catalog.create(kind, it) }
                val message: String = if (command.getString("action") == "create_category") {
                    catalog.create(kind, command.getString("name"), parent?.id.orEmpty())
                    context.getString(R.string.category_saved)
                } else {
                    val root = catalog.create(kind, command.getString("category"))
                    val child = command.optional("subcategory")?.let { catalog.create(kind, it, root.id) }
                    catalog.putRule(CategoryRule("rule:$operationId", kind, command.getString("merchant"), root.id, child?.id,
                        RuleMatch.valueOf(command.optString("match", "EXACT"))))
                    context.getString(R.string.rule_saved)
                }
                operations.completeLocal(operationId, message)
                AutomationOutcome(message)
            }
            "update_movement" -> {
                val kind = DocumentKind.valueOf(command.getString("kind"))
                val values = command.getJSONObject("patch")
                require(values.keys().asSequence().all { it in setOf("amount", "date", "description", "merchant", "category", "subcategory", "notes", "currency") } && values.length() > 0) { "PATCH_INVALID" }
                val date = values.optional("date")?.let { requireNotNull(DocumentValidator.parseDate(it)) { "DATE_INVALID" } }
                val patch = MovementPatch(if (values.has("amount")) values.getDouble("amount") else null, date,
                    values.optional("description"), values.optional("merchant"), values.patchText("category"), values.patchText("subcategory"), values.patchText("notes"), values.optional("currency"))
                val choices = database.withTransaction { mutations.candidates(kind, command.optional("target_uuid"), command.optional("description"), command.optBoolean("last", false)).map { identity ->
                    val revision = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == identity.uuid }?.financialRevision
                        ?: database.incomeDao().documentRecords().firstOrNull { it.documentUuid == identity.uuid }?.financialRevision
                    MovementChoice(identity, requireNotNull(revision))
                } }
                require(choices.isNotEmpty()) { "MOVEMENT_MISSING" }
                val pending = PendingCorrection(operationId, patch, choices)
                if (choices.size == 1) apply(pending, choices.first().identity.uuid)
                else AutomationOutcome(pending = pending)
            }
            else -> error("ACTION_INVALID")
        }
    }
    suspend fun apply(pending: PendingCorrection, uuid: String): AutomationOutcome {
        val choice = pending.choices.first { it.identity.uuid == uuid }
        val mutation = mutations.update(pending.operationId, uuid, pending.patch, choice.revision)
        val source = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == uuid }
        val income = database.incomeDao().documentRecords().firstOrNull { it.documentUuid == uuid }
        val categoryId = source?.categoryId ?: income?.categoryId
        val merchant = source?.proveedor ?: income?.fuente ?: income?.concepto
        val rule = if (pending.patch.category != null && !merchant.isNullOrBlank() && categoryId != null)
            CategoryRule("rule:${pending.operationId}", mutation.kind, merchant, categoryId, source?.subcategoryId ?: income?.subcategoryId) else null
        return AutomationOutcome(mutation.resultText, mutationId = mutation.id, suggestedRule = rule)
    }
    suspend fun saveRule(rule: CategoryRule) { catalog.putRule(rule) }
    suspend fun undo(mutationId: String): String = mutations.undo(UUID.randomUUID().toString(), mutationId)
    private fun JSONObject.patchText(name: String): String? = if (!has(name) || isNull(name)) null else getString(name).trim()
    private fun JSONObject.optional(name: String): String? = if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }
}
