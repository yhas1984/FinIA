package com.gastos.billing

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64

/**
 * Libro de exports: un documento por evento, identificado por el `eventId` que genera el cliente,
 * más un marcador por **hecho comercial**.
 *
 * Por qué dos cosas y no una: el hecho que se factura es *(cliente, trimestre, gestoría)*. Mandar el
 * PDF y luego el CSV del mismo trimestre, reenviarlo «por si acaso» o reexportarlo tras corregir un
 * dato son envíos distintos del mismo hecho; cambiar de gestoría es un hecho nuevo. Por eso el evento
 * guarda `firstFact` y el marcador se crea una sola vez por `installId + period + referralCode`.
 *
 * Lo que **no** guarda: importes, base imponible, NIF, ni el contenido del fichero. `artifactSha256`
 * identifica el artefacto sin revelarlo y `deliveredTo` dice a qué aplicación se entregó.
 */
interface ExportStore {
    suspend fun save(record: ExportRecord): ExportStoreResult
}

data class ExportStoreResult(val existed: Boolean, val firstFact: Boolean)

class FirestoreExportStore(
    private val firestore: Firestore
) : ExportStore {

    override suspend fun save(record: ExportRecord): ExportStoreResult = withContext(Dispatchers.IO) {
        val eventRef = firestore.collection(EVENTS).document(record.eventId)
        val factRef = record.referralCode?.let {
            firestore.collection(FACTS).document(factKey(record))
        }

        firestore.runTransaction { transaction ->
            // Todas las lecturas antes de cualquier escritura, como exige Firestore.
            val existing = transaction.get(eventRef).get()
            if (existing.exists()) {
                return@runTransaction ExportStoreResult(
                    existed = true,
                    firstFact = existing.getBoolean("firstFact") ?: false
                )
            }
            // Sin código de gestoría no hay hecho comercial: es telemetría y no consume el hecho.
            val factAlreadyExists = factRef?.let { transaction.get(it).get().exists() } ?: true
            val firstFact = factRef != null && !factAlreadyExists

            if (firstFact) {
                transaction.set(
                    factRef!!,
                    mapOf(
                        "installId" to record.installId,
                        "period" to record.period,
                        "referralCode" to record.referralCode,
                        "createdAt" to record.receivedAt
                    )
                )
            }
            transaction.set(eventRef, record.copy(firstFact = firstFact).toMap(), SetOptions.merge())
            ExportStoreResult(existed = false, firstFact = firstFact)
        }.get()
    }

    private fun factKey(record: ExportRecord): String {
        val raw = "${record.installId}|${record.period}|${record.referralCode}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun ExportRecord.toMap(): Map<String, Any> = buildMap {
        put("eventId", eventId)
        put("installId", installId)
        put("period", period)
        put("format", format)
        put("receivedAt", receivedAt)
        put("firstFact", firstFact)
        profile?.let { put("profile", it) }
        referralCode?.let { put("referralCode", it) }
        artifactSha256?.let { put("artifactSha256", it) }
        deliveredTo?.let { put("deliveredTo", it) }
        clientCreatedAt?.let { put("clientCreatedAt", it) }
    }

    private companion object {
        const val EVENTS = "exports"
        const val FACTS = "export_facts"
    }
}
