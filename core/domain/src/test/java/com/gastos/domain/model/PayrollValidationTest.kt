package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class PayrollValidationTest {
    private fun payroll(): ScannedDocument = ScannedDocument(kind = "nomina", issuer = "Synthetic Employer", currency = "EUR",
        date = "2025-07-20", gross = 1190.0, net = 790.0, socialSecurity = 80.0, payPeriod = "Del 1 al 31 de Enero de 2.026",
        payroll = PayrollDetails(periodStart = "2026-01-01", periodEnd = "2026-01-31", totalDeductions = 400.0,
            linesComplete = true,
            lines = listOf(PayrollLine("Salary", PayrollLineType.EARNING, 1200.0, 100.0, 12.0),
                PayrollLine("Unworked hours", PayrollLineType.EARNING, -10.0, 2.0, -5.0),
                PayrollLine("Advance", PayrollLineType.DEDUCTION, 100.0, deductionType = PayrollDeductionType.OTHER),
                PayrollLine("Cash offset", PayrollLineType.DEDUCTION, 220.0, deductionType = PayrollDeductionType.OTHER),
                PayrollLine("Employee contribution", PayrollLineType.DEDUCTION, 80.0, deductionType = PayrollDeductionType.SOCIAL_SECURITY))))

    @Test fun `negative earnings and deductions use their own columns without requiring product quantities or VAT`() {
        val result = DocumentValidator.validate(payroll())
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(-10.0, result.document.payroll!!.lines[1].amount!!, 0.0)
        assertNull(result.document.payroll!!.lines.last().quantity)
        assertNull(result.document.vatPercent)
        assertNull(result.document.priceBasis)
        val income = DocumentEvidence(result.document).toPayroll("synthetic", "photo")
        assertEquals(790.0, income.monto, 0.0)
        assertEquals(1190.0, income.totalDevengado, 0.0)
    }

    @Test fun `accounting date comes from the printed period not a date inferred from an employee code`() {
        val result = DocumentValidator.validate(payroll())
        assertEquals("2026-01-31", result.document.date)
        assertEquals(PayrollDateBasis.PERIOD_END, result.document.payroll!!.dateBasis)
        assertNull(result.document.payroll!!.paymentDate)
        assertTrue("date" in result.derivedFields)
        assertEquals(result.document, DocumentValidator.validate(result.document).document)
    }

    @Test fun `issue and payment dates require supporting printed text and take precedence over period end`() {
        val source = payroll()
        val dated = source.copy(payroll = source.payroll!!.copy(paymentDate = "2026-02-03", paymentDateText = "Fecha de pago: 03/02/2026"))
        assertEquals("2026-02-03", DocumentValidator.validate(dated).document.date)
        assertTrue(DocumentValidator.validate(dated).issues.isEmpty())
        for (text in listOf(null, "Antigüedad: 13/01/2021")) {
            assertTrue(DocumentValidator.validate(dated.copy(payroll = dated.payroll!!.copy(paymentDateText = text))).issues.any { it.field == "payroll.paymentDate" })
        }
    }

    @Test fun `international date formats are supported and absent invalid and reversed periods require review`() {
        val source = payroll()
        for (period in listOf("1–31 January 2026", "du 1 au 31 janvier 2026", "01/01/2026 - 31/01/2026", "2026-01-01 / 2026-01-31")) {
            assertTrue(period, DocumentValidator.validate(source.copy(payPeriod = period)).issues.isEmpty())
        }
        for (details in listOf(source.payroll!!.copy(periodEnd = "2026-02-31"), source.payroll.copy(periodStart = "2026-01-31", periodEnd = "2026-01-01"),
            source.payroll.copy(periodStart = null, periodEnd = null))) {
            assertTrue(DocumentValidator.validate(source.copy(payroll = details)).issues.isNotEmpty())
        }
        assertTrue(DocumentValidator.validate(source.copy(payPeriod = "2026-01")).issues.isNotEmpty())
    }

    @Test fun `contribution arrears never replace this payment deduction or change net`() {
        val source = payroll()
        val wrong = DocumentValidator.validate(source.copy(socialSecurity = 150.0))
        assertTrue(wrong.issues.any { it.field == "socialSecurity" && it.reason == ReviewReason.INCONSISTENT })
        assertEquals(150.0, wrong.document.socialSecurity!!, 0.0) // A conflict is not silently repaired.
        val derived = DocumentValidator.validate(source.copy(socialSecurity = null))
        assertTrue(derived.issues.isEmpty())
        assertEquals(80.0, derived.document.socialSecurity!!, 0.0)
        assertEquals(790.0, derived.document.net!!, 0.0)
        assertNull(derived.document.withholdingPercent)
        assertNull(derived.document.withholdingAmount)
    }

    @Test fun `missing rows unknown types and mismatched totals are never auto saved`() {
        val source = payroll()
        val samples = listOf(source.copy(net = 900.0), source.copy(payroll = source.payroll!!.copy(totalDeductions = 401.0)),
            source.copy(payroll = source.payroll.copy(linesComplete = false)),
            source.copy(payroll = source.payroll.copy(lines = source.payroll.lines.dropLast(1))),
            source.copy(payroll = source.payroll.copy(lines = source.payroll.lines + PayrollLine("Unclear"))),
            source.copy(payroll = source.payroll.copy(lines = source.payroll.lines.map { it.copy(amount = Double.NaN) })))
        samples.forEach { assertTrue(DocumentValidator.validate(it).issues.isNotEmpty()) }
    }

    @Test fun `rounding is tolerated but original values and signs survive serialization`() {
        val source = payroll().copy(gross = 1189.99, net = 789.99)
        assertTrue(DocumentValidator.validate(source).issues.isEmpty())
        val evidence = DocumentEvidence(source, originalExtraction = "synthetic original")
        assertEquals(evidence, DocumentEvidenceCodec.decode(DocumentEvidenceCodec.encode(evidence)))
        assertNull(DocumentEvidenceCodec.decode("""{"document":{"kind":"nomina","gross":100,"net":90}}""")!!.document.payroll)
    }

    @Test fun `old untyped OCR drafts require a fresh reading instead of becoming valid after this fix`() {
        val old = DocumentEvidence(payroll().copy(payroll = null, lines = emptyList()), originalExtraction = "old extraction")
        assertTrue(DocumentValidator.validate(old).issues.any { it.field == "payroll" })
    }

    @Test fun `explicit manual accounting date is preserved`() {
        val source = payroll().copy(date = "2026-02-05", payroll = payroll().payroll!!.copy(dateBasis = PayrollDateBasis.MANUAL))
        assertEquals("2026-02-05", DocumentValidator.validate(source).document.date)
    }

    @Test fun `identifier claims are rejected while supported printed period notation is normalized`() {
        val source = payroll().copy(payroll = payroll().payroll!!.copy(issueDate = "2025-07-20", issueDateText = "Código: 2025-07-2078",
            periodStart = "2.026-01-01", periodEnd = "2.026-01-31"))
        val result = DocumentValidator.validate(source)
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals("2026-01-31", result.document.date)
        assertNull(result.document.payroll!!.issueDate)
        assertEquals("Código: 2025-07-2078", result.document.payroll!!.issueDateText)
        assertEquals("2026-01-01", result.document.payroll!!.periodStart)
        assertTrue("payroll.issueDate" in result.derivedFields)
        assertTrue(DocumentValidator.validate(source.copy(payPeriod = "Unclear")).issues.isNotEmpty())
    }
}
