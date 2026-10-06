package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.BankCsv
import com.gastos.domain.model.BankProgress
import com.gastos.domain.model.BankResolution
import com.gastos.local.database.AppDatabase
import com.gastos.storage.BankImportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic local-only measurements. No device-dependent time/memory pass threshold. */
@RunWith(AndroidJUnit4::class)
class BankPerformanceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importOneThousandRows(): Unit = runBlocking { benchmark(1_000) }
    @Test fun importTenThousandRows(): Unit = runBlocking { benchmark(10_000) }

    private suspend fun benchmark(rowCount: Int): Unit = withContext(Dispatchers.IO) {
        assertNotEquals("Bank parsing and registration must stay off the UI thread", Looper.getMainLooper(), Looper.myLooper())
        val suffix = "qa-bank-performance-${UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = "$suffix-$name"
                synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
                return super.getSharedPreferences(isolatedName, mode)
            }
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, suffix).build()
        val measurements = JSONObject().put("rows", rowCount).put("synthetic", true)
        val started = SystemClock.elapsedRealtimeNanos()
        var heapPeak = usedHeap()
        var nativePeak = Debug.getNativeHeapAllocatedSize()
        var succeeded = false
        fun sampleMemory() {
            heapPeak = maxOf(heapPeak, usedHeap())
            nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
        }
        suspend fun <T> measure(name: String, action: suspend () -> T): T {
            val phaseStart = SystemClock.elapsedRealtimeNanos()
            return action().also {
                measurements.put(name, (SystemClock.elapsedRealtimeNanos() - phaseStart) / 1_000_000.0)
                sampleMemory()
            }
        }
        measurements.put("heapBeforeBytes", usedHeap())
        try {
            val store = BankImportStore(isolated, database)
            val account = store.account("Synthetic benchmark", "EUR")
            assertTrue(database.automationDao().records("RULE").isEmpty())
            val bytes = buildString {
                append("Fecha;Concepto;Importe;ID\n")
                repeat(rowCount) { index -> append("05/10/2026;Synthetic movement ${index + 1};-${index + 1};SYNTHETIC-${index + 1}\n") }
            }.toByteArray()
            measurements.put("csvBytes", bytes.size)
            val table = measure("parseMs") { BankCsv.read(bytes) }
            assertEquals(rowCount, table.rows.size)
            val mapping = BankCsv.detect(table.headers).copy(transactionId = 3)
            val batch = measure("stageMs") { store.stage(table, account, mapping, "synthetic-performance.csv", false) }
            var callbacks = 0
            var lastProgress: BankProgress? = null
            val created = measure("createMs") {
                store.createSafeNew(batch) { progress ->
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    assertEquals(rowCount, progress.total)
                    assertTrue(progress.completed > (lastProgress?.completed ?: 0))
                    assertTrue(progress.completed <= rowCount)
                    assertEquals(progress.completed, progress.created)
                    assertEquals(0, progress.linked)
                    assertEquals(progress.created.toLong(), scalar(database, "SELECT COUNT(*) FROM invoices"))
                    callbacks++
                    lastProgress = progress
                    sampleMemory()
                }
            }
            assertEquals(rowCount, created)
            assertTrue(callbacks in 1..rowCount)
            assertEquals(BankProgress(rowCount, rowCount, created = rowCount), lastProgress)
            measurements.put("progressCallbacks", callbacks)
            val identityBefore = measure("verifyMs") {
                assertEquals(rowCount, database.invoiceDao().getInvoiceCount())
                assertEquals(0, database.incomeDao().getIncomeCount())
                assertEquals(rowCount, store.transactions().count { it.resolution == BankResolution.CREATED })
                assertEquals(rowCount, database.automationDao().records("SYNC").size)
                assertEquals(0L, scalar(database, "SELECT COUNT(*) FROM invoices WHERE ivaPercent IS NOT NULL"))
                assertEquals(0L, scalar(database, "SELECT COUNT(*) FROM products"))
                assertEquals(rowCount.toLong(), scalar(database, "SELECT COUNT(DISTINCT documentUuid) FROM invoices"))
                invoiceIdentityDigest(database)
            }
            measure("reimportMs") {
                assertEquals(batch, store.stage(table, account, mapping, "synthetic-performance.csv", false))
                assertEquals(0, store.createSafeNew(batch))
                assertEquals(rowCount, database.invoiceDao().getInvoiceCount())
                assertArrayEquals(identityBefore, invoiceIdentityDigest(database))
            }
            succeeded = true
        } finally {
            sampleMemory()
            measurements.put("success", succeeded)
                .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                .put("heapAfterBytes", usedHeap())
                .put("observedPeakHeapBytes", heapPeak)
                .put("observedPeakNativeBytes", nativePeak)
            // Metrics only: no CSV cells, merchant, UUID, account, API key or financial content.
            val report = measurements.toString()
            Log.i("FinAIBankPerformance", report)
            try {
                File(context.cacheDir, "qa-bank-performance-$rowCount.json").writeText(report)
            } finally {
                database.close()
                context.deleteDatabase(suffix)
                synchronized(preferenceNames) { preferenceNames.toList() }.forEach(context::deleteSharedPreferences)
            }
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun scalar(database: AppDatabase, query: String): Long = database.openHelper.readableDatabase.query(query).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
    private fun invoiceIdentityDigest(database: AppDatabase): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        database.openHelper.readableDatabase.query("SELECT documentUuid FROM invoices ORDER BY documentUuid").use { cursor ->
            while (cursor.moveToNext()) {
                digest.update(cursor.getString(0).toByteArray())
                digest.update(0.toByte())
            }
        }
        return digest.digest()
    }
}
