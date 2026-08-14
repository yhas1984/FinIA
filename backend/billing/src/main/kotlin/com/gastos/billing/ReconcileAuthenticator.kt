package com.gastos.billing

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun interface ReconcileAuthenticator {
    suspend fun isAuthorized(authorizationHeader: String?): Boolean
}

class GoogleOidcReconcileAuthenticator(
    audience: String
) : ReconcileAuthenticator {
    private val logger = LoggerFactory.getLogger(GoogleOidcReconcileAuthenticator::class.java)
    private val verifier = GoogleIdTokenVerifier.Builder(
        GoogleNetHttpTransport.newTrustedTransport(),
        GsonFactory.getDefaultInstance()
    )
        .setAudience(listOf(audience))
        .build()

    override suspend fun isAuthorized(authorizationHeader: String?): Boolean = withContext(Dispatchers.IO) {
        val token = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@withContext false
        val verified = runCatching { verifier.verify(token) }
            .onFailure { logger.warn("Reconcile OIDC verification failed: {}", it.javaClass.simpleName) }
            .getOrNull()
        if (verified == null) {
            logger.warn("Reconcile OIDC token rejected")
            false
        } else {
            logger.debug("Reconcile OIDC token accepted")
            true
        }
    }
}
