package com.gastos.billing

import com.google.cloud.firestore.FirestoreOptions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
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
import kotlinx.serialization.SerializationException
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
    billingModule(config, service, GoogleOidcReconcileAuthenticator(config.reconcileAudience), InstallRegistry(FirestoreInstallStore(firestore)))
}

fun Application.billingModule(
    config: BillingConfig,
    service: BillingService,
    reconcileAuthenticator: ReconcileAuthenticator = ReconcileAuthenticator { false },
    installRegistry: InstallRegistry? = null
) {
    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ClientInputException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request", "INVALID_REQUEST"))
        }
        exception<PurchaseNotEntitledException> { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Purchase is not entitled", "PURCHASE_NOT_ENTITLED"))
        }
        exception<UpstreamPlayException> { call, error ->
            // El motivo importa: «token que no vale» no es «servidor caído», y hasta ahora ambos
            // salían como 503 indistinguible.
            val (status, code) = when (error.upstreamStatus) {
                400, 404, 410 -> HttpStatusCode.Forbidden to "PURCHASE_NOT_FOUND"
                401, 403 -> HttpStatusCode.ServiceUnavailable to "UPSTREAM_AUTH_ERROR"
                else -> HttpStatusCode.BadGateway to "UPSTREAM_PLAY_ERROR"
            }
            call.application.log.warn(
                "Play respondió ${error.upstreamStatus} verificando una compra -> $code"
            )
            call.respond(
                status,
                ErrorResponse("Google Play rechazó la verificación (HTTP ${error.upstreamStatus})", code)
            )
        }
        exception<BadRequestException> { call, error ->
            // JSON mal formado o con un campo que no está en el DTO: la lista de campos es cerrada.
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request", "INVALID_REQUEST"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body", "INVALID_REQUEST"))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Billing service unavailable", "SERVICE_UNAVAILABLE"))
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
        post("/v1/installs:register") {
            val registry = installRegistry
            if (registry == null) {
                call.application.log.error("installs:register sin almacén configurado")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Install registry not configured", "REGISTRY_UNAVAILABLE")
                )
            } else {
                call.respond(registry.register(call.receive<RegisterInstallRequest>()))
            }
        }
        post("/v1/entitlements:reconcile") {
            val secret = call.request.headers[INTERNAL_SECRET_HEADER]
            if (!constantTimeEquals(config.internalReconcileSecret, secret) ||
                !reconcileAuthenticator.isAuthorized(call.request.headers[HttpHeaders.Authorization])
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "UNAUTHORIZED"))
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
