package com.gastos.feature.chatbot

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.gastos.data.local.entity.DocumentDraftEntity
import com.gastos.domain.model.*
import com.gastos.feature.ai.*
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.storage.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var model: DocumentCaptureViewModel
    private val reader = mockk<AIService>(relaxed = true)
    private val store = mockk<DocumentCaptureStore>(relaxed = true)
    private val sheets = mockk<SheetsSyncManager>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)
    private val draft = DocumentDraftEntity("uuid", "hash", "photo")
    private val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", date = "2026-09-19", issuer = "Synthetic",
        currency = "EUR", total = 100.0, vatPercent = 0.0, linesComplete = true))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns uri
        every { store.observe() } returns flowOf(emptyList())
        model = DocumentCaptureViewModel(mockk<Context>(relaxed = true), reader, store, mockk(relaxed = true), sheets)
    }
    @After fun cleanup() { model.viewModelScope.cancel(); unmockkStatic(Uri::class); Dispatchers.resetMain() }

    @Test fun `same file is rejected before Gemini and remote sync`() = runTest(dispatcher) {
        val invoice = evidence.toInvoice("old", "photo").first.copy(id = 7)
        coEvery { store.start(any()) } returns CaptureStart.Duplicate(listOf(invoice.documentIdentity()))
        model.processImage(uri)
        advanceUntilIdle()
        assertEquals(7L, model.state.value.duplicates.single().existing.localId)
        coVerify(exactly = 0) { reader.readDocument(any(), any()) }
        coVerify(exactly = 0) { sheets.syncExpense(any(), any()) }
    }

    @Test fun `existing draft resumes without another model request`() = runTest(dispatcher) {
        val incomplete = evidence.copy(document = evidence.document.copy(date = null))
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft.copy(evidenceJson = DocumentEvidenceCodec.encode(incomplete)), true)
        model.processImage(uri)
        advanceUntilIdle()
        assertEquals(incomplete, model.state.value.evidence)
        coVerify(exactly = 0) { reader.readDocument(any(), any()) }
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test fun `failed reading keeps the photo and error without creating editable invented data`() = runTest(dispatcher) {
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.Failure("Model unavailable")
        model.processImage(uri)
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertEquals(draft, model.state.value.selected)
        assertNull(model.state.value.evidence)
        assertEquals("Model unavailable", model.state.value.message)
        coVerify { store.update("uuid", null, "Model unavailable") }
        coVerify(exactly = 0) { store.save(any(), any()) }
        model.reread()
        advanceUntilIdle()
        coVerify(exactly = 2) { reader.readDocument(any(), OcrProfile.FAST) }
    }

    @Test fun `financial doubt persists draft and never rereads or saves automatically`() = runTest(dispatcher) {
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        val missing = evidence.copy(document = evidence.document.copy(date = null))
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.NeedsReview(missing, DocumentValidator.validate(missing).issues)
        model.processImage(uri)
        advanceUntilIdle()
        assertNotNull(model.state.value.selected)
        coVerify(exactly = 1) { reader.readDocument(any(), OcrProfile.FAST) }
        coVerify(exactly = 1) { store.update("uuid", any(), null) }
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test fun `remote failure after local commit remains saved and double tap does not duplicate`() = runTest(dispatcher) {
        val invoice = evidence.toInvoice("uuid", "photo").first.copy(id = 8)
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.Ready(evidence)
        coEvery { store.save(any(), any()) } returns CaptureSave.Saved(invoice)
        coEvery { sheets.syncExpense(any(), any()) } throws IllegalStateException("synthetic outage")
        model.processImage(uri)
        model.processImage(uri)
        advanceUntilIdle()
        assertEquals(8L, model.state.value.saved!!.localId)
        assertNull(model.state.value.selected)
        coVerify(exactly = 1) { store.save(any(), any()) }
    }

    @Test fun `reopening a valid interrupted draft saves without another API call`() = runTest(dispatcher) {
        val pending = draft.copy(evidenceJson = DocumentEvidenceCodec.encode(evidence))
        val invoice = evidence.toInvoice("uuid", "photo").first.copy(id = 9)
        coEvery { store.get("uuid") } returns pending
        coEvery { store.save("uuid", evidence) } returns CaptureSave.Saved(invoice)
        model.open(pending)
        advanceUntilIdle()
        assertEquals(9L, model.state.value.saved!!.localId)
        assertNull(model.state.value.selected)
        coVerify(exactly = 1) { store.save("uuid", evidence) }
        coVerify(exactly = 0) { reader.readDocument(any(), any()) }
    }

    @Test fun `resubmitted valid draft saves once without another API call`() = runTest(dispatcher) {
        val pending = draft.copy(evidenceJson = DocumentEvidenceCodec.encode(evidence))
        coEvery { store.start(any()) } returns CaptureStart.Draft(pending, true)
        coEvery { store.save("uuid", evidence) } returns CaptureSave.Saved(evidence.toInvoice("uuid", "photo").first.copy(id = 9))
        model.processImage(uri)
        advanceUntilIdle()
        assertEquals(9L, model.state.value.saved!!.localId)
        coVerify(exactly = 1) { store.save("uuid", evidence) }
        coVerify(exactly = 0) { reader.readDocument(any(), any()) }
    }

    @Test fun `dismissed uncertain document remains available and unsaved when reopened`() = runTest(dispatcher) {
        val incomplete = evidence.copy(document = evidence.document.copy(date = null))
        val pending = draft.copy(evidenceJson = DocumentEvidenceCodec.encode(incomplete))
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { store.get("uuid") } returns pending
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.NeedsReview(incomplete, DocumentValidator.validate(incomplete).issues)
        model.processImage(uri)
        advanceUntilIdle()
        model.dismissNotice()
        assertNull(model.state.value.selected)
        model.open(pending)
        advanceUntilIdle()
        assertNull(model.state.value.evidence!!.document.date)
        assertEquals(100.0, model.state.value.evidence!!.document.total!!, 0.0)
        coVerify(exactly = 1) { reader.readDocument(any(), any()) }
        coVerify(exactly = 0) { store.save(any(), any()) }
        coVerify(exactly = 0) { store.discard(any()) }
    }

    @Test fun `uncertain reading retries only on request and then saves a coherent document`() = runTest(dispatcher) {
        val incomplete = evidence.copy(document = evidence.document.copy(date = null))
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), OcrProfile.FAST) } returns DocumentReadResult.NeedsReview(incomplete, DocumentValidator.validate(incomplete).issues)
        coEvery { reader.readDocument(any(), OcrProfile.THOROUGH) } returns DocumentReadResult.Ready(evidence)
        coEvery { store.save(any(), any()) } returns CaptureSave.Saved(evidence.toInvoice("uuid", "photo").first.copy(id = 8))
        model.processImage(uri)
        advanceUntilIdle()
        coVerify(exactly = 0) { store.save(any(), any()) }
        model.reread()
        advanceUntilIdle()
        assertEquals(8L, model.state.value.saved!!.localId)
        coVerify(exactly = 1) { reader.readDocument(any(), OcrProfile.THOROUGH) }
        coVerify(exactly = 1) { store.save(any(), any()) }
    }

    @Test fun `strong duplicate cannot be confirmed as distinct`() = runTest(dispatcher) {
        val match = DuplicateMatch(evidence.toInvoice("existing", "photo").first.copy(id = 7).documentIdentity(), DuplicateStrength.STRONG, DuplicateReason.SAME_INVOICE)
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.Ready(evidence)
        coEvery { store.save(any(), any()) } returns CaptureSave.Duplicate(listOf(match))
        model.processImage(uri)
        advanceUntilIdle()
        model.confirmDistinct()
        advanceUntilIdle()
        assertNull(model.state.value.saved)
        assertEquals(listOf(match), model.state.value.duplicates)
        coVerify(exactly = 1) { store.save(any(), any()) }
        coVerify(exactly = 0) { sheets.syncExpense(any(), any()) }
    }

    @Test fun `possible duplicate requires explicit consent bound to the matching version`() = runTest(dispatcher) {
        val match = DuplicateMatch(evidence.toInvoice("existing", "photo").first.copy(id = 7).documentIdentity(), DuplicateStrength.POSSIBLE, DuplicateReason.SAME_INVOICE)
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.Ready(evidence)
        coEvery { store.save(any(), any()) } returnsMany listOf(CaptureSave.Duplicate(listOf(match)), CaptureSave.Saved(evidence.toInvoice("uuid", "photo").first.copy(id = 8)))
        model.processImage(uri)
        advanceUntilIdle()
        assertNull(model.state.value.saved)
        coVerify(exactly = 1) { store.save(any(), any()) }
        model.confirmDistinct()
        advanceUntilIdle()
        assertEquals(8L, model.state.value.saved!!.localId)
        coVerify(exactly = 1) { store.save("uuid", match { match.existing.version in it.distinctFrom }) }
    }

    @Test fun `cancelled reading keeps its draft and permits a later retry`() = runTest(dispatcher) {
        coEvery { store.start(any()) } returns CaptureStart.Draft(draft, false)
        coEvery { reader.readDocument(any(), any()) } coAnswers { awaitCancellation() }
        model.processImage(uri)
        runCurrent()
        assertTrue(model.state.value.busy)
        model.cancelReading()
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertEquals(draft, model.state.value.selected)
        coVerify(exactly = 0) { store.save(any(), any()) }
        coVerify(exactly = 0) { store.discard(any()) }
        coEvery { reader.readDocument(any(), any()) } returns DocumentReadResult.Ready(evidence)
        coEvery { store.save(any(), any()) } returns CaptureSave.Saved(evidence.toInvoice("uuid", "photo").first.copy(id = 8))
        model.reread()
        advanceUntilIdle()
        assertEquals(8L, model.state.value.saved!!.localId)
    }

    @Test fun `cancellation before the coroutine starts does not leave capture busy`() = runTest(dispatcher) {
        model.processImage(uri)
        assertTrue(model.state.value.busy)
        model.cancelReading()
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        coVerify(exactly = 0) { reader.readDocument(any(), any()) }
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test fun `discard failure keeps the pending photo accessible for another attempt`() = runTest(dispatcher) {
        val incomplete = evidence.copy(document = evidence.document.copy(date = null))
        val pending = draft.copy(evidenceJson = DocumentEvidenceCodec.encode(incomplete))
        coEvery { store.get("uuid") } returns pending
        coEvery { store.discard("uuid") } throws IllegalStateException("storage unavailable")
        model.open(pending)
        advanceUntilIdle()
        model.discard()
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertEquals(pending, model.state.value.selected)
        assertEquals(incomplete, model.state.value.evidence)
        coVerify(exactly = 0) { store.save(any(), any()) }
    }
}
