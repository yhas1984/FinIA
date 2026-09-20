package com.gastos.feature.ai

import com.gastos.extension.SafeLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
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

internal enum class GeminiFailure { RECOVERABLE, DAILY_QUOTA, GLOBAL_QUOTA, AUTH, BAD_REQUEST, SAFETY, INVALID_OUTPUT, MODEL_UNAVAILABLE }
internal class GeminiApiException(val statusCode: Int, message: String,
    val category: GeminiFailure = GeminiFailure.RECOVERABLE, val retryAfterMillis: Long = 0,
    val diagnostics: String? = null) : IOException(message)

internal fun interface GeminiTransport {
    suspend fun execute(body: String, key: String, model: String, stream: Boolean, timeoutMillis: Long): okhttp3.Response
}

internal class GoogleGeminiTransport : GeminiTransport {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    override suspend fun execute(body: String, key: String, model: String, stream: Boolean, timeoutMillis: Long): okhttp3.Response {
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:$action")
            .header("x-goog-api-key", key).post(body.toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return runInterruptible(Dispatchers.IO) { call.execute() }
    }
}

@Singleton
class GeminiRestClient internal constructor(
    private val transport: GeminiTransport,
    private val wait: suspend (Long) -> Unit,
    private val jitter: () -> Long,
    private val log: (String, Long, String) -> Unit
) {
    private data class ModelPause(val untilNanos: Long, val failure: GeminiApiException)
    private val unavailableUntil = java.util.concurrent.ConcurrentHashMap<String, ModelPause>()
    @Inject constructor() : this(GoogleGeminiTransport(), { delay(it) }, { kotlin.random.Random.nextLong(0, 251) },
        { model, duration, category -> SafeLog.d("Gemini", "model=$model durationMs=$duration category=$category") })

    internal suspend fun generateContent(request: GeminiGenerateRequest, validate: (String) -> Boolean = { true }): String = withContext(Dispatchers.IO) {
        val budget = budget(request)
        withTimeoutOrNull(budget) {
            attempts(request, stream = false) { response ->
                val envelope = JSONObject(runInterruptible { response.body?.string().orEmpty() })
                val text = envelope.extractCandidateText()
                if (text.isBlank() || !validate(text)) {
                    val candidate = envelope.optJSONArray("candidates")?.optJSONObject(0)
                    val finish = candidate?.optString("finishReason").orEmpty().filter { it in 'A'..'Z' || it == '_' }.take(48)
                    val diagnostics = "textChars=${text.length} candidates=${envelope.optJSONArray("candidates")?.length() ?: 0} finish=$finish parts=${candidate?.optJSONObject("content")?.optJSONArray("parts")?.length() ?: 0}"
                    throw GeminiApiException(200, "Invalid model output", GeminiFailure.INVALID_OUTPUT, diagnostics = diagnostics)
                }
                text
            }
        } ?: throw GeminiApiException(408, "Gemini operation timed out")
    }

    internal fun streamGenerateContent(request: GeminiGenerateRequest): Flow<String> = flow {
        var failure: Throwable? = null
        // Drain delivered fragments before reporting a terminal network/model error.
        // Throwing upstream of flowOn can cancel its channel and lose buffered text.
        streamChunks(request).catch { error ->
            if (error is CancellationException) throw error
            failure = error
        }.flowOn(Dispatchers.IO).collect { emit(it) }
        failure?.let { throw it }
    }

    private fun streamChunks(request: GeminiGenerateRequest): Flow<String> = flow {
        var emitted = false
        val completed = withTimeoutOrNull(budget(request)) {
            attempts(request, stream = true, mayRetry = { !emitted }) { response ->
                val reader = response.body?.charStream()?.buffered() ?: throw GeminiApiException(200, "Empty response", GeminiFailure.INVALID_OUTPUT)
                var event = StringBuilder()
                var finished = false
                suspend fun publish() {
                    val raw = event.toString()
                    event = StringBuilder()
                    if (raw == "[DONE]") { finished = true; return }
                    if (raw.isBlank()) return
                    val chunk = JSONObject(raw)
                    val finish = chunk.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
                    val text = chunk.extractCandidateText()
                    if (text.isNotBlank()) { emitted = true; emit(text) }
                    if (finish == "STOP") finished = true
                    else if (finish.isNotBlank()) throw GeminiApiException(200, "Incomplete response", GeminiFailure.INVALID_OUTPUT)
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = runInterruptible(Dispatchers.IO) { reader.readLine() } ?: break
                    if (line.isBlank()) publish()
                    else if (line.startsWith("data:")) event.append(line.removePrefix("data:").trim())
                }
                publish()
                if (!emitted || !finished) throw GeminiApiException(200, "Incomplete response", GeminiFailure.INVALID_OUTPUT)
            }
            true
        }
        if (completed == null) throw GeminiApiException(408, "Gemini operation timed out")
    }

    private suspend fun <T> attempts(request: GeminiGenerateRequest, stream: Boolean,
        mayRetry: () -> Boolean = { true }, consume: suspend (okhttp3.Response) -> T): T {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget(request))
        val models = if (isImageRequest(request)) OCR_MODELS else ATTEMPT_MODELS
        val keyFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(request.apiKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        var step = 0
        var last: Exception? = null
        while (step < models.size) {
            currentCoroutineContext().ensureActive()
            val model = models[step]
            val cacheKey = "$keyFingerprint:$model"
            val pause = unavailableUntil[cacheKey]
            if (isImageRequest(request) && pause != null && pause.untilNanos > System.nanoTime()) {
                last = pause.failure
                step++
                continue
            }
            val started = System.nanoTime()
            try {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMillis <= 0) throw GeminiApiException(408, "Gemini operation timed out")
                val response = transport.execute(request.toJson().toString(), request.apiKey, model, stream,
                    minOf(budget(request) / models.size, remainingMillis))
                val value = response.use {
                    if (!it.isSuccessful) throw classifyError(it.code, runInterruptible { it.body?.string() }, it.header("Retry-After"))
                    consume(it)
                }
                log(model, (System.nanoTime() - started) / 1_000_000, "SUCCESS")
                unavailableUntil.remove(cacheKey)
                return value
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                val failure = when (error) {
                    is GeminiApiException -> error
                    is java.io.InterruptedIOException -> GeminiApiException(408, "Reading timed out")
                    is IOException -> GeminiApiException(503, "Connection interrupted")
                    is org.json.JSONException -> GeminiApiException(200, "Invalid model output", GeminiFailure.INVALID_OUTPUT)
                    else -> throw error
                }
                log(model, (System.nanoTime() - started) / 1_000_000,
                    "${failure.category.name} status=${failure.statusCode}" + (failure.diagnostics?.let { " $it" } ?: ""))
                if (!mayRetry() || failure.category in setOf(GeminiFailure.AUTH, GeminiFailure.BAD_REQUEST,
                        GeminiFailure.SAFETY, GeminiFailure.GLOBAL_QUOTA)) throw failure
                last = failure
                if (isImageRequest(request) && failure.category in setOf(GeminiFailure.DAILY_QUOTA, GeminiFailure.MODEL_UNAVAILABLE, GeminiFailure.RECOVERABLE)) {
                    val cooldown = when (failure.category) {
                        GeminiFailure.DAILY_QUOTA, GeminiFailure.MODEL_UNAVAILABLE -> 300_000L
                        else -> 30_000L
                    }
                    if (unavailableUntil.size > 30) unavailableUntil.clear()
                    unavailableUntil[cacheKey] = ModelPause(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxOf(cooldown, failure.retryAfterMillis)), failure)
                }
                val previousModel = model
                step = if (!isImageRequest(request) && failure.category in setOf(GeminiFailure.DAILY_QUOTA, GeminiFailure.INVALID_OUTPUT, GeminiFailure.MODEL_UNAVAILABLE)) {
                    if (step < 2) 2 else step + 1
                } else step + 1
                if (step < models.size) {
                    val serverDelay = if (failure.category == GeminiFailure.DAILY_QUOTA) 0 else failure.retryAfterMillis
                    val backoff = if (isImageRequest(request) && models[step] != previousModel) 0L else (500L shl step) + jitter()
                    if (maxOf(serverDelay, backoff) > 0) wait(maxOf(serverDelay, backoff))
                }
            }
        }
        throw requireNotNull(last)
    }

    private fun isImageRequest(request: GeminiGenerateRequest): Boolean = request.contents.any { it.inlineDataParts.isNotEmpty() }
    private fun budget(request: GeminiGenerateRequest): Long = if (isImageRequest(request)) 135_000L else 90_000L

    private fun JSONObject.extractCandidateText(): String {
        optJSONObject("error")?.let { throw classifyError(it.optInt("code", 503), toString(), null) }
        if (optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotBlank())
            throw GeminiApiException(400, "Response blocked by safety policy", GeminiFailure.SAFETY)
        val candidate = optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        if (candidate.optString("finishReason") in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY"))
            throw GeminiApiException(400, "Response blocked by safety policy", GeminiFailure.SAFETY)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!part.optBoolean("thought", false)) this@buildString.append(part.optString("text"))
            }
        }
    }

    internal companion object {
        const val PRIMARY_MODEL = "gemini-3.6-flash"
        const val FALLBACK_MODEL = "gemini-3.8-flash"
        const val LITE_FALLBACK_MODEL = "gemini-3.5-flash-lite"
        private val ATTEMPT_MODELS = listOf(PRIMARY_MODEL, PRIMARY_MODEL, FALLBACK_MODEL, LITE_FALLBACK_MODEL)
        private val OCR_MODELS = listOf(PRIMARY_MODEL, FALLBACK_MODEL, LITE_FALLBACK_MODEL)
        private const val JSON_MIME = "application/json"
        internal fun classifyError(status: Int, raw: String?, retryAfter: String?): GeminiApiException {
            val error = runCatching { JSONObject(raw.orEmpty()).optJSONObject("error") }.getOrNull()
            val details = error?.optJSONArray("details")
            var retry = retryAfter?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
            if (retryAfter != null && retry == 0L) {
                retry = runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .parse(retryAfter)!!.time - System.currentTimeMillis() }.getOrDefault(0L)
            }
            var daily = false
            var global = false
            for (index in 0 until (details?.length() ?: 0)) {
                val detail = details!!.optJSONObject(index) ?: continue
                detail.optString("retryDelay").removeSuffix("s").toDoubleOrNull()?.let { retry = maxOf(retry, (it * 1000).toLong()) }
                val violations = detail.optJSONArray("violations")
                for (v in 0 until (violations?.length() ?: 0)) {
                    val violation = violations!!.optJSONObject(v) ?: continue
                    val metric = (violation.optString("quotaId") + violation.optString("quotaMetric")).lowercase()
                    val perDay = "perday" in metric || "per_day" in metric
                    val model = violation.optJSONObject("quotaDimensions")?.optString("model").orEmpty()
                    if (perDay && model.isNotBlank()) daily = true
                    if (perDay && model.isBlank() || "spend" in metric || "billing" in metric) global = true
                }
            }
            val category = when {
                status == 401 || status == 403 -> GeminiFailure.AUTH
                status == 400 -> GeminiFailure.BAD_REQUEST
                status == 404 -> GeminiFailure.MODEL_UNAVAILABLE
                status == 429 && global -> GeminiFailure.GLOBAL_QUOTA
                status == 429 && daily -> GeminiFailure.DAILY_QUOTA
                status == 408 || status == 429 || status in 500..599 -> GeminiFailure.RECOVERABLE
                else -> GeminiFailure.BAD_REQUEST
            }
            return GeminiApiException(status, "Gemini: ${category.name} ($status)", category, retry.coerceAtLeast(0))
        }
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

}
