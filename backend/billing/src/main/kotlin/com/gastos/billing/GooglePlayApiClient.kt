package com.gastos.billing

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface PlayPurchaseGateway {
    suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifiedPurchase
    suspend fun acknowledge(productId: String, purchaseToken: String)
    suspend fun listVoidedPurchaseTokens(): List<String>
    suspend fun verifyPlayIntegrity(token: String, expectedNonce: String)
}

class GooglePlayApiClient(
    private val config: BillingConfig,
    private val credentials: GoogleCredentials = GoogleCredentials.getApplicationDefault()
        .createScoped(listOf(ANDROID_PUBLISHER_SCOPE, CLOUD_PLATFORM_SCOPE)),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : PlayPurchaseGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifiedPurchase =
        withContext(Dispatchers.IO) {
            val root = request(
                method = "GET",
                url = "$PUBLISHER_BASE/applications/${config.packageName}/purchases/products/$productId/tokens/$purchaseToken"
            )
            val state = root.jsonObject["purchaseState"]?.jsonPrimitive?.intOrNull ?: -1
            if (state != PURCHASED_STATE) throw PurchaseNotEntitledException()
            VerifiedPurchase(
                orderId = root.jsonObject["orderId"]?.jsonPrimitive?.contentOrNull,
                purchaseTimeMillis = root.jsonObject["purchaseTimeMillis"]?.jsonPrimitive?.longOrNull,
                acknowledgementState = root.jsonObject["acknowledgementState"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

    override suspend fun acknowledge(productId: String, purchaseToken: String) {
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                url = "$PUBLISHER_BASE/applications/${config.packageName}/purchases/products/$productId/tokens/$purchaseToken:acknowledge",
                body = "{}"
            )
        }
    }

    override suspend fun listVoidedPurchaseTokens(): List<String> = withContext(Dispatchers.IO) {
        val tokens = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("$PUBLISHER_BASE/applications/${config.packageName}/purchases/voidedpurchases?maxResults=1000")
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val root = request("GET", query).jsonObject
            root["voidedPurchases"]?.jsonArray?.forEach { item ->
                item.jsonObject["purchaseToken"]?.jsonPrimitive?.contentOrNull?.let(tokens::add)
            }
            pageToken = root["tokenPagination"]?.jsonObject?.get("nextPageToken")?.jsonPrimitive?.contentOrNull
        } while (!pageToken.isNullOrBlank())
        tokens
    }

    override suspend fun verifyPlayIntegrity(token: String, expectedNonce: String) = withContext(Dispatchers.IO) {
        val requestBody = "{\"integrityToken\":${jsonString(token)}}"
        val root = request(
            method = "POST",
            url = "https://playintegrity.googleapis.com/v1/${config.packageName}:decodeIntegrityToken",
            body = requestBody
        ).jsonObject
        val payload = root["tokenPayloadExternal"]?.jsonObject
            ?: throw PurchaseNotEntitledException()
        val requestDetails = payload["requestDetails"]?.jsonObject
            ?: throw PurchaseNotEntitledException()
        val requestPackage = requestDetails["requestPackageName"]?.jsonPrimitive?.contentOrNull
        val nonce = requestDetails["nonce"]?.jsonPrimitive?.contentOrNull
        val issuedAt = requestDetails["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L
        val appIntegrity = payload["appIntegrity"]?.jsonObject
            ?: throw PurchaseNotEntitledException()
        val recognizedPackage = appIntegrity["packageName"]?.jsonPrimitive?.contentOrNull == config.packageName
        val recognizedApp = appIntegrity["appRecognitionVerdict"]?.jsonPrimitive?.contentOrNull == "PLAY_RECOGNIZED"
        val licensed = payload["accountDetails"]?.jsonObject
            ?.get("appLicensingVerdict")?.jsonPrimitive?.contentOrNull == "LICENSED"
        val fresh = issuedAt in (System.currentTimeMillis() - MAX_INTEGRITY_AGE_MILLIS)..(System.currentTimeMillis() + CLOCK_SKEW_MILLIS)
        if (requestPackage != config.packageName || nonce != expectedNonce || !recognizedPackage || !recognizedApp || !licensed || !fresh) {
            throw PurchaseNotEntitledException()
        }
    }

    private fun request(method: String, url: String, body: String? = null): kotlinx.serialization.json.JsonElement {
        val accessToken = credentials.refreshAccessToken().tokenValue
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody(JSON_MEDIA_TYPE)) else builder.method(method, null)
        httpClient.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ExternalServiceException("Google Play API returned HTTP ${response.code}")
            }
            return if (responseBody.isBlank()) json.parseToJsonElement("{}") else json.parseToJsonElement(responseBody)
        }
    }

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    companion object {
        private const val PUBLISHER_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"
        private const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        private const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val PURCHASED_STATE = 0
        private const val MAX_INTEGRITY_AGE_MILLIS = 2 * 60 * 1000L
        private const val CLOCK_SKEW_MILLIS = 30 * 1000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
