package com.gastos.feature.settings

import com.gastos.feature.ai.GeminiKeyValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only transient work; API keys are never placed in saved UI or navigation state. */
internal class ApiKeyValidationSession(
    private val scope: CoroutineScope,
    private val validate: suspend (String) -> GeminiKeyValidation,
    private val persist: suspend (String) -> Unit,
    private val publish: (Boolean, ApiKeyValidation) -> Unit
) {
    private var generation: Long = 0
    private var job: Job? = null
    private val persistence = Mutex()

    fun reset() {
        generation++
        job?.cancel()
        job = null
        publish(false, ApiKeyValidation.None)
    }

    fun submit(key: String) {
        reset()
        val expected: Long = generation
        publish(true, ApiKeyValidation.None)
        job = scope.launch {
            try {
                val result: GeminiKeyValidation = validate(key.trim())
                currentCoroutineContext().ensureActive()
                if (expected != generation) return@launch
                val state: ApiKeyValidation = when (result) {
                    GeminiKeyValidation.Valid -> {
                        persistCurrent(expected, key.trim())
                        ApiKeyValidation.Valid
                    }
                    is GeminiKeyValidation.Rejected -> ApiKeyValidation.Invalid(result.message)
                    is GeminiKeyValidation.Unavailable -> ApiKeyValidation.Unavailable(result.message)
                }
                if (expected == generation) publish(false, state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) publish(false, ApiKeyValidation.StorageError)
            } finally {
                if (expected == generation) job = null
            }
        }
    }

    fun clear() {
        reset()
        val expected: Long = generation
        job = scope.launch {
            try {
                persistCurrent(expected, "")
                if (expected == generation) publish(false, ApiKeyValidation.None)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) publish(false, ApiKeyValidation.StorageError)
            }
        }
    }

    private suspend fun persistCurrent(expected: Long, key: String) = persistence.withLock {
        currentCoroutineContext().ensureActive()
        if (expected == generation) {
            // Finish an already accepted encrypted commit before a later change or
            // deletion. A cancelled IO commit cannot overwrite a newer key later.
            withContext(NonCancellable) { persist(key) }
        }
    }
}
