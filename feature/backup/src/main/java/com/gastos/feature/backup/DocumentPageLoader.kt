package com.gastos.feature.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Finish the current renderer request, then load the latest requested page.
 * Conflation skips intermediate requests; stale images and failures are never presented.
 * Closing the viewer still cancels this collector and the active request.
 */
internal suspend fun <T> loadDocumentPages(
    requests: Flow<Int>,
    currentPage: () -> Int,
    render: suspend (Int) -> T,
    loading: (Int) -> Unit,
    ready: (Int, T) -> Unit,
    failed: (Int, Exception) -> Unit,
    discard: (T) -> Unit
) {
    requests.distinctUntilChanged().conflate().collect { page ->
        if (currentPage() != page) return@collect
        loading(page)
        try {
            val image = render(page)
            try {
                currentCoroutineContext().ensureActive()
                if (currentPage() == page) ready(page, image) else discard(image)
            } catch (cancelled: CancellationException) {
                discard(image)
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (currentPage() == page) failed(page, failure)
        }
    }
}
