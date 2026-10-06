package com.gastos.feature.backup

import android.content.Context
import com.gastos.domain.model.*
import com.gastos.repository.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FinancialReportScaleTest {
    private fun writer(root: File, count: Int): FinancialReportWriter {
        val context = mockk<Context>(relaxed=true) {
            every { cacheDir } returns root
            every { filesDir } returns root
            every { getString(any()) } answers { "label-${firstArg<Int>()}" }
        }
        val data = BackupDataset((1..count).map {
            Invoice(id=it.toLong(),fecha=1,proveedor="Synthetic $it",tipo=InvoiceType.GASTO,total=it.toDouble(),ivaPercent=null)
        },emptyList(),emptyList(),emptyList(),emptyList())
        return FinancialReportWriter(context,mockk { coEvery { financialSnapshot() } returns data },
            mockk { every { defaultCurrency } returns MutableStateFlow("EUR") },
            mockk { every { rates } returns MutableStateFlow(mapOf("EUR" to 1.0)); every { lastUpdated } returns MutableStateFlow(1L) })
    }

    @Test fun `CSV streams one thousand and ten thousand original movements`() = runTest {
        for (count in listOf(1_000,10_000)) {
            val root = Files.createTempDirectory("finai-report-scale").toFile()
            try {
                val writer = writer(root,count)
                val start = System.nanoTime()
                var lastProgress = 0f
                val file = writer.generate(ReportFormat.CSV) { assertTrue(it >= lastProgress); lastProgress = it }
                assertEquals(count,file.useLines { lines -> lines.count { it.contains("Synthetic ") } })
                assertEquals(1f,lastProgress)
                println("CSV synthetic: documents=$count bytes=${file.length()} elapsedMs=${(System.nanoTime()-start)/1_000_000}")
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun `cancelling CSV generation removes its partial output and permits another report`() = runTest {
        val root = Files.createTempDirectory("finai-report-cancel").toFile()
        try {
            val writer = writer(root,1_000)
            val failure = runCatching { writer.generate(ReportFormat.CSV) {
                if(it > 0.2f) throw CancellationException("synthetic cancellation")
            } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(File(root,"report_exports").listFiles().orEmpty().isEmpty())
            assertTrue(writer.generate(ReportFormat.CSV) {}.isFile)
        } finally { root.deleteRecursively() }
    }
}
