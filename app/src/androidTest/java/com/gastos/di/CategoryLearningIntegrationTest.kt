package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.storage.CategoryCatalog
import com.gastos.storage.WalletPaymentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Categorías de fábrica y aprendizaje desde el historial — BD en memoria, preferencias aisladas. */
@RunWith(AndroidJUnit4::class)
class CategoryLearningIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(suffix: String): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-catlearn-$suffix-$name", mode)
    }

    @Test fun seededRulesCategorizeKnownMerchantsAndInitializeIsIdempotent(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            catalog.initialize()
            val seeded = catalog.rules.first()
            assertTrue(seeded.isNotEmpty())
            assertTrue(seeded.any { AutomationCodec.key(it.merchant) == "mercadona" })
            catalog.initialize()
            assertEquals(seeded.size, catalog.rules.first().size)

            val market = catalog.assign(Invoice(fecha = 1, proveedor = "MERCADONA, S.A.", tipo = InvoiceType.GASTO, total = 25.0), useRules = true)
            assertEquals("Alimentación", market.categoria)
            assertEquals("Supermercado", market.subcategoria)

            val delivery = catalog.assign(Invoice(fecha = 2, proveedor = "UBER EATS ES", tipo = InvoiceType.GASTO, total = 18.5), useRules = true)
            assertEquals("Alimentación", delivery.categoria)
            assertEquals("Restaurantes", delivery.subcategoria)
        } finally { db.close() }
    }

    @Test fun historyReusesTheLatestCategoryForTheSameMerchantAndSkipsArchived(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            catalog.initialize()
            val parent = catalog.create(DocumentKind.EXPENSE, "Snacks")
            val child = catalog.create(DocumentKind.EXPENSE, "Máquinas", parent.id)
            db.invoiceDao().insertInvoice(Invoice(documentUuid = "learn-1", fecha = 10, proveedor = "Frutería Pepa", tipo = InvoiceType.GASTO, total = 12.0,
                categoria = parent.name, subcategoria = child.name, categoryId = parent.id, subcategoryId = child.id).toEntity())

            val assigned = catalog.assign(Invoice(documentUuid = "learn-2", fecha = 20, proveedor = "  frutería   PEPA ", tipo = InvoiceType.GASTO, total = 9.0), useRules = true)
            assertEquals(parent.id, assigned.categoryId)
            assertEquals(child.id, assigned.subcategoryId)

            catalog.archive(parent.id, true)
            val ignored = catalog.assign(Invoice(documentUuid = "learn-3", fecha = 21, proveedor = "Frutería Pepa", tipo = InvoiceType.GASTO, total = 5.0), useRules = true)
            assertNull(ignored.categoryId)
        } finally { db.close() }
    }

    @Test fun walletCaptureInheritsCategoriesFromMerchantHistory(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            catalog.initialize()
            val parent = catalog.create(DocumentKind.EXPENSE, "Snacks")
            val child = catalog.create(DocumentKind.EXPENSE, "Máquinas", parent.id)
            db.invoiceDao().insertInvoice(Invoice(documentUuid = "learn-w1", fecha = 10, proveedor = "Frutería Pepa", tipo = InvoiceType.GASTO, total = 12.0,
                categoria = parent.name, subcategoria = child.name, categoryId = parent.id, subcategoryId = child.id).toEntity())

            val store = WalletPaymentStore(isolated("categories"), db)
            store.setEnabled(true)
            assertTrue(store.capture(WalletPayment("qa-event", "Frutería Pepa", 7.5, "EUR", 1_791_158_400_000)))

            val captured = db.invoiceDao().documentRecords().map { it.toDomain() }.first { it.total == 7.5 }
            assertEquals(parent.id, captured.categoryId)
            assertEquals(child.id, captured.subcategoryId)
        } finally { db.close() }
    }
}
