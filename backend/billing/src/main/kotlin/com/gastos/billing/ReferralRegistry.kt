package com.gastos.billing

import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * Código de referido del PDF gratuito.
 *
 * Para qué existe: el PDF del trimestre se comparte con la gestoría y hasta hoy viajaba anónimo. El
 * `referralCode` que viaja en `exports:record` lo enviaba el cliente, así que no servía como prueba
 * —un cliente modificado podía reclamar el código de otra gestoría—. Aquí el código **se emite en el
 * servidor**, atado a una instalación concreta, y es lo único que hay que citar cuando una gestoría
 * escribe: se busca el código y se sabe qué instalación y qué canal lo generaron.
 *
 * Reglas:
 *  - **Idempotente por `installId`.** El PDF se regenera muchas veces; el código es siempre el mismo.
 *  - **Aleatorio, no derivado.** 6 caracteres de un alfabeto sin letras ambiguas (`0/o`, `1/l`), unos
 *    1.000 millones de combinaciones. No codifica el `installId`: quien lo ve no puede reconstruirlo.
 *  - **Sin datos personales.** El registro solo guarda `installId`, código y fecha.
 */
@Serializable
data class IssueReferralRequest(val installId: String)

@Serializable
data class IssueReferralResponse(
    val code: String,
    val installId: String,
    val createdAt: Long
)

data class ReferralRecord(
    val code: String,
    val installId: String,
    val createdAt: Long
)

interface ReferralStore {
    suspend fun findByInstall(installId: String): ReferralRecord?

    /**
     * Crea el código para esa instalación. Devuelve el registro existente si la instalación ya
     * tenía uno, y `null` si el código estaba ocupado por otra instalación (colisión).
     */
    suspend fun create(installId: String, code: String, now: Long): ReferralRecord?

    suspend fun findByCode(code: String): ReferralRecord?
}

class ReferralRegistry(
    private val store: ReferralStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val generate: () -> String = ::randomCode
) {

    suspend fun issue(request: IssueReferralRequest): IssueReferralResponse {
        if (!INSTALL_ID.matches(request.installId)) {
            throw ClientInputException("installId must be 32 lowercase hex characters")
        }
        store.findByInstall(request.installId)?.let { return it.toResponse() }

        repeat(MAX_ATTEMPTS) {
            val code = generate()
            if (code.length != CODE_LENGTH || !code.all { it in ALPHABET }) {
                error("El generador produjo un código fuera del alfabeto: $code")
            }
            store.create(request.installId, code, now())?.let { return it.toResponse() }
        }
        error("No se pudo emitir un código libre tras $MAX_ATTEMPTS intentos")
    }

    private fun ReferralRecord.toResponse() = IssueReferralResponse(code, installId, createdAt)

    companion object {
        /** Alfabeto sin caracteres que se confunden al leerlos de un papel o de un PDF. */
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        const val CODE_LENGTH = 6

        val INSTALL_ID = Regex("^[0-9a-f]{32}$")

        private const val MAX_ATTEMPTS = 5
        private val random = SecureRandom()

        fun randomCode(): String = buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}

class FirestoreReferralStore(
    private val firestore: Firestore
) : ReferralStore {

    override suspend fun findByInstall(installId: String): ReferralRecord? = withContext(Dispatchers.IO) {
        val index = firestore.collection(INDEX_COLLECTION).document(installId).get().get()
        val code = index.getString("code") ?: return@withContext null
        ReferralRecord(code = code, installId = installId, createdAt = index.getLong("createdAt") ?: 0L)
    }

    override suspend fun create(installId: String, code: String, now: Long): ReferralRecord? =
        withContext(Dispatchers.IO) {
            val index = firestore.collection(INDEX_COLLECTION).document(installId)
            firestore.runTransaction { transaction ->
                val existing = transaction.get(index).get()
                existing.getString("code")?.let { existingCode ->
                    return@runTransaction ReferralRecord(
                        code = existingCode,
                        installId = installId,
                        createdAt = existing.getLong("createdAt") ?: now
                    )
                }
                val codeRef: DocumentReference = firestore.collection(CODE_COLLECTION).document(code)
                if (transaction.get(codeRef).get().exists()) return@runTransaction null

                transaction.set(codeRef, mapOf("code" to code, "installId" to installId, "createdAt" to now))
                transaction.set(index, mapOf("code" to code, "createdAt" to now))
                ReferralRecord(code, installId, now)
            }.get()
        }

    override suspend fun findByCode(code: String): ReferralRecord? = withContext(Dispatchers.IO) {
        val doc = firestore.collection(CODE_COLLECTION).document(code).get().get()
        val installId = doc.getString("installId") ?: return@withContext null
        ReferralRecord(
            code = doc.getString("code") ?: code,
            installId = installId,
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private companion object {
        const val CODE_COLLECTION = "referrals"
        const val INDEX_COLLECTION = "referrals_by_install"
    }
}
