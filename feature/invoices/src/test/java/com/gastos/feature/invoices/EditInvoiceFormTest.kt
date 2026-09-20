package com.gastos.feature.invoices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests unitarios de [EditInvoiceForm.recalcFiscal] — función pura, sin
 * dependencias de Android, para validar el desglose fiscal (base, IVA,
 * IRPF, neto) usado al guardar facturas.
 */
class EditInvoiceFormTest {
    @Test fun `mixed rates remain nullable while manual row edits recalculate the tax`() {
        val locale = java.util.Locale.forLanguageTag("es-ES")
        val rows = listOf(com.gastos.common.TaxFormRow("IVA", "21", "100", "21"),
            com.gastos.common.TaxFormRow("IVA", "10", "50", "5"), com.gastos.common.TaxFormRow("IVA", "4", "25", "1"))
        val form = EditInvoiceForm(total = "202", baseImponible = "175", ivaPercent = "", taxes = rows)
        val original = form.recalcFiscal(locale)!!
        assertNull(original.ivaPercent)
        assertEquals(27.0, original.ivaAmount!!, 0.0)
        assertEquals(175.0, original.baseImponible!!, 0.0)
        assertNull(form.copy(total = "220").recalcFiscal(locale))
        val edited = form.copy(total = "210", taxes = rows.mapIndexed { index, row -> if (index == 0) row.copy(rate = "29", amount = "") else row })
            .recalcFiscal(locale)!!
        assertEquals(35.0, edited.ivaAmount!!, 0.0)
        assertEquals("0", EditInvoiceForm().ivaPercent)
    }

    @Test fun `unknown scanned tax stays unknown during unrelated manual edits`() {
        val form = EditInvoiceForm(total = "25", ivaPercent = "", baseImponible = "", cuotaIva = "")
        val result = form.copy(proveedor = "Edited").recalcFiscal(java.util.Locale.ENGLISH)!!
        assertNull(result.ivaPercent)
        assertNull(result.ivaAmount)
        assertNull(result.baseImponible)
        assertEquals(25.0, result.total, 0.0)
    }

    @Test fun `OCR payable total does not deduct withholding again`() {
        val result = EditInvoiceForm(total = "106", baseImponible = "100", cuotaIva = "21", ivaPercent = "21", irpfPercent = "15",
            withheldAmountRead = 15.0, withheldRateRead = 15.0).recalcFiscal(java.util.Locale.ENGLISH)!!
        assertEquals(106.0, result.totalNeto, 0.0)
        assertEquals(100.0, result.baseImponible!!, 0.0)
        assertEquals(21.0, result.ivaAmount!!, 0.0)
    }

    private fun form(total: String, iva: String = "21.0", irpf: String = "0.0") =
        EditInvoiceForm(total = total, ivaPercent = iva, irpfPercent = irpf)

    @Test
    fun `total sin IVA fields con IVA 21 desglosa base y cuota correctamente`() {
        // total=121 con IVA 21% → base=100, iva=21, irpf=0, neto=121
        val r = form("121").recalcFiscal()!!
        assertEquals(100.0, r.baseImponible!!, 0.001)
        assertEquals(21.0, r.ivaAmount!!, 0.001)
        assertEquals(121.0, r.total, 0.001)
        assertEquals(0.0, r.irpfAmount, 0.001)
        assertEquals(121.0, r.totalNeto, 0.001)
    }

    @Test
    fun `total con IRPF 15 descuenta la retencion sobre la base`() {
        // total=121, iva=21%, irpf=15% → base=100, irpf=15, neto=121-15=106
        val r = form("121", irpf = "15.0").recalcFiscal()!!
        assertEquals(100.0, r.baseImponible!!, 0.001)
        assertEquals(15.0, r.irpfAmount, 0.001)
        assertEquals(106.0, r.totalNeto, 0.001)
    }

    @Test
    fun `iva cero deja base igual al total`() {
        val r = form("50", iva = "0.0").recalcFiscal()!!
        assertEquals(50.0, r.baseImponible!!, 0.001)
        assertEquals(0.0, r.ivaAmount!!, 0.001)
        assertEquals(50.0, r.totalNeto, 0.001)
    }

    @Test
    fun `total no numerico devuelve null`() {
        assertNull(form("").recalcFiscal())
        assertNull(form("abc").recalcFiscal())
    }

    @Test
    fun `iva porcentaje no numerico devuelve null`() {
        assertNull(form("100", iva = "x").recalcFiscal())
    }

    @Test
    fun `base o cuota no numericas devuelven null`() {
        assertNull(EditInvoiceForm(total = "100", baseImponible = "x").recalcFiscal())
        assertNull(EditInvoiceForm(total = "100", cuotaIva = "x").recalcFiscal())
    }

    @Test
    fun `total negativo devuelve null`() {
        assertNull(form("-10").recalcFiscal())
    }

    @Test
    fun `valores no finitos y porcentajes fuera de rango devuelven null`() {
        assertNull(form("NaN").recalcFiscal())
        assertNull(form("Infinity").recalcFiscal())
        assertNull(form("100", iva = "-1").recalcFiscal())
        assertNull(form("100", irpf = "101").recalcFiscal())
    }

    @Test
    fun `decimales se respetan con precision de 2 decimales`() {
        // total=84.70 iva=21% → base≈70.0, iva≈14.70
        val r = form("84.70").recalcFiscal()!!
        assertEquals(70.0, r.baseImponible!!, 0.01)
        assertEquals(14.70, r.ivaAmount!!, 0.01)
    }
}
