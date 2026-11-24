package com.gastos.billing

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Libro de exports: un documento por evento, identificado por el `eventId` que genera el cliente.
 *
 * Por qué existe: la comisión con una gestoría no se puede facturar sin prueba de que el autónomo
 * exportó el trimestre y por qué canal llegó. Hasta hoy ese hecho no se registraba en ningún sitio.
 *
 * Lo que **no** guarda: importes, base imponible, NIF, ni el contenido del fichero. `artifactSha256`
 * identifica el artefacto sin revelarlo y `deliveredTo` dice a qué aplicación se entregó.
 */
interface ExportStore {
    /** Devuelve si el `eventId` ya estaba registrado (reintento de la cola durable). */
    suspend fun save(record: ExportRecord): Boolean
}

class FirestoreExportStore(
    private val firestore: Firestore
) : ExportStore {

    override suspend fun save(record: ExportRecord): Boolean = withContext(Dispatchers.IO) {
        val reference = firestore.collection(COLLECTION).document(record.eventId)
        firestore.runTransaction { transaction ->
            val current = transaction.get(reference).get()
            val existed = current.exists()
            if (!existed) {
                transaction.set(reference, record.toMap(), SetOptions.merge())
            }
            existed
        }.get()
    }

    private fun ExportRecord.toMap(): Map<String, Any> = buildMap {
        put("eventId", eventId)
        put("installId", installId)
        put("period", period)
        put("format", format)
        put("receivedAt", receivedAt)
        profile?.let { put("profile", it) }
        referralCode?.let { put("referralCode", it) }
        artifactSha256?.let { put("artifactSha256", it) }
        deliveredTo?.let { put("deliveredTo", it) }
        clientCreatedAt?.let { put("clientCreatedAt", it) }
    }

    private companion object {
        const val COLLECTION = "exports"
    }
}
