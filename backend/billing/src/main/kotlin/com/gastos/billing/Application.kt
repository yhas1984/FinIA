package com.gastos.billing

import com.google.cloud.firestore.FirestoreOptions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.event.Level

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, host = "0.0.0.0", port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = BillingConfig.fromEnvironment()
    val firestore = FirestoreOptions.getDefaultInstance()
        .toBuilder()
        .setProjectId(config.projectId)
        .setDatabaseId(config.firestoreDatabaseId)
        .build()
        .service
    val playApi = GooglePlayApiClient(config)
    val service = BillingService(
        config = config,
        playApi = playApi,
        store = FirestoreEntitlementStore(firestore),
        signer = EntitlementSigner(config)
    )
    billingModule(config, service, GoogleOidcReconcileAuthenticator(config.reconcileAudience))
}

fun Application.billingModule(
    config: BillingConfig,
    service: BillingService,
    reconcileAuthenticator: ReconcileAuthenticator = ReconcileAuthenticator { false }
) {
    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ClientInputException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request"))
        }
        exception<PurchaseNotEntitledException> { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Purchase is not entitled"))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Billing service unavailable"))
        }
    }
    routing {
        get("/") {
            call.respond(HealthResponse("ok"))
        }
        get("/healthz") {
            call.respond(HealthResponse("ok"))
        }
        post("/v1/entitlements:verify") {
            val request = call.receive<VerifyEntitlementRequest>()
            call.respond(service.verify(request))
        }
        post("/v1/entitlements:reconcile") {
            val secret = call.request.headers[INTERNAL_SECRET_HEADER]
            if (!constantTimeEquals(config.internalReconcileSecret, secret) ||
                !reconcileAuthenticator.isAuthorized(call.request.headers[HttpHeaders.Authorization])
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            } else {
                call.respond(ReconcileResponse(service.reconcileVoidedPurchases()))
            }
        }
    }

    environment.log.info("FinAI Billing backend configured for ${config.packageName}/${config.productId}")
}

private const val INTERNAL_SECRET_HEADER = "X-Internal-Reconcile-Secret"

private fun constantTimeEquals(expected: String, actual: String?): Boolean =
    actual != null && java.security.MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8)
    )
