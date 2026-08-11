package com.gastos.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiRestClientRequestTest {
    @Test
    fun `ocr generation config contains latency and structured output controls`() {
        val config = GeminiGenerationConfig(
            thinkingLevel = "low",
            responseMimeType = "application/json",
            mediaResolution = "MEDIA_RESOLUTION_MEDIUM"
        )

        assertEquals("low", config.thinkingLevel)
        assertEquals("application/json", config.responseMimeType)
        assertEquals("MEDIA_RESOLUTION_MEDIUM", config.mediaResolution)
    }

    @Test
    fun `regular requests omit generation config`() {
        val request = GeminiGenerateRequest(
            apiKey = "test-key",
            systemInstruction = "chat",
            contents = emptyList()
        )

        assertNull(request.generationConfig)
    }
}
