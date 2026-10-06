package com.gastos.feature.ai

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GeminiKeyValidationTest {
    @Test fun onlyCredentialRejectionIsAnInvalidKey() {
        assertTrue(keyValidationFailure(GeminiApiException(401, "auth", GeminiFailure.AUTH), "auth") is GeminiKeyValidation.Rejected)
        for (failure in listOf(GeminiApiException(429, "quota", GeminiFailure.DAILY_QUOTA),
            GeminiApiException(503, "temporary"), GeminiApiException(408, "timeout"), IOException("network"),
            GeminiApiException(404, "model", GeminiFailure.MODEL_UNAVAILABLE))) {
            assertTrue(keyValidationFailure(failure, "safe message") is GeminiKeyValidation.Unavailable)
        }
    }

    @Test fun explicitGoogleInvalidKeyReasonDoesNotBecomeBadRequestOrRetry() {
        val failure = GeminiRestClient.classifyError(400,
            """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""", null)
        assertEquals(GeminiFailure.AUTH, failure.category)
    }
}
