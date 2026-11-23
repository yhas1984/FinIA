package com.gastos.feature.invoices

import com.gastos.domain.model.DocumentEvidence
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.ScannedDocument
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La línea fiscal del detalle debe mostrar lo que el guardado conserva, sin desplegables. */
class InvoiceFiscalLineTest {

    private fun invoice(
        base: Double? = null,
        quota: Double? = null,
        irpf: Double = 0.0,
        withholding: Double? = null,
        taxes: List<DocumentTax> = emptyList()
    ) = Invoice(
        fecha = 1L,
        proveedor = "Proveedor",
        tipo = InvoiceType.GASTO,
        total = 121.0,
        baseImponible = base,
        cuotaIva = quota,
        ivaPercent = 21.0,
        irpfPercent = irpf,
        evidence = withholding?.let { DocumentEvidence(ScannedDocument(withholdingAmount = it)) },
        taxes = taxes
    )

    @Test
    fun `base, IVA y retencion salen en la misma linea`() {
        val line = requireNotNull(invoiceFiscalLine(invoice(base = 100.0, quota = 21.0, irpf = 15.0, withholding = 15.0)))
        assertEquals(100.0, line.base!!, 0.001)
        assertEquals(21.0, line.vat!!, 0.001)
        assertEquals(15.0, line.withheld!!, 0.001)
        assertNull(line.nonTaxable)
    }

    @Test
    fun `exento sustituye a la cuota de IVA`() {
        val line = requireNotNull(invoiceFiscalLine(invoice(
            base = 100.0,
            quota = 0.0,
            taxes = listOf(DocumentTax(name = "IVA", base = 100.0, amount = 0.0, treatment = TaxTreatment.EXEMPT))
        )))
        assertEquals(100.0, line.base!!, 0.001)
        assertNull(line.vat)
        assertEquals(TaxTreatment.EXEMPT, line.nonTaxable)
    }

    @Test
    fun `no sujeto se conserva como tratamiento`() {
        val line = requireNotNull(invoiceFiscalLine(invoice(
            base = 100.0,
            taxes = listOf(DocumentTax(name = "IVA", base = 100.0, amount = 0.0, treatment = TaxTreatment.OUT_OF_SCOPE))
        )))
        assertEquals(TaxTreatment.OUT_OF_SCOPE, line.nonTaxable)
    }

    @Test
    fun `la retencion de una fila se usa si no hay importe leido`() {
        val line = requireNotNull(invoiceFiscalLine(invoice(
            base = 100.0,
            quota = 21.0,
            irpf = 15.0,
            taxes = listOf(DocumentTax(name = "IRPF", amount = 15.0, effect = TaxEffect.WITHHOLDING))
        )))
        assertEquals(15.0, line.withheld!!, 0.001)
    }

    @Test
    fun `el importe leido y la fila no se suman dos veces`() {
        val line = requireNotNull(invoiceFiscalLine(invoice(
            base = 100.0,
            quota = 21.0,
            irpf = 15.0,
            withholding = 15.0,
            taxes = listOf(DocumentTax(name = "IRPF", amount = 15.0, effect = TaxEffect.WITHHOLDING))
        )))
        assertEquals(15.0, line.withheld!!, 0.001)
    }

    @Test
    fun `sin datos fiscales no se inventa una linea`() {
        assertNull(invoiceFiscalLine(invoice()))
    }
}
