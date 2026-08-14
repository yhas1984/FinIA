package com.gastos.billing

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `health and verify endpoint expose the safe contract`() = testApplication {
        val config = testConfig()
        val service = BillingService(
            config = config,
            playApi = FakePlayApi(),
            store = FakeStore(),
            signer = FakeSigner()
        )
        application { billingModule(config, service) }
        client = createClient {
            install(ContentNegotiation) { json() }
        }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val response = client.post("/v1/entitlements:verify") {
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(VerifyEntitlementRequest("com.gastos.ingresos", "finai_premium", "purchase-token"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<EntitlementResponse>().entitlementToken.isNotBlank())
    }

    @Test
    fun `verify rejects an unsupported package`() = testApplication {
        val config = testConfig()
        application { billingModule(config, BillingService(config, FakePlayApi(), FakeStore(), FakeSigner())) }
        client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/entitlements:verify") {
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(VerifyEntitlementRequest("other.package", "finai_premium", "token"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `reconcile requires both scheduler identity and secret`() = testApplication {
        val config = testConfig()
        val service = BillingService(config, FakePlayApi(), FakeStore(), FakeSigner())
        application {
            billingModule(config, service, ReconcileAuthenticator { header -> header == "Bearer valid" })
        }
        client = createClient { install(ContentNegotiation) { json() } }

        val missingIdentity = client.post("/v1/entitlements:reconcile") {
            header("X-Internal-Reconcile-Secret", "secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, missingIdentity.status)

        val authorized = client.post("/v1/entitlements:reconcile") {
            header("Authorization", "Bearer valid")
            header("X-Internal-Reconcile-Secret", "secret")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
    }

    private fun testConfig() = BillingConfig(
        projectId = "finai-501616",
        firestoreDatabaseId = "finai",
        packageName = "com.gastos.ingresos",
        productId = "finai_premium",
        entitlementIssuer = "test",
        entitlementKeyId = "test-key",
        entitlementPrivateKeyPem = TEST_PRIVATE_KEY,
            internalReconcileSecret = "secret",
            requirePlayIntegrity = false,
            reconcileAudience = "https://billing.test"
    )

    private class FakePlayApi : PlayPurchaseGateway {
        override suspend fun verifyPurchase(productId: String, purchaseToken: String) =
            VerifiedPurchase("order-1", 1L, 1)

        override suspend fun acknowledge(productId: String, purchaseToken: String) = Unit
        override suspend fun listVoidedPurchaseTokens() = emptyList<String>()
        override suspend fun verifyPlayIntegrity(token: String, expectedNonce: String) = Unit
    }

    private class FakeStore : EntitlementStore {
        override suspend fun save(record: EntitlementRecord) = true
        override suspend fun status(tokenHash: String): String? = null
        override suspend fun revoke(tokenHash: String) = Unit
    }

    private class FakeSigner : EntitlementTokenSigner {
        override fun issue(record: EntitlementRecord) =
            EntitlementResponse("signed", record.status, record.productId, Long.MAX_VALUE)
    }

    companion object {
        private const val TEST_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n-----END PRIVATE KEY-----"
    }
}
