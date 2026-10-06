package com.gastos.feature.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DocumentPageLoaderTest {
    @Test fun `immediate next preserves active render and loads only latest requested page`() = runTest {
        val page = MutableStateFlow(0)
        val first = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        val job = launch {
            loadDocumentPages(page, { page.value }, { index ->
                started += index
                if (index == 0) first.await()
                "image-$index"
            }, {}, { _, image -> published += image }, { _, error -> errors += error }, { discarded += it })
        }
        runCurrent()
        page.value = 1; runCurrent()
        page.value = 2; runCurrent()
        assertEquals(listOf(0), started)
        assertTrue(published.isEmpty())
        assertTrue(discarded.isEmpty())
        first.complete(Unit); runCurrent()
        assertEquals(listOf(0, 2), started)
        assertEquals(listOf("image-0"), discarded)
        assertEquals(listOf("image-2"), published)
        assertTrue(errors.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `failure from old page cannot replace current page with an error`() = runTest {
        val page = MutableStateFlow(0)
        val first = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        val job = launch {
            loadDocumentPages(page, { page.value }, { index ->
                if (index == 0) { first.await(); error("old request failed") }
                index
            }, {}, { _, image -> published += image }, { index, _ -> errors += index }, {})
        }
        runCurrent()
        page.value = 1; runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(listOf(1), published)
        assertTrue(errors.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `current page failure is visible and next request can recover`() = runTest {
        val page = MutableStateFlow(0)
        val published = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        val job = launch {
            loadDocumentPages(page, { page.value }, { index ->
                if (index == 0) error("PDF_RENDERER_UNAVAILABLE") else index
            }, {}, { _, image -> published += image }, { index, _ -> errors += index }, {})
        }
        runCurrent()
        assertEquals(listOf(0), errors)
        page.value = 1; runCurrent()
        assertEquals(listOf(1), published)
        job.cancelAndJoin()
    }

    @Test fun `closing viewer cancels current render without showing an error`() = runTest {
        val page = MutableStateFlow(0)
        var released = false
        val errors = mutableListOf<Int>()
        val job = launch {
            loadDocumentPages<Int>(page, { page.value }, { try { awaitCancellation() } finally { released = true } },
                {}, { _, _ -> fail("Cancelled image must not be published") }, { index, _ -> errors += index }, {})
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(released)
        assertTrue(errors.isEmpty())
    }
}
