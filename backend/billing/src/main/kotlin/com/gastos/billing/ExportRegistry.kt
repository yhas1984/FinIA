package com.gastos.billing

/**
 * Registro del hecho «el autónomo exportó el trimestre».
 *
 * Reglas, y el motivo de cada una:
 *  - **Idempotente por `eventId`.** El cliente lo genera y la cola durable puede reintentar sin red:
 *    el mismo evento no se cuenta dos veces. Es el caso C2 del plan de QA.
 *  - **Sin importes.** El DTO es una lista cerrada de campos: cualquier campo inesperado devuelve 400
 *    en lugar de guardarse (la misma barrera que en `installs:register`).
 *  - **`deliveredTo` es el paquete de la app elegida, no una promesa de entrega.** La app sabe que
 *    entregó el fichero a una aplicación concreta; no sabe si el mensaje se envió ni si el gestor lo
 *    abrió. El contrato no puede prometer más de lo que ocurre en el dispositivo.
 *  - **`referralCode` lo emite el servidor.** Aquí solo se valida su forma; si viajara libre en una
 *    URL, dos gestorías podrían reclamar el mismo lead y el libro dejaría de valer como prueba.
 */
class ExportRegistry(
    private val store: ExportStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    suspend fun record(request: RecordExportRequest): RecordExportResponse {
        val period = resolvePeriod(request)
        validate(request, period)
        val record = ExportRecord(
            eventId = request.eventId,
            installId = request.installId,
            period = period,
            format = request.format,
            profile = request.profile,
            referralCode = request.referralCode,
            artifactSha256 = request.artifactSha256,
            deliveredTo = request.deliveredTo,
            clientCreatedAt = request.createdAt,
            receivedAt = now()
        )
        val duplicate = store.save(record)
        return RecordExportResponse(status = "ok", duplicate = duplicate)
    }

    /**
     * `period` es el nombre bueno; `quarter` es el alias que ya está en la calle. Si llegan los dos
     * y no coinciden, la petición se rechaza: preferir uno en silencio guardaría un periodo que el
     * cliente no pidió.
     */
    private fun resolvePeriod(request: RecordExportRequest): String {
        val canonical = request.period?.trim()?.takeIf(String::isNotEmpty)
        val legacy = request.quarter?.trim()?.takeIf(String::isNotEmpty)
        if (canonical != null && legacy != null && canonical != legacy) {
            throw ClientInputException("period and quarter disagree")
        }
        return canonical ?: legacy ?: throw ClientInputException("period is required")
    }

    private fun validate(request: RecordExportRequest, period: String) {
        if (!EVENT_ID.matches(request.eventId)) throw ClientInputException("eventId must be a UUID")
        if (!INSTALL_ID.matches(request.installId)) {
            throw ClientInputException("installId must be 32 lowercase hex characters")
        }
        if (!PERIOD.matches(period)) {
            throw ClientInputException("period must look like 2026-4T or 2026-01-01_2026-03-31")
        }
        if (request.format !in FORMATS) throw ClientInputException("Unsupported format")
        request.profile?.let {
            if (it !in PROFILES) throw ClientInputException("Unsupported profile")
        }
        request.referralCode?.let {
            if (!REFERRAL_CODE.matches(it)) throw ClientInputException("Unsupported referralCode")
        }
        request.artifactSha256?.let {
            if (!SHA256.matches(it)) throw ClientInputException("artifactSha256 must be 64 lowercase hex characters")
        }
        request.deliveredTo?.let {
            if (!ANDROID_PACKAGE.matches(it)) throw ClientInputException("deliveredTo must be an Android package name")
        }
        request.createdAt?.let {
            if (it < 0) throw ClientInputException("createdAt must be non-negative")
        }
    }

    companion object {
        val FORMATS = setOf("pdf", "csv")
        val PROFILES = setOf("ledger", "summary", "standard")

        private val EVENT_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val INSTALL_ID = Regex("^[0-9a-f]{32}$")

        /** Trimestre (`2026-4T`) o rango de fechas (`2026-01-01_2026-03-31`). */
        private val PERIOD = Regex("^(?:[0-9]{4}-[1-4]T|[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4}-[0-9]{2}-[0-9]{2})$")
        private val REFERRAL_CODE = Regex("^[a-z0-9]{6,32}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ANDROID_PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
