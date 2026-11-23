package com.gastos.billing

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Almacén de instalaciones atribuidas a una campaña (`POST /v1/installs:register`).
 *
 * Es deliberadamente pobre en datos: una fila por instalación, parámetros de campaña, versión,
 * idioma y país. Ni importes, ni facturas, ni NIF, ni identificador de publicidad. Si alguien
 * intenta meter un campo nuevo, `kotlinx.serialization` lo rechaza por defecto y la petición
 * se cae con 400: la lista cerrada es parte del contrato, no una comodidad.
 */
interface InstallStore {
    /** Devuelve si el documento ya existía y el `stage` que queda guardado tras la fusión. */
    suspend fun upsert(record: InstallRecord): InstallStoreResult
}

data class InstallStoreResult(val existed: Boolean, val stage: String)

class FirestoreInstallStore(
    private val firestore: Firestore
) : InstallStore {

    override suspend fun upsert(record: InstallRecord): InstallStoreResult = withContext(Dispatchers.IO) {
        val reference = firestore.collection(COLLECTION).document(record.installId)
        firestore.runTransaction { transaction ->
            val current = transaction.get(reference).get()
            val existed = current.exists()
            // El perfil fiscal no se degrada: si este móvil ya lo declaró, un `install` posterior
            // (por ejemplo, reintento tras limpiar caché) no puede devolverlo a la casilla previa.
            val previousStage = current.getString("stage")
            val stage = higherStage(previousStage, record.stage)
            val payload = record.toMap().toMutableMap()
            payload["stage"] = stage
            if (!existed) payload["firstSeenAt"] = record.seenAt
            transaction.set(reference, payload, SetOptions.merge())
            InstallStoreResult(existed = existed, stage = stage)
        }.get()
    }

    private fun higherStage(previous: String?, candidate: String): String {
        val previousRank = STAGE_ORDER.indexOf(previous)
        val candidateRank = STAGE_ORDER.indexOf(candidate)
        return if (previousRank > candidateRank) previous!! else candidate
    }

    private fun InstallRecord.toMap(): Map<String, Any> = buildMap {
        put("installId", installId)
        put("lastSeenAt", seenAt)
        utmSource?.let { put("utmSource", it) }
        utmMedium?.let { put("utmMedium", it) }
        utmCampaign?.let { put("utmCampaign", it) }
        utmContent?.let { put("utmContent", it) }
        utmTerm?.let { put("utmTerm", it) }
        appVersionName?.let { put("appVersionName", it) }
        appVersionCode?.let { put("appVersionCode", it) }
        locale?.let { put("locale", it) }
        country?.let { put("country", it) }
        firstRunAt?.let { put("firstRunAt", it) }
        referrerClickTs?.let { put("referrerClickTs", it) }
        installBeginTs?.let { put("installBeginTs", it) }
    }

    companion object {
        private const val COLLECTION = "installs"

        /** Orden de avance: cada etapa solo puede subir. */
        private val STAGE_ORDER = listOf(InstallRegistry.STAGE_INSTALL, InstallRegistry.STAGE_FISCAL_PROFILE)
    }
}
