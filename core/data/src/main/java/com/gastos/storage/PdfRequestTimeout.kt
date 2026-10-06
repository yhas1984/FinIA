package com.gastos.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** An internal renderer deadline is a retryable failure; caller cancellation still propagates. */
internal suspend fun <T : Any> withPdfRequestTimeout(block: suspend CoroutineScope.() -> T): T =
    withTimeoutOrNull(20_000, block) ?: error("PDF_RENDERER_UNAVAILABLE")
