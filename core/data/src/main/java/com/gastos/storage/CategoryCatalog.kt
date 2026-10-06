package com.gastos.storage

import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryCatalog @Inject constructor(private val database: AppDatabase, @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null) {
    val categories: Flow<List<Category>> = database.automationDao().observeCategories().map { it.map(CategoryEntity::toDomain) }
    val rules: Flow<List<CategoryRule>> = database.automationDao().observeRecords("RULE").map { rows -> rows.map { AutomationCodec.json.decodeFromString<CategoryRule>(it.payload) } }
    suspend fun initialize() = database.withTransaction {
        if (database.automationDao().record("catalog:initialized") == null) {
            for (kind: DocumentKind in DocumentKind.entries) {
                val defaults = if (kind == DocumentKind.EXPENSE) TransactionCategories.suggestedExpenseSubcategories else TransactionCategories.suggestedIncomeSubcategories
                defaults.forEach { (name, children) ->
                    val parent = create(kind, name)
                    children.forEach { create(kind, it, parent.id) }
                }
            }
            database.automationDao().putRecord(AutomationRecordEntity("catalog:initialized", "CATALOG", "1"))
        }
    }
    suspend fun list(): List<Category> = database.automationDao().categories().map(CategoryEntity::toDomain)
    /** Interactive creation reports an existing sibling instead of silently reusing it. */
    suspend fun createUnique(kind: DocumentKind, name: String, parentId: String = ""): Category = database.withTransaction {
        require(list().none { it.kind == kind && it.parentId == parentId && AutomationCodec.key(it.name) == AutomationCodec.key(name) }) { "CATEGORY_ALREADY_EXISTS" }
        create(kind, name, parentId)
    }
    suspend fun create(kind: DocumentKind, name: String, parentId: String = ""): Category = database.withTransaction {
        val cleaned: String = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 100) { "CATEGORY_NAME_INVALID" }
        val values: List<Category> = list()
        val existing: Category? = values.firstOrNull { it.kind == kind && it.parentId == parentId && AutomationCodec.key(it.name) == AutomationCodec.key(cleaned) }
        if (parentId.isNotEmpty()) require(values.any { it.id == parentId && it.kind == kind && it.parentId.isEmpty() && (!it.archived || existing != null) }) { "CATEGORY_PARENT_INVALID" }
        existing ?: Category(UUID.randomUUID().toString(), kind, cleaned, parentId).also { database.automationDao().insertCategory(it.toEntity()) }
    }
    suspend fun rename(id: String, name: String) = database.withTransaction {
        val current: Category = list().firstOrNull { it.id == id } ?: error("RECORD_CHANGED")
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "CATEGORY_NAME_INVALID" }
        require(list().none { it.id != id && it.kind == current.kind && it.parentId == current.parentId && AutomationCodec.key(it.name) == AutomationCodec.key(name) }) { "CATEGORY_ALREADY_EXISTS" }
        database.automationDao().updateCategory(current.copy(name = name.trim()).toEntity())
        val affected = database.invoiceDao().documentRecords().filter { it.categoryId == id || it.subcategoryId == id }.map { it.documentUuid } +
            database.incomeDao().documentRecords().filter { it.categoryId == id || it.subcategoryId == id }.map { it.documentUuid }
        val column: String = if (current.parentId.isEmpty()) "categoria" else "subcategoria"
        val reference: String = if (current.parentId.isEmpty()) "categoryId" else "subcategoryId"
        for (table: String in listOf("invoices", "incomes")) database.openHelper.writableDatabase.execSQL(
            "UPDATE $table SET $column=?, financialRevision=financialRevision+1, updatedAt=? WHERE $reference=?", arrayOf<Any?>(name.trim(), System.currentTimeMillis(), id))
        context?.let { runtime -> affected.forEach { FinancialMutationStore(runtime, database).enqueue(it) } }
    }
    suspend fun archive(id: String, archived: Boolean) = database.withTransaction {
        val all: List<Category> = list()
        all.filter { it.id == id || it.parentId == id }.forEach { database.automationDao().updateCategory(it.copy(archived = archived).toEntity()) }
        if (archived) database.automationDao().records("RULE").forEach { row ->
            val rule: CategoryRule = AutomationCodec.json.decodeFromString(row.payload)
            if (rule.categoryId == id || rule.subcategoryId == id) putRule(rule.copy(enabled = false))
        }
    }
    suspend fun putRule(rule: CategoryRule) = database.withTransaction {
        val all: List<Category> = list()
        require(rule.merchant.trim().isNotEmpty() && all.any { it.id == rule.categoryId && it.parentId.isEmpty() && it.kind == rule.kind && (!rule.enabled || !it.archived) }) { "RULE_INVALID" }
        require(rule.subcategoryId == null || all.any { it.id == rule.subcategoryId && it.parentId == rule.categoryId && (!rule.enabled || !it.archived) }) { "RULE_INVALID" }
        database.automationDao().putRecord(AutomationRecordEntity(rule.id, "RULE", AutomationCodec.json.encodeToString(rule)))
    }
    suspend fun updateRule(rule: CategoryRule) = database.withTransaction {
        require(database.automationDao().record(rule.id)?.type == "RULE") { "RECORD_CHANGED" }
        putRule(rule)
    }
    suspend fun deleteRule(id: String) { database.automationDao().record(id)?.takeIf { it.type == "RULE" }?.let { database.automationDao().deleteRecord(id) } }
    private suspend fun classify(kind: DocumentKind, merchant: String, category: String?, child: String?, useRules: Boolean): Pair<Category?, Category?> {
        val rule: CategoryRule? = if (useRules) AutomationCodec.selectRule(database.automationDao().records("RULE").map { AutomationCodec.json.decodeFromString(it.payload) }, list(), kind, merchant) else null
        if (rule != null) return list().first { it.id == rule.categoryId } to list().firstOrNull { it.id == rule.subcategoryId }
        val parent: Category = category?.takeIf(String::isNotBlank)?.let { create(kind, it) } ?: return null to null
        if (useRules && parent.archived) return null to null
        val subcategory: Category? = child?.takeIf(String::isNotBlank)?.let { create(kind, it, parent.id) }
        return parent to subcategory?.takeUnless { useRules && it.archived }
    }
    suspend fun assign(invoice: Invoice, useRules: Boolean = false): Invoice {
        val values: Pair<Category?, Category?> = classify(if (invoice.tipo == InvoiceType.INGRESO) DocumentKind.INCOME else DocumentKind.EXPENSE,
            invoice.proveedor, invoice.categoria, invoice.subcategoria, useRules)
        return invoice.copy(categoryId = values.first?.id, subcategoryId = values.second?.id,
            categoria = values.first?.name, subcategoria = values.second?.name ?: invoice.subcategoria.takeIf { values.first == null })
    }
    suspend fun assign(income: Income, useRules: Boolean = false): Income {
        val values: Pair<Category?, Category?> = classify(DocumentKind.INCOME, income.fuente ?: income.concepto, income.categoria, income.subcategoria, useRules)
        return income.copy(categoryId = values.first?.id, subcategoryId = values.second?.id,
            categoria = values.first?.name, subcategoria = values.second?.name ?: income.subcategoria.takeIf { values.first == null })
    }
}
