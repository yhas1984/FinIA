package com.gastos.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PdfRequestTimeoutTest {
    @Test fun `internal deadline reports retryable failure and releases request`() = runTest {
        var released = false
        try {
            withPdfRequestTimeout {
                try { delay(20_001); 2 } finally { released = true }
            }
            fail("Expected renderer failure")
        } catch (failure: IllegalStateException) {
            assertEquals("PDF_RENDERER_UNAVAILABLE", failure.message)
        }
        assertTrue(released)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `successful render returning zero remains successful`() = runTest {
        assertEquals(0, withPdfRequestTimeout { 0 })
    }

    @Test fun `caller deadline remains cancellation`() = runTest {
        try {
            withTimeout(50) { withPdfRequestTimeout<Int> { awaitCancellation() } }
            fail("Expected caller cancellation")
        } catch (_: TimeoutCancellationException) { /* The outer deadline must not become a renderer error. */ }
        assertEquals(50L, testScheduler.currentTime)
    }

    @Test fun `closing caller cancels active request and releases resources`() = runTest {
        var cancelled = false
        var released = false
        val request = launch {
            try { withPdfRequestTimeout<Int> { try { awaitCancellation() } finally { released = true } } }
            catch (failure: CancellationException) { cancelled = true; throw failure }
        }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(cancelled)
        assertTrue(released)
    }
}
