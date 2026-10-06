package com.gastos.di

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.ExchangeRateProvider
import com.gastos.storage.CategoryCatalog
import com.gastos.storage.MonthlyLimitStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All writes are confined to an in-memory database; no account or user ledger is touched. */
@RunWith(AndroidJUnit4::class)
class OrganizationStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rates = object : ExchangeRateProvider {
        override val rates = MutableStateFlow<Map<String, Double>>(emptyMap())
        override val lastUpdated = MutableStateFlow<Long?>(null)
        override suspend fun refresh() = Unit
        override fun convert(amount: Double, from: String, to: String): Double? = if (from == to) amount else null
    }

    @Test fun deletedOrMovedRepeatingInstanceIsNotRegeneratedAndCanBeExplicitlyRecreated(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val parent = CategoryCatalog(db).createUnique(DocumentKind.EXPENSE, "Forza")
            val store = MonthlyLimitStore(db, rates)
            store.create(MonthlyLimit("", parent.id, "2026-09", 100.0, "EUR", repeat = true))
            val october = store.progress("2026-10").first().single().limit
            store.delete(october.id)
            assertTrue(store.progress("2026-10").first().isEmpty())
            store.create(october.copy(amount = 120.0))
            assertEquals(120.0, store.progress("2026-10").first().single().limit.amount, 0.0)
            store.update(october.id, october.copy(month = "2026-11"))
            assertTrue(store.progress("2026-10").first().isEmpty())
            assertEquals(100.0, store.progress("2026-11").first().single().limit.amount, 0.0)
        } finally { db.close() }
    }

    @Test fun duplicateNamesRemainScopedToKindAndParentAndArchivedRowsAreRecoverable(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val expense = catalog.createUnique(DocumentKind.EXPENSE, "Forza")
            val income = catalog.createUnique(DocumentKind.INCOME, "Forza")
            val child = catalog.createUnique(DocumentKind.EXPENSE, "Gasolina", expense.id)
            assertNotEquals(expense.id, income.id)
            assertEquals(expense.id, child.parentId)
            assertEquals("CATEGORY_ALREADY_EXISTS", runCatching { catalog.createUnique(DocumentKind.EXPENSE, "  FORZA  ") }.exceptionOrNull()?.message)
            catalog.archive(expense.id, true)
            assertEquals("CATEGORY_ALREADY_EXISTS", runCatching { catalog.createUnique(DocumentKind.EXPENSE, "Forza") }.exceptionOrNull()?.message)
            catalog.archive(expense.id, false)
            assertFalse(catalog.list().first { it.id == child.id }.archived)
            assertEquals(3, catalog.list().size)
        } finally { db.close() }
    }

    @Test fun editingDisabledRuleRetainsItsIdentityStateAndSingleRecord(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val parent = catalog.createUnique(DocumentKind.EXPENSE, "Forza")
            val child = catalog.createUnique(DocumentKind.EXPENSE, "Gasolina", parent.id)
            val rule = CategoryRule("existing-rule", DocumentKind.EXPENSE, "Old merchant", parent.id, enabled = false)
            catalog.putRule(rule)
            catalog.updateRule(rule.copy(merchant = "New merchant", subcategoryId = child.id, match = RuleMatch.CONTAINS, priority = 3))
            val row = db.automationDao().records("RULE").single()
            val edited: CategoryRule = AutomationCodec.json.decodeFromString(row.payload)
            assertEquals(rule.id, edited.id)
            assertFalse(edited.enabled)
            assertEquals(child.id, edited.subcategoryId)
            catalog.deleteRule(rule.id)
            assertEquals("RECORD_CHANGED", runCatching { catalog.updateRule(edited) }.exceptionOrNull()?.message)
            assertTrue(db.automationDao().records("RULE").isEmpty())
        } finally { db.close() }
    }

    @Test fun movingLimitIsAtomicRejectsOccupiedDestinationAndNeverOverwritesIt(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val source = catalog.createUnique(DocumentKind.EXPENSE, "Forza")
            val target = catalog.createUnique(DocumentKind.EXPENSE, "Food")
            val store = MonthlyLimitStore(db, rates)
            val first = MonthlyLimit("", source.id, "2026-09", 100.0, "EUR", repeat = true, notify = true)
            val occupied = first.copy(categoryId = target.id, month = "2026-10", amount = 200.0)
            store.create(first); store.create(occupied)
            val before = db.automationDao().limits().sortedBy { it.id }
            val originalId = "${source.id}:2026-09"
            assertEquals("LIMIT_ALREADY_EXISTS", runCatching { store.update(originalId, occupied.copy(amount = 999.0)) }.exceptionOrNull()?.message)
            assertEquals(before, db.automationDao().limits().sortedBy { it.id })
            assertEquals("LIMIT_ALREADY_EXISTS", runCatching { store.create(first) }.exceptionOrNull()?.message)
            store.update(originalId, first.copy(categoryId = target.id, month = "2026-11", amount = 150.0, repeat = false))
            val after = db.automationDao().limits()
            assertEquals(2, after.size)
            assertFalse(after.any { it.id == originalId })
            val moved: MonthlyLimit = AutomationCodec.json.decodeFromString(after.first { it.month == "2026-11" }.payload)
            assertEquals(target.id, moved.categoryId)
            assertEquals(150.0, moved.amount, 0.0)
            assertTrue(moved.notify); assertFalse(moved.repeat)
            val stable = after.sortedBy { it.id }
            assertTrue(runCatching { store.update(moved.id, moved.copy(amount = Double.NaN)) }.isFailure)
            assertEquals(stable, db.automationDao().limits().sortedBy { it.id })
        } finally { db.close() }
    }
}
