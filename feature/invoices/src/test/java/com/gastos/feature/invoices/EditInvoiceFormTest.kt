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

    private fun form(total: String, iva: String = "21.0", irpf: String = "0.0") =
        EditInvoiceForm(total = total, ivaPercent = iva, irpfPercent = irpf)

    @Test
    fun `total sin IVA fields con IVA 21 desglosa base y cuota correctamente`() {
        // total=121 con IVA 21% → base=100, iva=21, irpf=0, neto=121
        val r = form("121").recalcFiscal()!!
        assertEquals(100.0, r.baseImponible, 0.001)
        assertEquals(21.0, r.ivaAmount, 0.001)
        assertEquals(121.0, r.total, 0.001)
        assertEquals(0.0, r.irpfAmount, 0.001)
        assertEquals(121.0, r.totalNeto, 0.001)
    }

    @Test
    fun `total con IRPF 15 descuenta la retencion sobre la base`() {
        // total=121, iva=21%, irpf=15% → base=100, irpf=15, neto=121-15=106
        val r = form("121", irpf = "15.0").recalcFiscal()!!
        assertEquals(100.0, r.baseImponible, 0.001)
        assertEquals(15.0, r.irpfAmount, 0.001)
        assertEquals(106.0, r.totalNeto, 0.001)
    }

    @Test
    fun `iva cero deja base igual al total`() {
        val r = form("50", iva = "0.0").recalcFiscal()!!
        assertEquals(50.0, r.baseImponible, 0.001)
        assertEquals(0.0, r.ivaAmount, 0.001)
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
    fun `total negativo devuelve null`() {
        assertNull(form("-10").recalcFiscal())
    }

    @Test
    fun `decimales se respetan con precision de 2 decimales`() {
        // total=84.70 iva=21% → base≈70.0, iva≈14.70
        val r = form("84.70").recalcFiscal()!!
        assertEquals(70.0, r.baseImponible, 0.01)
        assertEquals(14.70, r.ivaAmount, 0.01)
    }
}