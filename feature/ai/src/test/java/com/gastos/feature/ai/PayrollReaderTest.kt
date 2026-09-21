package com.gastos.feature.ai

import com.gastos.domain.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PayrollReaderTest {
    private fun source(): JSONObject = JSONObject("""{
        "tipo_documento":"nomina","empresa":"Synthetic Employer","moneda":"EUR","fecha":"2025-07-20",
        "devengado":1190,"liquido":790,"seguridad_social":80,"periodo_liquidacion":"Del 1 al 31 de enero de 2.026",
        "detalle_nomina":{"fecha_pago":null,"fecha_emision":null,"periodo_inicio":"2026-01-01","periodo_fin":"2026-01-31",
            "total_deducciones":400,"cotizaciones_trabajador":230,"cotizaciones_atrasos":150,"conceptos_completos":true,
            "conceptos":[
                {"descripcion":"Salary","tipo":"EARNING","importe":1200,"unidades":100,"valor_base":12},
                {"descripcion":"Unworked hours","tipo":"EARNING","importe":-10,"unidades":2,"valor_base":-5},
                {"descripcion":"Advance","tipo":"DEDUCTION","importe":320,"tipo_deduccion":"OTHER"},
                {"descripcion":"Current social security","tipo":"DEDUCTION","importe":80,"tipo_deduccion":"SOCIAL_SECURITY"}
            ]},"productos":[]
    }""")

    @Test fun `schema exposes typed payroll evidence without invoice products`() {
        val fields = PayrollReader.schema().getJSONObject("properties")
        assertTrue(fields.has("conceptos"))
        assertTrue(fields.has("texto_fecha_pago"))
        assertTrue(fields.has("total_deducciones"))
        assertTrue(PayrollReader.schema().getBoolean("nullable"))
    }

    @Test fun `signed payroll rows preserve original response and yield a supported period end date`() {
        val raw = source().toString()
        val result = DocumentReader.parse(raw) as DocumentReadResult.Ready
        assertEquals(raw, result.evidence.originalExtraction)
        assertEquals("2026-01-31", result.evidence.document.date)
        assertEquals(PayrollDateBasis.PERIOD_END, result.evidence.document.payroll!!.dateBasis)
        assertEquals(-10.0, result.evidence.document.payroll!!.lines[1].amount!!, 0.0)
        assertNull(result.evidence.document.payroll!!.lines.last().quantity)
        assertTrue(result.evidence.document.lines.isEmpty())
        assertEquals(790.0, result.evidence.toPayroll("id", "photo").monto, 0.0)
    }

    @Test fun `partial payroll details preserve evidence and accept the printed net`() {
        val cases = listOf(source().put("detalle_nomina", JSONObject.NULL),
            source().also { it.getJSONObject("detalle_nomina").put("total_deducciones", "oops") },
            source().also { it.getJSONObject("detalle_nomina").put("conceptos", JSONObject()) },
            source().also { it.getJSONObject("detalle_nomina").getJSONArray("conceptos").put(5) },
            source().also { it.getJSONObject("detalle_nomina").getJSONArray("conceptos").getJSONObject(1).put("importe", "oops") })
        for (json in cases) {
            val result = DocumentReader.parse(json.toString()) as DocumentReadResult.Ready
            assertTrue(result.evidence.invalidFields.any { it.startsWith("payroll") })
            assertEquals(json.toString(), result.evidence.originalExtraction)
            assertEquals(790.0, result.evidence.toPayroll("id", "photo").monto, 0.0)
        }
    }

    @Test fun `ambiguous legacy aggregate cannot replace verified payment deductions`() {
        val raw = source().put("seguridad_social", 150).toString()
        val result = DocumentReader.parse(raw) as DocumentReadResult.Ready
        assertEquals(80.0, result.evidence.document.socialSecurity!!, 0.0)
        assertTrue("socialSecurity" in result.evidence.derivedFields)
        assertEquals(raw, result.evidence.originalExtraction)
    }

    @Test fun `employee code and tenure are not accepted as payment date evidence`() {
        val json = source()
        json.getJSONObject("detalle_nomina").put("fecha_pago", "2025-07-20").put("texto_fecha_pago", "Código: 2025-07-2078")
        val result = DocumentReader.parse(json.toString()) as DocumentReadResult.Ready
        assertNull(result.evidence.document.payroll!!.paymentDate)
        assertEquals("2026-01-31", result.evidence.document.date)
        assertEquals(PayrollDateBasis.PERIOD_END, result.evidence.document.payroll!!.dateBasis)
    }
}
