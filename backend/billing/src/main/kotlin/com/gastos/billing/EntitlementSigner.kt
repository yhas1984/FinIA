package com.gastos.billing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

interface EntitlementTokenSigner {
    fun issue(record: EntitlementRecord): EntitlementResponse
}

class EntitlementSigner(
    private val config: BillingConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : EntitlementTokenSigner {
    private val privateKey: RSAPrivateKey = parsePrivateKey(config.entitlementPrivateKeyPem)

    override fun issue(record: EntitlementRecord): EntitlementResponse {
        val issuedAt = nowMillis()
        val expiresAt = issuedAt + ENTITLEMENT_LIFETIME_MILLIS
        val token = JWT.create()
            .withKeyId(config.entitlementKeyId)
            .withIssuer(config.entitlementIssuer)
            .withSubject(record.tokenHash)
            .withClaim("packageName", record.packageName)
            .withClaim("productId", record.productId)
            .withClaim("status", record.status)
            .withIssuedAt(Date(issuedAt))
            .withExpiresAt(Date(expiresAt))
            .sign(Algorithm.RSA256(null, privateKey))
        return EntitlementResponse(token, record.status, record.productId, expiresAt)
    }

    private fun parsePrivateKey(pem: String): RSAPrivateKey {
        val encoded = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateKey
    }

    private companion object {
        const val ENTITLEMENT_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
