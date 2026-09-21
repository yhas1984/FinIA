package com.gastos.feature.ai

import com.gastos.domain.model.OcrProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class GeminiFallbackTest {
    @Test fun `completed structured stream does not wait for the server to close the connection`() = runTest {
        val command = "{\"action\":\"add_income\",\"monto\":120}"
        val event = "data: " + JSONObject().put("candidates", org.json.JSONArray().put(JSONObject()
            .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", command))))
            .put("finishReason", "STOP"))) + "\n\n"
        var readsAfterFinish = 0
        var calls = 0
        val pending = Buffer().writeUtf8(event)
        val openConnection = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (pending.size > 0) return pending.read(sink, byteCount)
                readsAfterFinish++
                throw IOException("Server keeps completed SSE connection open")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source() = openConnection
        }
        val client = GeminiRestClient(GeminiTransport { _, _, _, _, _ ->
            calls++
            response(200, "").newBuilder().body(body).build()
        }, {}, { 0L }, { _, _, _ -> })
        assertEquals(listOf(command), client.streamGenerateContent(request).toList())
        assertEquals(1, calls)
        assertEquals(0, readsAfterFinish)
    }

    private fun imageRequest() = request.copy(contents = listOf(GeminiContent("user",
        inlineDataParts = listOf(GeminiInlineDataPart("image/jpeg", "synthetic")))))

    @Test fun `OCR moves straight to the next model and avoids recent service failures on the next document`() = runTest {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val client = GeminiRestClient(GeminiTransport { _, _, model, _, _ ->
            calls += model
            if (model == GeminiRestClient.PRIMARY_MODEL) response(503, "{}") else response(200, success("ok"))
        }, { waits += it }, { 0L }, { _, _, _ -> })
        repeat(2) { assertEquals("ok", client.generateContent(imageRequest())) }
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL, GeminiRestClient.FALLBACK_MODEL), calls)
        assertTrue(waits.isEmpty())
        assertEquals("ok", client.generateContent(imageRequest().copy(apiKey = "another-test-key")))
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL), calls.takeLast(2))
    }

    @Test fun `OCR retry delay supplied by Google is still respected when changing model`() = runTest {
        val waits = mutableListOf<Long>()
        val client = GeminiRestClient(GeminiTransport { _, _, model, _, _ ->
            if (model == GeminiRestClient.PRIMARY_MODEL)
                response(429, """{"error":{"details":[{"retryDelay":"1.5s"}]}}""")
            else response(200, success("ok"))
        }, { waits += it }, { 0L }, { _, _, _ -> })
        assertEquals("ok", client.generateContent(imageRequest()))
        assertEquals(listOf(1500L), waits)
    }

    @Test fun `OCR cooldown preserves quota cause and never leaks from one key to another`() = runTest {
        val calls = mutableListOf<String>()
        val quota = """{"error":{"details":[{"violations":[{"quotaId":"RequestsPerDay","quotaDimensions":{"model":"flash"}}]}]}}"""
        val client = client(calls, ArrayDeque(List(3) { 429 to quota }))
        repeat(2) {
            try { client.generateContent(imageRequest()); fail() }
            catch (error: GeminiApiException) { assertEquals(GeminiFailure.DAILY_QUOTA, error.category) }
        }
        assertEquals(3, calls.size)
    }
    private val request = GeminiGenerateRequest("synthetic-key", "test", emptyList())
    private fun response(code: Int, body: String) = Response.Builder().request(Request.Builder().url("https://example.test").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
    private fun success(text: String) = """{"candidates":[{"content":{"parts":[{"text":"$text"}]},"finishReason":"STOP"}]}"""
    private fun client(calls: MutableList<String>, responses: ArrayDeque<Pair<Int, String>>) = GeminiRestClient(
        GeminiTransport { _, key, model, _, _ ->
            assertEquals("synthetic-key", key)
            calls += model
            val (code, body) = responses.removeFirst()
            response(code, body)
        }, {}, { 0L }, { _, _, _ -> })

    @Test fun `two transient failures use Flash fallback as the third request`() = runTest {
        val calls = mutableListOf<String>()
        val client = client(calls, ArrayDeque(listOf(503 to "{}", 503 to "{}", 200 to success("ok"))))
        assertEquals("ok", client.generateContent(request))
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL), calls)
    }
    @Test fun `both Flash models failing use Flash Lite as the final fallback`() = runTest {
        val calls = mutableListOf<String>()
        val client = client(calls, ArrayDeque(listOf(503 to "{}", 503 to "{}", 503 to "{}", 200 to success("lite"))))
        assertEquals("lite", client.generateContent(request))
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"), calls)
    }
    @Test fun `fast reading and detailed rereading preserve schema and image through Lite fallback`() = runTest {
        for (profile in OcrProfile.entries) {
            val calls = mutableListOf<String>()
            val payloads = mutableListOf<JSONObject>()
            val client = GeminiRestClient(GeminiTransport { body, key, model, stream, _ ->
                assertEquals("synthetic-key", key)
                assertFalse(stream)
                calls += model
                payloads += JSONObject(body)
                if (model == GeminiRestClient.LITE_FALLBACK_MODEL) response(200, success("valid"))
                else response(503, "{}")
            }, {}, { 0L }, { _, _, _ -> })
            val operation = imageRequest().copy(generationConfig = GeminiGenerationConfig(
                thinkingLevel = profile.thinkingLevel,
                responseMimeType = "application/json",
                responseSchema = JSONObject("""{"type":"OBJECT","properties":{"total":{"type":"NUMBER"}}}"""),
                mediaResolution = "MEDIA_RESOLUTION_HIGH"
            ))
            assertEquals("valid", client.generateContent(operation) { it == "valid" })
            assertEquals(listOf("gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"), calls)
            payloads.forEach { payload ->
                val config = payload.getJSONObject("generationConfig")
                assertEquals(profile.thinkingLevel, config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
                assertEquals("application/json", config.getString("responseMimeType"))
                assertEquals("NUMBER", config.getJSONObject("responseSchema").getJSONObject("properties").getJSONObject("total").getString("type"))
                assertEquals("MEDIA_RESOLUTION_HIGH", config.getString("mediaResolution"))
                assertEquals(payloads.first().getJSONArray("contents").toString(), payload.getJSONArray("contents").toString())
            }
        }
    }
    @Test fun `per-model quota on both Flash models skips retries and reaches Lite`() = runTest {
        val quota = """{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDay","quotaDimensions":{"model":"flash"}}]}]}}"""
        val calls = mutableListOf<String>()
        assertEquals("lite", client(calls, ArrayDeque(listOf(429 to quota, 429 to quota, 200 to success("lite")))).generateContent(request))
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL, GeminiRestClient.LITE_FALLBACK_MODEL), calls)
    }
    @Test fun `invalid OCR from both Flash models reaches Lite and Lite must also pass validation`() = runTest {
        for (validLite in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val client = client(calls, ArrayDeque(listOf(200 to success("invalid"), 200 to success("invalid"),
                200 to success(if (validLite) "valid" else "invalid"))))
            val imageRequest = request.copy(contents = listOf(GeminiContent("user", inlineDataParts =
                listOf(GeminiInlineDataPart("image/jpeg", "synthetic")))))
            if (validLite) assertEquals("valid", client.generateContent(imageRequest) { it == "valid" })
            else {
                try { client.generateContent(imageRequest) { it == "valid" }; fail() }
                catch (error: GeminiApiException) { assertEquals(GeminiFailure.INVALID_OUTPUT, error.category) }
            }
            assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL, GeminiRestClient.LITE_FALLBACK_MODEL), calls)
        }
    }
    @Test fun `nonrecoverable failure on second Flash stops before Lite`() = runTest {
        val globalQuota = """{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDay","quotaDimensions":{}}]}]}}"""
        listOf(401 to "{}", 400 to "{}", 429 to globalQuota,
            200 to """{"promptFeedback":{"blockReason":"SAFETY"}}""").forEach { failure ->
            val calls = mutableListOf<String>()
            try { client(calls, ArrayDeque(listOf(503 to "{}", 503 to "{}", failure))).generateContent(request); fail() }
            catch (_: GeminiApiException) { }
            assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL), calls)
        }
    }
    @Test fun `daily per-model quota skips the primary retry and global quota stops`() = runTest {
        fun quota(model: Boolean) = """{"error":{"details":[{"violations":[{"quotaId":"GenerateRequestsPerDay","quotaDimensions":${if (model) "{\"model\":\"flash\"}" else "{}"}}]}]}}"""
        val calls = mutableListOf<String>()
        assertEquals("ok", client(calls, ArrayDeque(listOf(429 to quota(true), 200 to success("ok")))).generateContent(request))
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL, GeminiRestClient.FALLBACK_MODEL), calls)
        calls.clear()
        try { client(calls, ArrayDeque(listOf(429 to quota(false)))).generateContent(request); fail() }
        catch (error: GeminiApiException) { assertEquals(GeminiFailure.GLOBAL_QUOTA, error.category) }
        assertEquals(1, calls.size)
    }
    @Test fun `invalid key bad request and safety never use fallback`() = runTest {
        listOf(401 to "{}", 400 to "{}", 200 to """{"promptFeedback":{"blockReason":"SAFETY"}}""").forEach { failure ->
            val calls = mutableListOf<String>()
            try { client(calls, ArrayDeque(listOf(failure))).generateContent(request); fail() } catch (_: GeminiApiException) { }
            assertEquals(1, calls.size)
        }
    }
    @Test fun `invalid OCR is retried on fallback within the same budget`() = runTest {
        val calls = mutableListOf<String>()
        val client = client(calls, ArrayDeque(listOf(200 to success("invalid"), 200 to success("valid"))))
        assertEquals("valid", client.generateContent(request) { it == "valid" })
        assertEquals(2, calls.size)
        assertEquals(GeminiRestClient.FALLBACK_MODEL, calls.last())
    }
    @Test fun `stream never switches models after a visible fragment`() = runTest {
        val calls = mutableListOf<String>()
        val partial = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n"
        val pieces = mutableListOf<String>()
        try { client(calls, ArrayDeque(listOf(200 to partial))).streamGenerateContent(request).toList(pieces); fail() }
        catch (_: GeminiApiException) { }
        assertEquals(listOf("partial"), pieces)
        assertEquals(1, calls.size)
    }
    @Test fun `stream can fall back to Lite before any visible fragment`() = runTest {
        val calls = mutableListOf<String>()
        val pieces = client(calls, ArrayDeque(listOf(503 to "{}", 503 to "{}", 503 to "{}", 200 to "data: ${success("ok")}\n\n")))
            .streamGenerateContent(request).toList()
        assertEquals(listOf("ok"), pieces)
        assertEquals(4, calls.size)
        assertEquals(GeminiRestClient.LITE_FALLBACK_MODEL, calls.last())
    }
    @Test fun `cancellation never becomes a retry`() = runTest {
        var count = 0
        val client = GeminiRestClient(GeminiTransport { _, _, _, _, _ -> count++; throw CancellationException() }, {}, { 0L }, { _, _, _ -> })
        try { client.generateContent(request); fail() } catch (_: CancellationException) { }
        assertEquals(1, count)
    }
    @Test fun `server retry delay is respected`() {
        val failure = GeminiRestClient.classifyError(429, """{"error":{"details":[{"retryDelay":"4.5s"}]}}""", "2")
        assertEquals(4500L, failure.retryAfterMillis)
    }
    @Test fun `chat and OCR use bounded call deadlines and never a fifth request`() = runTest {
        for (image in listOf(false,true)) {
            val deadlines=mutableListOf<Long>()
            val client=GeminiRestClient(GeminiTransport { _,_,_,_,timeout ->
                deadlines += timeout; response(503,"{}")
            }, {}, { 0L }, { _,_,_ -> })
            val operation=if (image) request.copy(contents=listOf(GeminiContent("user",inlineDataParts=listOf(GeminiInlineDataPart("image/jpeg","synthetic"))))) else request
            try { client.generateContent(operation); fail() } catch (_: GeminiApiException) { }
            assertEquals(if (image) 3 else 4,deadlines.size)
            assertTrue(deadlines.all { it in 1..(if(image) 45_000L else 22_500L) })
        }
    }

    @Test fun `incomplete structured stream stays invisible and switches models before committing`() = runTest {
        val calls = mutableListOf<String>()
        fun event(text: String, stop: Boolean): String {
            val candidate = JSONObject().put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",text))))
            if(stop) candidate.put("finishReason","STOP")
            return "data: " + JSONObject().put("candidates",org.json.JSONArray().put(candidate)) + "\n\n"
        }
        val partial = event("```json\n{\"action\":\"add_income\",",false)
        val valid = "```json\n{\"action\":\"add_income\",\"monto\":35}\n```"
        val pieces = client(calls,ArrayDeque(listOf(200 to partial,200 to event(valid,true)))).streamGenerateContent(request).toList()
        assertEquals(listOf(valid),pieces)
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL,GeminiRestClient.FALLBACK_MODEL),calls)
    }
    @Test fun `malformed structured stream with STOP still falls back without exposing JSON`() = runTest {
        val calls = mutableListOf<String>()
        val malformed = "data: " + JSONObject().put("candidates",org.json.JSONArray().put(JSONObject()
            .put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text","{invalid"))))
            .put("finishReason","STOP"))) + "\n\n"
        assertEquals(listOf("ok"),client(calls,ArrayDeque(listOf(200 to malformed,200 to "data: ${success("ok")}\n\n"))).streamGenerateContent(request).toList())
        assertEquals(2,calls.size)
    }

}
