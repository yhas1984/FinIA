package com.gastos.billing

/**
 * Registro de instalaciones atribuidas a una campaña.
 *
 * Para qué existe: `finkit.eu` enlaza a Play con `referrer=utm_source%3Dfinkit…` y la app lee ese
 * referrer con `InstallAttribution`, pero hasta hoy el dato moría en el `SharedPreferences` de cada
 * teléfono. Sin este registro no se puede responder «cuántos instalaron desde esta página y cuántos
 * activaron el perfil fiscal»: es la única métrica que decide si tiene sentido llamar a gestorías.
 *
 * Reglas:
 *  - **Idempotente por `installId`.** La app puede reintentar en cada arranque; el documento se
 *    fusiona, nunca se duplica, y `firstSeenAt` se escribe una sola vez.
 *  - **`stage` solo avanza** (`install` → `fiscal_profile`). Un reintento tardío no degrada.
 *  - **Nada de dinero.** El DTO es una lista cerrada de campos; cualquier campo inesperado hace que
 *    la petición falle con 400 en lugar de guardarse silenciosamente.
 */
class InstallRegistry(
    private val store: InstallStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    suspend fun register(request: RegisterInstallRequest): RegisterInstallResponse {
        validate(request)
        val record = InstallRecord(
            installId = request.installId,
            stage = request.stage,
            utmSource = clean(request.utmSource),
            utmMedium = clean(request.utmMedium),
            utmCampaign = clean(request.utmCampaign),
            utmContent = clean(request.utmContent),
            utmTerm = clean(request.utmTerm),
            appVersionName = clean(request.appVersionName, MAX_VERSION_LENGTH),
            appVersionCode = request.appVersionCode,
            locale = clean(request.locale, MAX_LOCALE_LENGTH),
            country = clean(request.country, MAX_LOCALE_LENGTH),
            firstRunAt = request.firstRunAt,
            referrerClickTs = request.referrerClickTs,
            installBeginTs = request.installBeginTs,
            seenAt = now()
        )
        val result = store.upsert(record)
        return RegisterInstallResponse(
            status = "ok",
            stage = result.stage,
            duplicate = result.existed
        )
    }

    private fun validate(request: RegisterInstallRequest) {
        if (!INSTALL_ID.matches(request.installId)) {
            throw ClientInputException("installId must be 32 lowercase hex characters")
        }
        if (request.stage !in STAGES) {
            throw ClientInputException("Unsupported stage")
        }
        request.appVersionCode?.let {
            if (it < 0) throw ClientInputException("appVersionCode must be non-negative")
        }
        listOf(request.firstRunAt, request.referrerClickTs, request.installBeginTs).forEach {
            if (it != null && it < 0) throw ClientInputException("Timestamps must be non-negative")
        }
        request.country?.let {
            if (!COUNTRY.matches(it)) throw ClientInputException("country must be an ISO-3166 alpha-2 code")
        }
        request.locale?.let {
            if (!LOCALE.matches(it)) throw ClientInputException("locale must look like es-ES")
        }
    }

    /**
     * Los parámetros de campaña llegan de una URL pública: se recortan y se les quitan los
     * caracteres de control. No se rechazan por longitud —perder el dato sería peor— pero tampoco
     * se guarda una cadena de 10 KB.
     */
    private fun clean(value: String?, maxLength: Int = MAX_UTM_LENGTH): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.filter { it >= ' ' && it != '\u007F' }.trim().take(maxLength)
        return cleaned.ifBlank { null }
    }

    companion object {
        const val STAGE_INSTALL = "install"
        const val STAGE_FISCAL_PROFILE = "fiscal_profile"

        val STAGES = setOf(STAGE_INSTALL, STAGE_FISCAL_PROFILE)

        private val INSTALL_ID = Regex("^[0-9a-f]{32}$")
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val LOCALE = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?$")

        private const val MAX_UTM_LENGTH = 200
        private const val MAX_VERSION_LENGTH = 40
        private const val MAX_LOCALE_LENGTH = 16
    }
}
