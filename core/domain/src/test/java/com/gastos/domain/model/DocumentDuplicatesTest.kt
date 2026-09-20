package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentDuplicatesTest {
    private fun identity(uuid: String = "new"): DocumentIdentity = DocumentIdentity(uuid, 1, false, "2026-09-19", "EUR", 121.0,
        "Synthetic", "ES", "F-0001", "B123", null, 1)
    private fun existing(): DocumentIdentity = identity("existing")
    @Test fun `same invoice from another photo is strong`() { assertEquals(DuplicateStrength.STRONG, DocumentDuplicates.compare(identity(), existing())!!.strength) }
    @Test fun `same amount alone is not a duplicate`() { assertNull(DocumentDuplicates.compare(identity().copy(number = null), existing().copy(number = null))) }
    @Test fun `different issuers series and years are distinct`() {
        listOf(existing().copy(issuerTaxId = "B456"), existing().copy(number = "G-0001"), existing().copy(date = "2025-09-19")).forEach {
            assertNull(DocumentDuplicates.compare(identity(), it))
        }
    }
    @Test fun `leading zeros and significant punctuation are preserved`() {
        assertNull(DocumentDuplicates.compare(identity(), existing().copy(number = "F-1")))
        assertNull(DocumentDuplicates.compare(identity(), existing().copy(number = "F0001")))
    }
    @Test fun `confusable O and zero are weak only`() {
        assertEquals(DuplicateStrength.POSSIBLE, DocumentDuplicates.compare(identity().copy(number = "F-OOO1"), existing())!!.strength)
        assertEquals(identity().key, identity().copy(number = "F-OOO1").key)
    }
    @Test fun `same number with conflicting values needs review`() {
        val result = DocumentDuplicates.compare(identity(), existing().copy(amount = 122.0))!!
        assertEquals(DuplicateStrength.POSSIBLE, result.strength)
        assertEquals(DuplicateReason.CONFLICT, result.reason)
    }
    @Test fun `missing tax identity allows possible duplicate not strong`() {
        assertEquals(DuplicateStrength.POSSIBLE, DocumentDuplicates.compare(identity().copy(issuerTaxId = null), existing())!!.strength)
    }
    @Test fun `matching cross income and expense is a review conflict`() {
        assertEquals(DuplicateReason.CONFLICT, DocumentDuplicates.compare(identity().copy(isIncome = true), existing())!!.reason)
    }
    @Test fun `self edit excludes its UUID`() { assertNull(DocumentDuplicates.compare(identity(), identity())) }
    @Test fun `file hash detects copies regardless of extracted number`() {
        val evidence = DocumentEvidence(ScannedDocument(), sourceSha256 = "same-hash")
        assertEquals(DuplicateReason.SAME_FILE, DocumentDuplicates.compare(identity().copy(evidence = evidence), existing().copy(number = "different", evidence = evidence))!!.reason)
    }
    @Test fun `confirmation only releases possible matches at the same version`() {
        val old = existing().copy(issuerTaxId = null)
        val incoming = identity().copy(evidence = DocumentEvidence(ScannedDocument(), distinctFrom = setOf(old.version)))
        assertTrue(DocumentDuplicates.blocking(incoming, DocumentDuplicates.find(incoming, listOf(old))).isEmpty())
        assertFalse(DocumentDuplicates.blocking(incoming, DocumentDuplicates.find(incoming, listOf(old.copy(updatedAt = 2)))).isEmpty())
        assertFalse(DocumentDuplicates.blocking(incoming, DocumentDuplicates.find(incoming, listOf(existing()))).isEmpty())
    }
    private fun payroll(uuid: String, period: String = "2026-09", kind: String = "ordinary", reference: String? = "PAY-09"): DocumentIdentity =
        identity(uuid).copy(isIncome = true, number = null, evidence = DocumentEvidence(ScannedDocument(kind = "nomina", issuer = "Company",
            issuerTaxId = "B123", workerId = "WORKER-1", payPeriod = period, paymentKind = kind, payrollReference = reference)))
    @Test fun `matching payroll reference worker and period is strong`() {
        assertEquals(DuplicateStrength.STRONG, DocumentDuplicates.compare(payroll("a"), payroll("b"))!!.strength)
    }
    @Test fun `same payroll amount in different months is not duplicate`() { assertNull(DocumentDuplicates.compare(payroll("a"), payroll("b", period = "2026-08"))) }
    @Test fun `extra payroll and arrears do not merge with ordinary pay`() { assertNull(DocumentDuplicates.compare(payroll("a", reference = null), payroll("b", kind = "extra", reference = null))) }
    @Test fun `no reference means possible payroll even with matching amount`() {
        assertEquals(DuplicateStrength.POSSIBLE, DocumentDuplicates.compare(payroll("a", reference = null), payroll("b", reference = null))!!.strength)
    }
    @Test fun `corrected gross with same payroll net needs review`() {
        val other = payroll("b")
        assertEquals(DuplicateStrength.POSSIBLE, DocumentDuplicates.compare(payroll("a"), other.copy(evidence = other.evidence!!.copy(document = other.evidence!!.document.copy(gross = 2000.0))))!!.strength)
    }
    @Test fun `period is not inferred from payment date`() {
        val other = payroll("b")
        assertNull(DocumentDuplicates.compare(payroll("a"), other.copy(evidence = other.evidence!!.copy(document = other.evidence!!.document.copy(payPeriod = null)))))
    }
}
