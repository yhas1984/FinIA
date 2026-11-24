package com.gastos.feature.backup

import android.content.Context
import com.gastos.domain.model.*
import com.gastos.repository.BackupDataRepository
import com.gastos.repository.BackupDataset
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class FinancialReportWriterLedgerTest {

    private val november = LocalDate.of(2026, 11, 2)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun invoice(
        id: Long,
        total: Double,
        base: Double? = null,
        quota: Double? = null,
        rate: Double? = 21.0,
        taxes: List<DocumentTax> = emptyList(),
        type: InvoiceType = InvoiceType.GASTO
    ) = Invoice(
        id = id,
        fecha = november,
        proveedor = "Proveedor $id",
        tipo = type,
        total = total,
        numeroFactura = "F-$id",
        baseImponible = base,
        cuotaIva = quota,
        ivaPercent = rate,
        taxes = taxes
    )

    private fun tax(rate: Double, base: Double, amount: Double) = DocumentTax(
        name = "IVA",
        rate = rate,
        base = base,
        amount = amount,
        treatment = TaxTreatment.TAXABLE,
        effect = TaxEffect.CHARGE
    )

    private fun writer(root: File, invoices: List<Invoice>): FinancialReportWriter {
        val context = mockk<Context>(relaxed = true) {
            every { cacheDir } returns root
            every { filesDir } returns root
            every { getString(any()) } answers {
                if (firstArg<Int>() == R.string.csv_header_supplier) "Proveedor" else "etiqueta"
            }
        }
        return FinancialReportWriter(
            context = context,
            repository = mockk<BackupDataRepository> {
                coEvery { financialSnapshot() } returns BackupDataset(
                    invoices = invoices,
                    products = emptyList(),
                    incomes = emptyList(),
                    fiscalConfigs = emptyList(),
                    chatMessages = emptyList()
                )
            },
            currencyPreference = mockk<CurrencyPreference> {
                every { defaultCurrency } returns MutableStateFlow("EUR")
            },
            exchangeRateProvider = mockk<ExchangeRateProvider> {
                every { rates } returns MutableStateFlow(mapOf("EUR" to 1.0))
                every { lastUpdated } returns MutableStateFlow(1L)
            }
        )
    }

    @Test
    fun `libro parte por tipo de IVA, calcula solo con total coherente y excluye lo que no cuadra`() = runTest {
        val root = Files.createTempDirectory("finai-ledger").toFile()
        try {
            val mixed = invoice(
                id = 145, total = 202.0, base = 175.0, quota = 27.0, rate = null,
                taxes = listOf(tax(21.0, 100.0, 21.0), tax(10.0, 50.0, 5.0), tax(4.0, 25.0, 1.0))
            )
            val calculated = invoice(id = 200, total = 121.0)
            val withoutData = invoice(id = 201, total = 50.0, rate = null)
            val incoherent = invoice(id = 202, total = 50.0, base = 100.0, quota = 21.0)
            val documented = invoice(id = 203, total = 121.0, base = 100.0, quota = 21.0)
            val income = invoice(id = 204, total = 500.0, type = InvoiceType.INGRESO)

            val filter = ReportFilter(
                startInclusive = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                endExclusive = LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
            val result = writer(root, listOf(mixed, calculated, withoutData, incoherent, documented, income))
                .generateLedger(filter) {}

            val lines = result.file.readText().removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
            assertEquals("FinAI_libro-recibidas_2026-4T.csv", result.file.name)
            assertEquals(20, lines.first().split(';').size)
            assertEquals("\"Proveedor\"", lines.first().split(';')[4])
            assertTrue(lines.first().endsWith("\"dato_fiscal\""))
            assertTrue(lines.all { it.split(';').size == 20 })

            // 3 de la mixta + 1 calculada + 1 documentada; ni producto, ni ingreso, ni resumen.
            assertEquals(6, lines.size) // cabecera + 5 filas
            assertEquals(3, lines.count { it.contains("145") })
            assertEquals(1, lines.count { it.contains("200") })
            assertEquals(1, lines.count { it.contains("203") })
            assertTrue(lines.none { it.contains("204") })
            assertTrue(lines.none { it.contains("Filtros") })
            assertTrue(lines.none { it.contains("Total Gastos") })

            val calculatedLine = lines.single { it.contains("200") }
            assertTrue(calculatedLine.endsWith("\"calculado\""))
            assertTrue(calculatedLine.contains("\"100,00\""))
            assertTrue(calculatedLine.contains("\"21,00\""))

            val mixedLines = lines.filter { it.contains("145") }
            assertTrue(mixedLines.all { it.endsWith("\"documento\"") })
            assertTrue(mixedLines.any { it.contains("\"21,00\"") })
            assertTrue(mixedLines.any { it.contains("\"10,00\"") })
            assertTrue(mixedLines.any { it.contains("\"4,00\"") })

            assertEquals(3, result.stats.includedInvoices)
            assertEquals(2, result.stats.excludedInvoices)
            assertEquals(listOf(201L, 202L), result.stats.excludedIds)
            assertEquals(4, result.stats.documentRows)
            assertEquals(1, result.stats.calculatedRows)
        } finally {
            root.deleteRecursively()
        }
    }
}
