package com.gastos.feature.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal data class GeminiTextPart(val text: String)

internal data class GeminiInlineDataPart(
    val mimeType: String,
    val data: String,
    val byteCount: Int = 0
)

internal data class GeminiContent(
    val role: String,
    val textParts: List<GeminiTextPart> = emptyList(),
    val inlineDataParts: List<GeminiInlineDataPart> = emptyList()
)

internal data class GeminiGenerateRequest(
    val apiKey: String,
    val systemInstruction: String,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null
)

internal data class GeminiGenerationConfig(
    val thinkingLevel: String? = null,
    val responseMimeType: String? = null,
    val responseSchema: JSONObject? = null,
    val mediaResolution: String? = null
)

internal class GeminiApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

@Singleton
class GeminiRestClient @Inject constructor() {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    internal suspend fun generateContent(request: GeminiGenerateRequest): String = withContext(Dispatchers.IO) {
        executeRequest(request, stream = false)
    }

    internal fun streamGenerateContent(request: GeminiGenerateRequest): Flow<String> = flow {
        val response = executeHttpRequest(request, stream = true)
        response.use { httpResponse ->
            val body = requireNotNull(httpResponse.body) { "Respuesta vacía de Gemini." }
            val context = currentCoroutineContext()
            var eventBuffer = StringBuilder()
            var emittedText = false
            body.charStream().buffered().useLines { lines ->
                lines.forEach { line ->
                    context.ensureActive()
                    when {
                        line.isBlank() -> {
                            emitSseChunk(eventBuffer.toString())?.let {
                                emittedText = true
                                emit(it)
                            }
                            eventBuffer = StringBuilder()
                        }
                        line.startsWith(DATA_PREFIX) -> eventBuffer.append(line.removePrefix(DATA_PREFIX).trim())
                    }
                }
            }
            emitSseChunk(eventBuffer.toString())?.let {
                emittedText = true
                emit(it)
            }
            check(emittedText) { "Gemini devolvió una respuesta vacía." }
        }
    }

    private suspend fun executeRequest(request: GeminiGenerateRequest, stream: Boolean): String {
        val response = executeHttpRequest(request, stream)
        response.use { httpResponse ->
            return parseCandidateText(httpResponse.body?.string().orEmpty())
                .takeIf(String::isNotBlank)
                ?: error("Gemini devolvió una respuesta vacía.")
        }
    }

    private suspend fun executeHttpRequest(request: GeminiGenerateRequest, stream: Boolean): okhttp3.Response {
        val body = request.toJson().toString()
        val httpRequest = Request.Builder()
            .url(buildUrl(stream))
            .header(HEADER_API_KEY, request.apiKey)
            .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = httpClient.newCall(httpRequest)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val response = try {
            runInterruptible(Dispatchers.IO) { call.execute() }
        } catch (error: IOException) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Gemini request cancelled", error)
            }
            throw error
        }
        if (!response.isSuccessful) {
            val message = parseErrorMessage(response.body?.string())
            response.close()
            throw GeminiApiException(response.code, message)
        }
        return response
    }

    private fun buildUrl(stream: Boolean): String = if (stream) {
        "$BASE_URL/models/$MODEL_NAME:streamGenerateContent?alt=sse"
    } else {
        "$BASE_URL/models/$MODEL_NAME:generateContent"
    }

    private fun parseCandidateText(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val root = JSONObject(rawBody)
        return root.extractCandidateText()
    }

    private fun emitSseChunk(rawEvent: String): String? {
        if (rawEvent.isBlank() || rawEvent == "[DONE]") return null
        return JSONObject(rawEvent).extractCandidateText().takeIf(String::isNotBlank)
    }

    private fun JSONObject.extractCandidateText(): String {
        val candidates = optJSONArray("candidates") ?: return ""
        val firstCandidate = candidates.optJSONObject(0) ?: return ""
        val content = firstCandidate.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val text = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            text.append(part.optString("text"))
        }
        return text.toString()
    }

    private fun parseErrorMessage(rawBody: String?): String {
        val safeFallback = "Error al contactar con Gemini."
        if (rawBody.isNullOrBlank()) return safeFallback
        return runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty().ifBlank { safeFallback }
        }.getOrDefault(safeFallback)
    }

    private fun GeminiGenerateRequest.toJson(): JSONObject = JSONObject().apply {
        put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        )
        put("contents", JSONArray().apply {
            contents.forEach { content ->
                put(JSONObject().apply {
                    put("role", content.role)
                    put("parts", JSONArray().apply {
                        content.textParts.forEach { part -> put(JSONObject().put("text", part.text)) }
                        content.inlineDataParts.forEach { part ->
                            put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject()
                                        .put("mimeType", part.mimeType)
                                        .put("data", part.data)
                                )
                            )
                        }
                    })
                })
            }
        })
        generationConfig?.let { config ->
            put("generationConfig", JSONObject().apply {
                config.thinkingLevel?.let { level ->
                    put("thinkingConfig", JSONObject().put("thinkingLevel", level))
                }
                config.responseMimeType?.let { mimeType -> put("responseMimeType", mimeType) }
                config.responseSchema?.let { schema -> put("responseSchema", schema) }
                config.mediaResolution?.let { resolution -> put("mediaResolution", resolution) }
            })
        }
    }

    private companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODEL_NAME = "gemini-3.6-flash"
        private const val HEADER_API_KEY = "x-goog-api-key"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val MEDIA_TYPE_JSON = "application/json"
        private const val DATA_PREFIX = "data:"
        private val JSON_MEDIA_TYPE = MEDIA_TYPE_JSON.toMediaType()
    }
}
