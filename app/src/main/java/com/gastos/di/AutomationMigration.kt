package com.gastos.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gastos.domain.model.AutomationCodec
import com.gastos.domain.model.TransactionCategories
import java.util.UUID

val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, parentId TEXT NOT NULL, archived INTEGER NOT NULL, nameKey TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_categories_kind_parentId_nameKey ON categories(kind, parentId, nameKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS automation_records (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_automation_records_type ON automation_records(type)")
        for (table: String in listOf("invoices", "incomes")) {
            for (column: String in listOf("categoryId", "subcategoryId", "sourceMimeType", "sourceName")) db.execSQL("ALTER TABLE $table ADD COLUMN $column TEXT")
            db.execSQL("ALTER TABLE $table ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE $table ADD COLUMN manualAmountAdjusted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $table ADD COLUMN financialRevision INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE document_drafts ADD COLUMN sourceName TEXT")
        db.execSQL("ALTER TABLE document_drafts ADD COLUMN sourceMimeType TEXT")
        TransactionCategories.defaultExpenseCategories.forEach { createCategory(db, "EXPENSE", it) }
        TransactionCategories.defaultIncomeCategories.forEach { createCategory(db, "INCOME", it) }
        for (table: String in listOf("invoices", "incomes")) {
            val query: String = if (table == "incomes") "SELECT id, categoria, subcategoria, 'INCOME' AS kind FROM incomes"
                else "SELECT id, categoria, subcategoria, CASE WHEN tipo='INGRESO' THEN 'INCOME' ELSE 'EXPENSE' END AS kind FROM invoices"
            db.query(query).use { cursor ->
                while (cursor.moveToNext()) {
                    val category: String = cursor.getString(1)?.trim().orEmpty()
                    if (category.isBlank()) continue
                    val kind: String = cursor.getString(3)
                    val id: String = createCategory(db, kind, category)
                    val subcategory: String = cursor.getString(2)?.trim().orEmpty()
                    val child: String? = subcategory.takeIf(String::isNotBlank)?.let { createCategory(db, kind, it, id) }
                    db.execSQL("UPDATE $table SET categoryId=?, subcategoryId=? WHERE id=?", arrayOf<Any?>(id, child, cursor.getLong(0)))
                }
            }
        }
    }
}

private fun createCategory(db: SupportSQLiteDatabase, kind: String, name: String, parent: String = ""): String {
    val key: String = AutomationCodec.key(name)
    val id: String = UUID.nameUUIDFromBytes("finai-category:$kind:$parent:$key".toByteArray(Charsets.UTF_8)).toString()
    db.execSQL("INSERT OR IGNORE INTO categories(id,kind,name,parentId,archived,nameKey) VALUES(?,?,?,?,0,?)", arrayOf(id, kind, name, parent, key))
    return id
}

val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS monthly_limits (id TEXT NOT NULL PRIMARY KEY, categoryId TEXT NOT NULL, month TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_monthly_limits_categoryId_month ON monthly_limits(categoryId,month)")
    }
}
