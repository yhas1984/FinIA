package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentEnrichmentTest {
    private val date: Long = requireNotNull(DocumentValidator.parseDate("2026-10-05"))
    private fun invoice(document: ScannedDocument, origins: Map<String, DocumentFieldOrigin> = emptyMap()): Invoice =
        DocumentEvidence(document, fieldOrigins = origins).toInvoice("source", "synthetic.jpg", date).first

    @Test fun addingReceiptKeepsNotesIdentityCategoryAndManualMerchantUntilUserChooses() {
        val oldDocument = ScannedDocument(kind = "ticket", issuer = "My edited name", date = "2026-10-05", currency = "EUR", total = 20.0)
        val previous = invoice(oldDocument, mapOf("issuer" to DocumentFieldOrigin.MANUAL)).copy(
            id = 7, documentUuid = "existing", notas = "Keep my note", categoryId = "fuel", subcategoryId = "van",
            categoria = "Fuel", subcategoria = "Van", createdAt = 123, financialRevision = 4, manualAmountAdjusted = true)
        val scanned = invoice(oldDocument.copy(issuer = "Printed legal name"), mapOf("issuer" to DocumentFieldOrigin.EXTRACTED))
        val result = DocumentEnrichment.invoice(previous, scanned)
        assertEquals(listOf("issuer"), result.conflicts)
        assertEquals("My edited name", result.value.proveedor)
        assertEquals("Keep my note", result.value.notas)
        assertEquals(7L, result.value.id)
        assertEquals("existing", result.value.documentUuid)
        assertEquals("fuel", result.value.categoryId)
        assertEquals("van", result.value.subcategoryId)
        assertEquals(123L, result.value.createdAt)
        assertEquals(5L, result.value.financialRevision)
        assertTrue(result.value.manualAmountAdjusted)
        assertEquals(DocumentFieldOrigin.MANUAL, result.value.evidence!!.fieldOrigins["issuer"])
    }

    @Test fun explicitlyChoosingDocumentResolvesManualConflictAndUpdatesOrigin() {
        val document = ScannedDocument(kind = "ticket", issuer = "Edited", date = "2026-10-05", currency = "EUR", total = 20.0)
        val previous = invoice(document, mapOf("issuer" to DocumentFieldOrigin.MANUAL)).let { old ->
            old.copy(notas = "Keep note", evidence = old.evidence!!.copy(correctedFields = setOf("issuer")))
        }
        val scanned = invoice(document.copy(issuer = "Printed"), mapOf("issuer" to DocumentFieldOrigin.EXTRACTED))
        val result = DocumentEnrichment.invoice(previous, scanned, keepExisting = false)
        assertEquals("Printed", result.value.proveedor)
        assertEquals("Keep note", result.value.notas)
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.value.evidence!!.fieldOrigins["issuer"])
        assertFalse("issuer" in result.value.evidence!!.correctedFields)
    }

    @Test fun newPrintedMerchantReplacesWalletLabelWithExtractedProvenanceWithoutManualConflict() {
        val document = ScannedDocument(kind = "ticket", issuer = "Wallet store", date = "2026-10-05", currency = "EUR", total = 20.0)
        val previous = invoice(document, mapOf("issuer" to DocumentFieldOrigin.WALLET)).copy(financialRevision = 1)
        val scanned = invoice(document.copy(issuer = "Printed legal name"), mapOf("issuer" to DocumentFieldOrigin.EXTRACTED))
        val result = DocumentEnrichment.invoice(previous, scanned)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("Printed legal name", result.value.proveedor)
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.value.evidence!!.fieldOrigins["issuer"])
    }

    @Test fun absentFiscalFieldsDoNotEraseKnownMultipleTaxesOrExplicitZeroLine() {
        val taxes = listOf(DocumentTax("IVA", 21.0, 10.0, 2.1, TaxTreatment.TAXABLE),
            DocumentTax("IVA", 10.0, 10.0, 1.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 0.0, 10.0, 0.0, TaxTreatment.ZERO_RATED))
        val oldDocument = ScannedDocument(kind = "ticket", issuer = "Store", date = "2026-10-05", currency = "EUR", total = 33.1,
            number = "F-123", issuerTaxId = "ES-TEST", taxBase = 30.0, vatAmount = 3.1, taxes = taxes, taxesComplete = true)
        val previous = invoice(oldDocument)
        val scanned = invoice(ScannedDocument(kind = "ticket", issuer = "Store", date = "2026-10-05", currency = "EUR", total = 33.1))
        val result = DocumentEnrichment.invoice(previous, scanned)
        assertEquals(taxes, result.value.taxes)
        assertEquals(taxes, result.value.evidence!!.document.taxes)
        assertNull(result.value.ivaPercent)
        assertEquals(30.0, result.value.baseImponible!!, 0.0)
        assertEquals(3.1, result.value.cuotaIva!!, 0.0)
        assertEquals("F-123", result.value.numeroFactura)
        assertEquals("ES-TEST", result.value.nifEmisor)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test fun bankIncomeEnrichedByPartialPayrollDoesNotInventGrossOrContributions() {
        val previous = Income(documentUuid = "income", id = 9, fecha = date, concepto = "Payment", monto = 450.62,
            totalDevengado = 0.0, totalNeto = 0.0, notas = "Keep income note", origin = "BANK",
            evidence = DocumentEvidence(ScannedDocument(issuer = "Payment", total = 450.62, currency = "EUR", date = "2026-10-05"),
                fieldOrigins = mapOf("issuer" to DocumentFieldOrigin.BANK)))
        val scanned = DocumentEvidence(ScannedDocument(kind = "nomina", issuer = "Employer", date = "2026-10-05", currency = "EUR", net = 450.62),
            fieldOrigins = mapOf("issuer" to DocumentFieldOrigin.EXTRACTED, "net" to DocumentFieldOrigin.EXTRACTED)).toPayroll("new", "payroll.jpg", date)
        val result = DocumentEnrichment.income(previous, scanned)
        assertEquals("income", result.value.documentUuid)
        assertEquals(9L, result.value.id)
        assertEquals("Keep income note", result.value.notas)
        assertEquals(0.0, result.value.totalDevengado, 0.0)
        assertNull(result.value.evidence!!.document.gross)
        assertNull(result.value.evidence!!.document.socialSecurity)
        assertNull(result.value.evidence!!.document.contributionBase)
        assertEquals(450.62, result.value.totalNeto, 0.0)
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.value.evidence!!.fieldOrigins["issuer"])
    }

    @Test fun payrollFieldsMissingFromNewScanKeepKnownDetailsWithoutTurningUnknownsIntoZero() {
        val details = PayrollDetails(periodStart = "2026-10-01", periodEnd = "2026-10-31", totalDeductions = 100.0)
        val document = ScannedDocument(kind = "nomina", issuer = "Employer", date = "2026-10-05", currency = "EUR",
            gross = 1000.0, net = 900.0, contributionBase = 980.0, socialSecurity = 64.0, payroll = details)
        val previous = DocumentEvidence(document).toPayroll("existing", "old.jpg", date)
        val scanned = DocumentEvidence(document.copy(gross = null, contributionBase = null, socialSecurity = null, payroll = null)).toPayroll("new", "new.jpg", date)
        val result = DocumentEnrichment.income(previous, scanned)
        assertEquals(1000.0, result.value.totalDevengado, 0.0)
        assertEquals(980.0, result.value.evidence!!.document.contributionBase!!, 0.0)
        assertEquals(64.0, result.value.evidence!!.document.socialSecurity!!, 0.0)
        assertEquals(previous.evidence!!.document.payroll, result.value.evidence!!.document.payroll)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test fun captureDefaultsDoNotReplaceKnownBankDateOrCurrencyDuringEnrichment() {
        val bankDate = requireNotNull(DocumentValidator.parseDate("2026-10-01"))
        val previous = invoice(ScannedDocument(kind = "ticket", date = "2026-10-01", currency = "EUR", total = 20.0, issuer = "Store"),
            mapOf("date" to DocumentFieldOrigin.BANK, "currency" to DocumentFieldOrigin.BANK)).copy(fecha = bankDate)
        val prepared = DocumentProvenance.prepareCapture(DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Store", total = 20.0)), "USD", date)
        val scanned = prepared.toInvoice("new", "new.jpg", date).first
        val result = DocumentEnrichment.invoice(previous, scanned)
        assertEquals(bankDate, result.value.fecha)
        assertEquals("EUR", result.value.moneda)
        assertEquals("2026-10-01", result.value.evidence!!.document.date)
        assertEquals("EUR", result.value.evidence!!.document.currency)
        assertEquals(DocumentFieldOrigin.BANK, result.value.evidence!!.fieldOrigins["date"])
        assertEquals(DocumentFieldOrigin.BANK, result.value.evidence!!.fieldOrigins["currency"])
    }

    private val incomeMetadataFields = setOf("country", "issuerTaxId", "recipientTaxId", "workerId", "payPeriod",
        "paymentKind", "payrollReference", "taxBase", "vatAmount", "withholdingAmount", "discount", "priceBasis")
    private fun incomeMetadata() = ScannedDocument(kind = "factura_emitida", date = "2026-10-05", issuer = "Employer", currency = "EUR", total = 900.0,
        country = "ES", issuerTaxId = "SYNTHETIC-ISSUER", recipientTaxId = "SYNTHETIC-RECIPIENT", workerId = "SYNTHETIC-WORKER",
        payPeriod = "2026-10", paymentKind = "ordinary", payrollReference = "SYNTHETIC-PAYROLL", taxBase = 850.0,
        vatAmount = 100.0, withholdingAmount = 50.0, discount = 4.0, priceBasis = "tax_excluded")
    private fun bankIncome(document: ScannedDocument, manual: Boolean) = Income(documentUuid = if (manual) "existing" else "scan", id = if (manual) 9 else 0,
        fecha = date, concepto = "Employer", fuente = "Employer", monto = 900.0, ivaPercent = null, origin = "BANK", financialRevision = if (manual) 1 else 0,
        evidence = DocumentEvidence(document, correctedFields = if (manual) incomeMetadataFields else emptySet(),
            fieldOrigins = incomeMetadataFields.associateWith { if (manual) DocumentFieldOrigin.MANUAL else DocumentFieldOrigin.EXTRACTED }))

    @Test fun attachingPartialIncomeKeepsEveryPreviouslyEnteredFiscalAndPayrollIdentifier() {
        val document = incomeMetadata()
        val previous = bankIncome(document, true)
        val scanned = bankIncome(ScannedDocument(kind = "factura_emitida", date = "2026-10-05", issuer = "Employer", currency = "EUR", total = 900.0), false)
        val result = DocumentEnrichment.income(previous, scanned)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(document, result.value.evidence!!.document)
        incomeMetadataFields.forEach { field ->
            assertEquals(field, DocumentFieldOrigin.MANUAL, result.value.evidence!!.fieldOrigins[field])
            assertTrue(field, field in result.value.evidence!!.correctedFields)
        }
    }

    @Test fun conflictingIncomeIdentifiersAndFiscalAmountsRequireExplicitChoiceAndKeepManualValuesByDefault() {
        val document = incomeMetadata()
        val changed = document.copy(country = "FR", issuerTaxId = "OTHER-ISSUER", recipientTaxId = "OTHER-RECIPIENT", workerId = "OTHER-WORKER",
            payPeriod = "2026-09", paymentKind = "extra", payrollReference = "OTHER-PAYROLL", taxBase = 840.0,
            vatAmount = 110.0, withholdingAmount = 60.0, discount = 5.0, priceBasis = "tax_included")
        val previous = bankIncome(document, true)
        val scanned = bankIncome(changed, false)
        val kept = DocumentEnrichment.income(previous, scanned)
        assertEquals(incomeMetadataFields, kept.conflicts.toSet())
        assertEquals(document, kept.value.evidence!!.document)
        val replaced = DocumentEnrichment.income(previous, scanned, keepExisting = false)
        assertEquals(incomeMetadataFields, replaced.conflicts.toSet())
        assertEquals(changed, replaced.value.evidence!!.document)
        assertEquals(previous.documentUuid, replaced.value.documentUuid)
        incomeMetadataFields.forEach { field ->
            assertEquals(field, DocumentFieldOrigin.EXTRACTED, replaced.value.evidence!!.fieldOrigins[field])
            assertFalse(field, field in replaced.value.evidence!!.correctedFields)
        }
    }

    @Test fun invoiceReceiptRetainsKnownEvidenceAmountsAndCompletenessWhenNewScanOmitsThem() {
        val fields = setOf("withholdingAmount", "discount", "priceBasis", "linesComplete", "taxesComplete")
        val document = ScannedDocument(kind = "ticket", issuer = "Store", date = "2026-10-05", currency = "EUR", total = 20.0,
            withholdingAmount = 2.0, discount = 1.0, priceBasis = "tax_included", linesComplete = true, taxesComplete = true)
        val previous = invoice(document, fields.associateWith { DocumentFieldOrigin.MANUAL })
        val scanned = invoice(document.copy(withholdingAmount = null, discount = null, priceBasis = null, linesComplete = null, taxesComplete = null))
        val result = DocumentEnrichment.invoice(previous, scanned)
        val actual = result.value.evidence!!.document
        assertTrue(result.conflicts.isEmpty())
        assertEquals(previous.evidence!!.document.withholdingAmount, actual.withholdingAmount)
        assertEquals(previous.evidence!!.document.discount, actual.discount)
        assertEquals(previous.evidence!!.document.priceBasis, actual.priceBasis)
        assertEquals(previous.evidence!!.document.linesComplete, actual.linesComplete)
        assertEquals(previous.evidence!!.document.taxesComplete, actual.taxesComplete)
        fields.forEach { assertEquals(it, DocumentFieldOrigin.MANUAL, result.value.evidence!!.fieldOrigins[it]) }
    }
}
