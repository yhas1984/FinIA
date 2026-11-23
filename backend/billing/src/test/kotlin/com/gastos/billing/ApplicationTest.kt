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
        assertEquals("INVALID_REQUEST", response.body<ErrorResponse>().code)
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
        assertEquals("UNAUTHORIZED", missingIdentity.body<ErrorResponse>().code)

        val authorized = client.post("/v1/entitlements:reconcile") {
            header("Authorization", "Bearer valid")
            header("X-Internal-Reconcile-Secret", "secret")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
    }

    @Test
    fun `confirmed revocation and temporary outage have different compatible codes`() {
        listOf(
            Triple(PurchaseNotEntitledException(), HttpStatusCode.Forbidden, "PURCHASE_NOT_ENTITLED"),
            Triple(java.io.IOException("synthetic outage"), HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE")
        ).forEach { (failure, status, code) -> testApplication {
            val config = testConfig()
            application { billingModule(config, BillingService(config, FakePlayApi(failure), FakeStore(), FakeSigner())) }
            client = createClient { install(ContentNegotiation) { json() } }
            val response = client.post("/v1/entitlements:verify") {
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(VerifyEntitlementRequest("com.gastos.ingresos", "finai_premium", "synthetic-token"))
            }
            assertEquals(status,response.status)
            val body = response.body<ErrorResponse>()
            assertEquals(code,body.code)
            assertTrue(body.error.isNotBlank())
        } }
    }

    @Test
    fun `a rejected Play call is distinguishable from an outage`() {
        listOf(
            Triple(404, HttpStatusCode.Forbidden, "PURCHASE_NOT_FOUND"),
            Triple(410, HttpStatusCode.Forbidden, "PURCHASE_NOT_FOUND"),
            Triple(401, HttpStatusCode.ServiceUnavailable, "UPSTREAM_AUTH_ERROR"),
            Triple(403, HttpStatusCode.ServiceUnavailable, "UPSTREAM_AUTH_ERROR"),
            Triple(500, HttpStatusCode.BadGateway, "UPSTREAM_PLAY_ERROR")
        ).forEach { (upstream, status, code) -> testApplication {
            val config = testConfig()
            val failure = UpstreamPlayException(upstream, "synthetic $upstream")
            application { billingModule(config, BillingService(config, FakePlayApi(failure), FakeStore(), FakeSigner())) }
            client = createClient { install(ContentNegotiation) { json() } }
            val response = client.post("/v1/entitlements:verify") {
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(VerifyEntitlementRequest("com.gastos.ingresos", "finai_premium", "synthetic-token"))
            }
            assertEquals(status, response.status, "Play HTTP $upstream")
            val body = response.body<ErrorResponse>()
            assertEquals(code, body.code, "Play HTTP $upstream")
            assertTrue(body.error.contains(upstream.toString()), "el mensaje debe citar el HTTP de Play")
        } }
    }

    @Test
    fun `installs register acepta la peticion y rechaza campos fuera del contrato`() = testApplication {
        val config = testConfig()
        val store = RecordingInstallStore()
        val service = BillingService(config, FakePlayApi(), FakeStore(), FakeSigner())
        application { billingModule(config, service, installRegistry = InstallRegistry(store) { 1_000L }) }
        client = createClient { install(ContentNegotiation) { json() } }

        val ok = client.post("/v1/installs:register") {
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(
                RegisterInstallRequest(
                    installId = "0123456789abcdef0123456789abcdef",
                    utmSource = "finkit",
                    utmCampaign = "cuota-autonomos"
                )
            )
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("install", ok.body<RegisterInstallResponse>().stage)

        // Campo que no está en el DTO: la lista es cerrada y no se guarda nada.
        val extra = client.post("/v1/installs:register") {
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(
                """
                {"installId":"0123456789abcdef0123456789abcdef","utmSource":"finkit","importe":1234.56}
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, extra.status)
        assertEquals("INVALID_REQUEST", extra.body<ErrorResponse>().code)
        assertEquals(1, store.records.size)
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

    private class FakePlayApi(private val failure: Exception? = null) : PlayPurchaseGateway {
        override suspend fun verifyPurchase(productId: String, purchaseToken: String) =
            failure?.let { throw it } ?: VerifiedPurchase("order-1", 1L, 1)

        override suspend fun acknowledge(productId: String, purchaseToken: String) = Unit
        override suspend fun listVoidedPurchaseTokens() = emptyList<String>()
        override suspend fun verifyPlayIntegrity(token: String, expectedNonce: String) = Unit
    }

    private class RecordingInstallStore : InstallStore {
        val records = mutableListOf<InstallRecord>()

        override suspend fun upsert(record: InstallRecord): InstallStoreResult {
            val existed = records.any { it.installId == record.installId }
            records += record
            return InstallStoreResult(existed = existed, stage = record.stage)
        }
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
