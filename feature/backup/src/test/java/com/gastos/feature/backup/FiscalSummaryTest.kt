package com.gastos.feature.backup

import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxPeriodType
import com.gastos.domain.model.TaxTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Calendar
import java.util.TimeZone

class FiscalSummaryTest {

    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    private fun date(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(madrid).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun tax(rate: Double, base: Double, amount: Double, treatment: TaxTreatment = TaxTreatment.TAXABLE) =
        DocumentTax(name = "IVA", rate = rate, base = base, amount = amount, treatment = treatment, effect = TaxEffect.CHARGE)

    private fun expense(
        fecha: Long,
        base: Double?,
        cuota: Double?,
        rate: Double = 21.0,
        currency: String = "EUR",
        taxes: List<DocumentTax> = emptyList()
    ) = Invoice(
        fecha = fecha,
        proveedor = "Proveedor",
        tipo = InvoiceType.GASTO,
        total = (base ?: 0.0) + (cuota ?: 0.0),
        baseImponible = base,
        cuotaIva = cuota,
        ivaPercent = rate,
        moneda = currency,
        taxes = taxes
    )

    private fun income(fecha: Long, importe: Double, taxes: List<DocumentTax>, currency: String = "EUR") =
        Income(fecha = fecha, concepto = "Ingreso", monto = importe, moneda = currency, taxes = taxes)

    // Misma configuración que la app real: reglas de España verificadas en la sede de la AEAT.
    private val esConfig = CountryFiscalConfig(
        paisCodigo = "ES", nombrePais = "España", ivaRates = listOf(0.0, 4.0, 10.0, 21.0),
        irpfRate = 15.0, nombreLeyFiscal = "IVA",
        tipoPeriodo = TaxPeriodType.QUARTERLY,
        nombreDeclaracion = "modelo 303",
        plazos = mapOf(
            "1T" to "1-20 abril", "2T" to "1-20 julio", "3T" to "1-20 octubre", "4T" to "1-30 enero"
        ),
        tiposRetencion = listOf(7.0)
    )

    // ---- Periodo ----

    @Test
    fun `trimestre uno empieza el 1 de enero y termina el 1 de abril en Madrid`() {
        val q1 = VatPeriodCalculator.quarter(2026, 1, madrid)
        assertEquals(date(2026, 1, 1, 0), q1.startInclusive)
        assertEquals(date(2026, 4, 1, 0), q1.endExclusive)
        assertEquals("1T 2026", q1.label())
    }

    @Test
    fun `cuarto trimestre termina el 1 de enero del año siguiente`() {
        val q4 = VatPeriodCalculator.quarter(2026, 4, madrid)
        assertEquals(date(2026, 10, 1, 0), q4.startInclusive)
        assertEquals(date(2027, 1, 1, 0), q4.endExclusive)
    }

    @Test
    fun `el plazo sale de las reglas del pais, no del periodo`() {
        val q1 = VatPeriodCalculator.quarter(2026, 1, madrid)
        val q4 = VatPeriodCalculator.quarter(2026, 4, madrid)
        // España los tiene verificados en la AEAT.
        assertEquals("1-20 abril", q1.deadlineHint(esConfig))
        assertEquals("1-30 enero", q4.deadlineHint(esConfig))
        // Un país sin reglas verificadas devuelve la regla general, sin inventar fechas.
        val sinReglas = CountryFiscalConfig(paisCodigo = "MX", nombrePais = "México")
        assertTrue(q1.deadlineHint(sinReglas)!!.contains("trimestre"))
        assertNull(VatPeriodCalculator.yearOf(2026, madrid).deadlineHint(sinReglas))
    }

    @Test
    fun `un pais mensual usa periodo mensual y su informe no nombra el 303`() {
        val mexico = CountryFiscalConfig(
            paisCodigo = "MX", nombrePais = "México",
            tipoPeriodo = TaxPeriodType.MONTHLY, nombreDeclaracion = null
        )
        assertEquals(VatPeriodType.MONTHLY, mexico.vatPeriodTypeOrNull())
        val period = VatPeriodCalculator.forCountry(mexico, 2026, 3, VatPeriodType.MONTHLY, madrid)
        assertEquals(VatPeriodType.MONTHLY, period.type)
        assertEquals("3/2026", period.label())
        assertNull(period.deadlineHint(mexico)?.takeIf { it.contains("abril") })
    }

    @Test
    fun `un pais sin reglas verificadas deja que el usuario elija el periodo`() {
        val sinReglas = CountryFiscalConfig(paisCodigo = "CO", nombrePais = "Colombia")
        assertNull(sinReglas.vatPeriodTypeOrNull())
        val trimestral = VatPeriodCalculator.forCountry(sinReglas, 2026, 5, VatPeriodType.QUARTERLY, madrid)
        assertEquals("2T 2026", trimestral.label())
    }

    @Test
    fun `estados unidos no ofrece resumen de iva`() {
        val eeuu = CountryFiscalConfig(paisCodigo = "US", nombrePais = "Estados Unidos", nombreLeyFiscal = "Sales Tax")
        assertFalse(eeuu.permiteResumenIva())
        assertTrue(esConfig.permiteResumenIva())
    }

    @Test
    fun `la fecha del 30 de junio cae en el segundo trimestre`() {
        val period = VatPeriodCalculator.quarterOf(date(2026, 6, 30), madrid)
        assertEquals(2, period.quarter)
    }

    @Test
    fun `la fecha del 1 de julio cae en el tercer trimestre`() {
        val period = VatPeriodCalculator.quarterOf(date(2026, 7, 1), madrid)
        assertEquals(3, period.quarter)
    }

    // ---- Cálculo ----

    @Test
    fun `separa iva repercutido por tipo y lo ordena de mayor a menor`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = emptyList(),
            incomes = listOf(
                income(date(2026, 1, 15), 121.0, listOf(tax(21.0, 100.0, 21.0))),
                income(date(2026, 2, 10), 110.0, listOf(tax(10.0, 100.0, 10.0))),
                income(date(2026, 3, 5), 104.0, listOf(tax(4.0, 100.0, 4.0)))
            ),
            fiscalConfig = esConfig
        )
        assertEquals(3, summary.output.lines.size)
        assertEquals(BigDecimal("21.00"), summary.output.lines[0].rate)
        assertEquals(BigDecimal("10.00"), summary.output.lines[1].rate)
        assertEquals(BigDecimal("4.00"), summary.output.lines[2].rate)
        assertEquals(BigDecimal("300.00"), summary.output.base)
        assertEquals(BigDecimal("35.00"), summary.output.quota)
    }

    @Test
    fun `resta el iva soportado del repercutido`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(expense(date(2026, 1, 20), 200.0, 42.0)),
            incomes = listOf(income(date(2026, 1, 15), 121.0, listOf(tax(21.0, 100.0, 21.0)))),
            fiscalConfig = esConfig
        )
        assertEquals(BigDecimal("21.00"), summary.output.quota)
        assertEquals(BigDecimal("42.00"), summary.input.quota)
        assertEquals(BigDecimal("-21.00"), summary.result)
        assertFalse(summary.isPayable)
    }

    @Test
    fun `una factura con dos tipos reparte base y cuota en dos lineas`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(
                Invoice(
                    fecha = date(2026, 1, 10), proveedor = "Ferretería", tipo = InvoiceType.GASTO,
                    total = 149.31, baseImponible = 123.40, cuotaIva = 25.91, ivaPercent = 21.0,
                    taxes = listOf(tax(21.0, 123.40, 25.91), tax(4.0, 40.0, 1.60))
                )
            ),
            incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(2, summary.input.lines.size)
        assertEquals(BigDecimal("25.91").add(BigDecimal("1.60")), summary.input.quota)
        assertEquals(BigDecimal("163.40"), summary.input.base)
        // Una sola factura con dos tipos: cada línea cuenta un documento, y el bloque también uno.
        assertEquals(1, summary.input.lines[0].documents)
        assertEquals(1, summary.input.lines[1].documents)
        assertEquals(1, summary.input.documents)
    }

    @Test
    fun `el tipo se deduce de base y cuota y no del 21 por defecto`() {
        // Factura guardada sin desglose pero con base y cuota al 10 %: el ivaPercent trae 21 por defecto.
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(expense(date(2026, 1, 10), 100.0, 10.0, rate = 21.0)),
            incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(BigDecimal("10.00"), summary.input.lines.single().rate)
        assertEquals(BigDecimal("10.00"), summary.input.quota)
    }

    @Test
    fun `retencion de irpf no se cuenta como iva`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = emptyList(),
            incomes = listOf(
                income(
                    date(2026, 1, 15), 1050.0,
                    listOf(
                        tax(21.0, 1000.0, 210.0),
                        DocumentTax(name = "IRPF", rate = 15.0, base = 1000.0, amount = 150.0,
                            treatment = TaxTreatment.TAXABLE, effect = TaxEffect.WITHHOLDING)
                    )
                )
            ),
            fiscalConfig = esConfig
        )
        assertEquals(BigDecimal("210.00"), summary.output.quota)
        assertEquals(BigDecimal("150.00"), summary.irpfWithheld)
        assertEquals(BigDecimal("210.00"), summary.result)
    }

    @Test
    fun `documento exento no suma ni como base ni como cuota`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = emptyList(),
            incomes = listOf(
                income(date(2026, 1, 15), 500.0, listOf(tax(0.0, 500.0, 0.0, TaxTreatment.EXEMPT)))
            ),
            fiscalConfig = esConfig
        )
        assertTrue(summary.output.lines.isEmpty())
        assertEquals(BigDecimal.ZERO.setScale(2), summary.output.quota)
        assertEquals(1, summary.excluded[VatExclusion.EXEMPT])
    }

    @Test
    fun `el cero por ciento si cuenta como linea propia`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = emptyList(),
            incomes = listOf(
                income(date(2026, 1, 15), 500.0, listOf(tax(0.0, 500.0, 0.0, TaxTreatment.TAXABLE)))
            ),
            fiscalConfig = esConfig
        )
        assertEquals(1, summary.output.lines.size)
        assertEquals(BigDecimal("500.00"), summary.output.base)
        assertEquals(BigDecimal.ZERO.setScale(2), summary.output.quota)
        assertTrue(summary.excluded.isEmpty())
    }

    @Test
    fun `documento de otra moneda se excluye y se informa`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(expense(date(2026, 1, 10), 100.0, 21.0, currency = "USD")),
            incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(0, summary.input.documents)
        assertEquals(1, summary.excluded[VatExclusion.FOREIGN_CURRENCY])
    }

    @Test
    fun `documento sin base ni cuota se excluye y no se inventa`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(expense(date(2026, 1, 10), null, null)),
            incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(0, summary.input.documents)
        assertEquals(1, summary.excluded[VatExclusion.MISSING_TAX_DATA])
    }

    @Test
    fun `documento fuera del trimestre no entra`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR",
            invoices = listOf(
                expense(date(2026, 1, 10), 100.0, 21.0),
                expense(date(2026, 4, 1), 100.0, 21.0),
                expense(date(2025, 12, 31), 100.0, 21.0)
            ),
            incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(1, summary.input.documents)
        assertEquals(BigDecimal("21.00"), summary.input.quota)
    }

    @Test
    fun `las facturas emitidas cuentan como repercutido`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val emitida = Invoice(
            fecha = date(2026, 2, 1), proveedor = "Mi negocio", tipo = InvoiceType.INGRESO,
            total = 121.0, baseImponible = 100.0, cuotaIva = 21.0, ivaPercent = 21.0
        )
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = listOf(emitida), incomes = emptyList(),
            fiscalConfig = esConfig
        )
        assertEquals(BigDecimal("21.00"), summary.output.quota)
        assertEquals(0, summary.input.documents)
    }

    @Test
    fun `el nombre del impuesto sale de la configuracion del pais`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val peru = CountryFiscalConfig(paisCodigo = "PE", nombrePais = "Perú", ivaRates = listOf(18.0), nombreLeyFiscal = "IGV")
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "PEN", invoices = emptyList(), incomes = emptyList(), fiscalConfig = peru
        )
        assertEquals("IGV", summary.taxName)
        assertEquals("PE", summary.countryCode)
    }

    @Test
    fun `un trimestre sin documentos da cero y no rompe`() {
        val period = VatPeriodCalculator.quarter(2026, 1, madrid)
        val summary = VatSummaryCalculator.summary(
            period = period, currency = "EUR", invoices = emptyList(), incomes = emptyList(), fiscalConfig = esConfig
        )
        assertEquals(BigDecimal.ZERO.setScale(2), summary.result)
        assertTrue(summary.output.lines.isEmpty())
    }
}
