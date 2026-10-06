package com.gastos.di

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gastos.data.local.entity.IncomeEntity
import com.gastos.data.local.entity.InvoiceEntity
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.IncomeRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated in-memory DB; never modifies the installed user's records. */
@RunWith(AndroidJUnit4::class)
class MovementListProjectionTest {
    @Test fun lightweightExpenseIndexSearchesTenThousandRowsWithoutDecodingDocumentPayloads() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            db.withTransaction {
                repeat(10_000) { index -> db.invoiceDao().insertInvoice(InvoiceEntity(
                    documentUuid = "expense-$index", fecha = index.toLong(), proveedor = "Café $index", tipo = InvoiceType.GASTO,
                    total = 2.5, moneda = "EUR", numeroFactura = "FAC-$index", notas = "Viaje de trabajo",
                    taxesJson = "not needed by lists", evidenceJson = "not needed by lists", ocrRawText = "x".repeat(1024)
                )) }
            }
            val started = System.nanoTime()
            val rows = db.invoiceDao().observeListEntries(InvoiceType.GASTO).first().map { it.toListEntry(false) }
            val found = rows.filter(MovementListFilter(query = "cafe trabajo").let { it::includes })
            android.util.Log.i("MovementListQA", "rows=${rows.size} indexAndFilterMs=${(System.nanoTime() - started) / 1_000_000}")
            assertEquals(10_000, found.size)
            assertEquals(50, found.take(MovementListFilter.PAGE_SIZE).size)
            assertEquals(25_000.0, found.sumOf { it.amount }, 0.001)
            assertEquals("expense-9999", found.first().documentUuid)
            assertEquals(1, rows.count { it.matchesSearch("FAC-9999") })
        } finally { db.close() }
    }

    @Test fun incomeProjectionPreservesNumberLegacyIdentityAndCountsSharedUuidOnlyOnce() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            db.invoiceDao().insertInvoice(InvoiceEntity(documentUuid = "shared", fecha = 1, proveedor = "Old", tipo = InvoiceType.INGRESO, total = 5.0))
            val legacyId = db.invoiceDao().insertInvoice(InvoiceEntity(documentUuid = "legacy", fecha = 2, proveedor = "Employer", tipo = InvoiceType.INGRESO,
                total = 900.0, numeroFactura = "PAY-2"))
            db.incomeDao().insertIncomeEntity(IncomeEntity(documentUuid = "shared", fecha = 3, concepto = "Native", monto = 5.0,
                evidenceJson = """{"document":{"number":"SALE-9","lines":[]}}"""))
            val rows = IncomeRepositoryImpl(db.incomeDao(), db).observeListEntries().first()
            assertEquals(2, rows.size)
            assertEquals(1, rows.count { it.matchesSearch("sale-9") })
            val legacy = rows.single { it.documentUuid == "legacy" }
            assertEquals(-legacyId, legacy.id)
            assertEquals(MovementSource.INVOICE, legacy.source)
            assertEquals(905.0, rows.sumOf { it.amount }, 0.001)
        } finally { db.close() }
    }
}
