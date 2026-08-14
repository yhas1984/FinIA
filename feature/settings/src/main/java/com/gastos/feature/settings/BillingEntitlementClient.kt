package com.gastos.feature.settings

import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import javax.inject.Inject

class BillingEntitlementClient @Inject constructor(
    @ApplicationContext context: Context
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val integrityManager: IntegrityManager = IntegrityManagerFactory.create(context)

    private val isConfigured: Boolean
        get() = BuildConfig.BILLING_BACKEND_URL.isNotBlank() &&
            BuildConfig.BILLING_ENTITLEMENT_PUBLIC_KEY_PEM.isNotBlank() &&
            BuildConfig.BILLING_ENTITLEMENT_ISSUER.isNotBlank() &&
            BuildConfig.BILLING_ENTITLEMENT_KEY_ID.isNotBlank()

    val isEnabled: Boolean
        get() = isConfigured

    val isRequired: Boolean
        get() = BuildConfig.BILLING_BACKEND_REQUIRED

    suspend fun verifyPurchase(productId: String, purchaseToken: String): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext !isRequired
        val purchaseHash = hashPurchaseToken(purchaseToken)
        val playIntegrityToken = if (BuildConfig.BILLING_PLAY_INTEGRITY_ENABLED) {
            requestPlayIntegrityToken(purchaseHash) ?: return@withContext false
        } else {
            null
        }
        val body = JSONObject()
            .put("packageName", BuildConfig.BILLING_PACKAGE_NAME)
            .put("productId", productId)
            .put("purchaseToken", purchaseToken)
            .apply { playIntegrityToken?.let { put("playIntegrityToken", it) } }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(BuildConfig.BILLING_BACKEND_URL.trimEnd('/') + "/v1/entitlements:verify")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val token = JSONObject(response.body?.string().orEmpty()).optString("entitlementToken")
                verifyToken(token, productId, purchaseToken)
            }
        }.getOrDefault(false)
    }

    private suspend fun requestPlayIntegrityToken(nonce: String): String? = suspendCancellableCoroutine { continuation ->
        integrityManager.requestIntegrityToken(
            IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()
        ).addOnSuccessListener { response ->
            if (continuation.isActive) continuation.resume(response.token())
        }.addOnFailureListener {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun verifyToken(token: String, productId: String, purchaseToken: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        val publicKey = parsePublicKey(BuildConfig.BILLING_ENTITLEMENT_PUBLIC_KEY_PEM)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        val signatureValid = verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        if (!signatureValid) return false
        val header = JSONObject(decodeSegment(parts[0]))
        val payload = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        return header.optString("kid") == BuildConfig.BILLING_ENTITLEMENT_KEY_ID &&
            payload.optString("iss") == BuildConfig.BILLING_ENTITLEMENT_ISSUER &&
            payload.optString("sub") == hashPurchaseToken(purchaseToken) &&
            payload.optString("packageName") == BuildConfig.BILLING_PACKAGE_NAME &&
            payload.optString("productId") == productId &&
            payload.optString("status") == "active" &&
            payload.optLong("exp", 0L) > System.currentTimeMillis() / 1000L
    }

    private fun decodeSegment(segment: String): String =
        String(Base64.decode(segment, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    private fun hashPurchaseToken(token: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun parsePublicKey(pem: String) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(
            Base64.decode(
                pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s"), ""),
                Base64.DEFAULT
            )
        )
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
