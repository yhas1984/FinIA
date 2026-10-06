package com.gastos.feature.settings

import com.gastos.feature.ai.GeminiKeyValidation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiKeyValidationSessionTest {
    @Test fun temporaryFailureKeepsPreviousKeyAndOffersRetry() = runTest {
        var stored = "previous"
        var state: ApiKeyValidation = ApiKeyValidation.None
        var validating = false
        val session = ApiKeyValidationSession(this,
            { GeminiKeyValidation.Unavailable(GeminiKeyValidation.Reason.QUOTA, "quota") },
            { stored = it }, { busy, value -> validating = busy; state = value })
        session.submit("candidate")
        advanceUntilIdle()
        assertEquals("previous", stored)
        assertTrue(state is ApiKeyValidation.Unavailable)
        assertFalse(validating)
    }

    @Test fun changingKeyCancelsAndDiscardsEvenANonCooperativeOldResponse() = runTest {
        val late = CompletableDeferred<GeminiKeyValidation>()
        val saved = mutableListOf<String>()
        var state: ApiKeyValidation = ApiKeyValidation.None
        val session = ApiKeyValidationSession(this, { key ->
            if (key == "old") withContext(NonCancellable) { late.await() } else GeminiKeyValidation.Valid
        }, { saved.add(it) }, { _, value -> state = value })
        session.submit("old")
        runCurrent()
        session.submit("new")
        runCurrent()
        late.complete(GeminiKeyValidation.Valid)
        advanceUntilIdle()
        assertEquals(listOf("new"), saved)
        assertEquals(ApiKeyValidation.Valid, state)
    }

    @Test fun closingDialogAndDeletingKeyNeverSavesAnOutstandingCandidate() = runTest {
        val reply = CompletableDeferred<GeminiKeyValidation>()
        val saved = mutableListOf<String>()
        var state: ApiKeyValidation = ApiKeyValidation.None
        val session = ApiKeyValidationSession(this, { withContext(NonCancellable) { reply.await() } },
            { saved.add(it) }, { _, value -> state = value })
        session.submit("candidate")
        runCurrent()
        session.reset()
        session.clear()
        reply.complete(GeminiKeyValidation.Valid)
        advanceUntilIdle()
        assertEquals(listOf(""), saved)
        assertEquals(ApiKeyValidation.None, state)
    }

    @Test fun rejectedKeyAndPersistenceFailureNeverReportSuccess() = runTest {
        var state: ApiKeyValidation = ApiKeyValidation.None
        val rejected = ApiKeyValidationSession(this, { GeminiKeyValidation.Rejected("rejected") },
            { fail("Rejected credentials must not be persisted") }, { _, value -> state = value })
        rejected.submit("candidate")
        advanceUntilIdle()
        assertTrue(state is ApiKeyValidation.Invalid)
        val failure = ApiKeyValidationSession(this, { GeminiKeyValidation.Valid },
            { error("synthetic storage failure") }, { _, value -> state = value })
        failure.submit("candidate")
        advanceUntilIdle()
        assertEquals(ApiKeyValidation.StorageError, state)
    }

    @Test fun anEncryptedCommitAlreadyInFlightCannotOverwriteALaterDeletion() = runTest {
        val finishCommit = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val session = ApiKeyValidationSession(this, { GeminiKeyValidation.Valid }, { key ->
            if (key.isNotEmpty()) finishCommit.await()
            saved.add(key)
        }, { _, _ -> })
        session.submit("candidate")
        runCurrent()
        session.clear()
        runCurrent()
        finishCommit.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("candidate", ""), saved)
    }
}
