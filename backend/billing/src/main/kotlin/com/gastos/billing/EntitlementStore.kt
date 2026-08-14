package com.gastos.billing

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.security.MessageDigest

interface EntitlementStore {
    suspend fun save(record: EntitlementRecord): Boolean
    suspend fun status(tokenHash: String): String?
    suspend fun revoke(tokenHash: String)
}

class FirestoreEntitlementStore(
    private val firestore: Firestore
) : EntitlementStore {
    override suspend fun save(record: EntitlementRecord): Boolean {
        return withContext(Dispatchers.IO) {
            val reference = firestore.collection(COLLECTION).document(record.tokenHash)
            firestore.runTransaction { transaction ->
                val current = transaction.get(reference).get()
                if (current.getString("status") == "revoked") {
                    false
                } else {
                    transaction.set(reference, record.toMap(), SetOptions.merge())
                    true
                }
            }.get()
        }
    }

    override suspend fun status(tokenHash: String): String? = withContext(Dispatchers.IO) {
        firestore.collection(COLLECTION).document(tokenHash).get().get().getString("status")
    }

    override suspend fun revoke(tokenHash: String) {
        withContext(Dispatchers.IO) {
            firestore.collection(COLLECTION)
                .document(tokenHash)
                .set(
                    mapOf(
                        "tokenHash" to tokenHash,
                        "status" to "revoked",
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
                .get()
        }
    }

    private fun EntitlementRecord.toMap() = mapOf(
        "tokenHash" to tokenHash,
        "packageName" to packageName,
        "productId" to productId,
        "orderId" to orderId,
        "status" to status,
        "updatedAt" to updatedAt
    )

    companion object {
        private const val COLLECTION = "entitlements"
    }
}

fun hashPurchaseToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
