package com.gastos.billing

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Informe de ingresos del backend.
 *
 * Por qué existe: el backend ya guarda las tres piezas del negocio —instalaciones con su canal,
 * compras verificadas con su `installId` y exports con su `referralCode`— pero hasta hoy nadie
 * podía leerlas juntas. Sin esto, «¿cuánto hemos ingresado y por qué canal?» se contestaba de
 * memoria, y la comisión con una gestoría no tenía cifra que enseñar.
 *
 * Lo que **no** hace: no inventa. Si una compra no trae `installId` (clientes anteriores a vc44),
 * la cuenta en `unattributed` y lo dice en `alerts`, en vez de repartirla por canal.
 *
 * El precio no viene de Play —la API de compras no lo devuelve en la verificación— así que se
 * configura por entorno (`PREMIUM_PRICE_CENTS`) y lleva la comisión de Play (`PLAY_FEE_BPS`,
 * 15 % por defecto) para que el neto sea el que de verdad entra en cuenta.
 */
@Serializable
data class RevenueReport(
    val generatedAt: Long,
    val currency: String,
    val price: PriceSnapshot,
    val installs: InstallTotals,
    val purchases: PurchaseTotals,
    val exports: ExportTotals,
    val conversion: ConversionTotals,
    val alerts: List<String>
)

@Serializable
data class PriceSnapshot(
    val grossCents: Long,
    val playFeeBasisPoints: Int,
    val netCents: Long
)

@Serializable
data class InstallTotals(
    val total: Int,
    val fiscalProfiles: Int,
    /** Instalaciones por canal (`utmSource`) y `organico` para las que llegaron sin campaña. */
    val bySource: Map<String, Int>
)

@Serializable
data class PurchaseTotals(
    /** Compras con `status = active`: las que dan derecho a Premium. */
    val paid: Int,
    /** Compras revocadas (reembolso o `voided purchases`). No suman ingresos. */
    val revoked: Int,
    val grossCents: Long,
    val netCents: Long,
    /** Compras que traen `installId` y ese `installId` existe en el registro de instalaciones. */
    val attributed: Int,
    val byChannel: Map<String, Int>,
    /** Compras sin `installId`: se sabe que pagaron, no por qué canal llegaron. */
    val unattributed: Int,
    /** Compras con `installId` que no aparece en `installs` (registro perdido o previo). */
    val unknownInstallation: Int
)

@Serializable
data class ExportTotals(
    val total: Int,
    val uniqueInstallations: Int,
    /** Exports por código de gestoría: es la base facturable de la comisión (ingreso pasivo). */
    val byReferralCode: Map<String, Int>,
    val byDeliveredTo: Map<String, Int>,
    val byProfile: Map<String, Int>,
    /** Exports sin `referralCode`: no hay a quién atribuir la comisión. */
    val withoutReferralCode: Int
)

@Serializable
data class ConversionTotals(
    /** Compras pagadas por cada 100 instalaciones registradas. 0 si aún no hay instalaciones. */
    val installToPaidPct: Double
)

/** Fotografía leída de Firestore. `truncated` nombra las colecciones que llegaron al límite. */
data class RevenueSnapshot(
    val installs: List<InstallRecord>,
    val entitlements: List<EntitlementRecord>,
    val exports: List<ExportRecord>,
    val truncated: Set<String> = emptySet()
)

interface RevenueSource {
    suspend fun snapshot(): RevenueSnapshot
}

/**
 * Agregación pura: se puede probar sin Firestore y sin red. Todo lo que decide una cifra está aquí.
 */
class RevenueAggregator(
    private val grossPriceCents: Long,
    private val playFeeBasisPoints: Int,
    private val currency: String
) {

    fun build(snapshot: RevenueSnapshot, now: Long = System.currentTimeMillis()): RevenueReport {
        val paid = snapshot.entitlements.filter { it.status == STATUS_ACTIVE }
        val revoked = snapshot.entitlements.size - paid.size
        val installsById = snapshot.installs.associateBy { it.installId }

        val channels = paid.groupingBy { entitlement ->
            val installId = entitlement.installId
            when {
                installId == null -> UNATTRIBUTED
                else -> installsById[installId]?.utmSource?.takeIf { it.isNotBlank() } ?: ORGANIC
            }
        }.eachCount()

        val unattributed = paid.count { it.installId == null }
        val unknownInstallation = paid.count { it.installId != null && !installsById.containsKey(it.installId) }
        val attributed = paid.size - unattributed - unknownInstallation

        val installsBySource = snapshot.installs.groupingBy {
            it.utmSource?.takeIf { source -> source.isNotBlank() } ?: ORGANIC
        }.eachCount()

        val exportsWithoutReferral = snapshot.exports.count { it.referralCode.isNullOrBlank() }

        val alerts = buildList {
            if (paid.isEmpty()) {
                add("Sin compras activas: el embudo todavía no ha producido ingresos.")
            }
            if (unattributed > 0) {
                add("$unattributed compra(s) sin installId: se cobró, pero no se sabe por qué canal llegó.")
            }
            if (unknownInstallation > 0) {
                add("$unknownInstallation compra(s) con installId que no está en `installs`.")
            }
            if (exportsWithoutReferral > 0) {
                add("$exportsWithoutReferral export(s) sin referralCode: no hay gestoría a la que facturar comisión.")
            }
            snapshot.truncated.sorted().forEach { collection ->
                add("Lectura truncada en `$collection` (límite $READ_LIMIT): el informe es parcial.")
            }
        }

        val installCount = snapshot.installs.size
        val conversion = if (installCount == 0) 0.0 else {
            (paid.size * 10000.0 / installCount).toLong() / 100.0
        }

        return RevenueReport(
            generatedAt = now,
            currency = currency,
            price = PriceSnapshot(
                grossCents = grossPriceCents,
                playFeeBasisPoints = playFeeBasisPoints,
                netCents = netPerUnit()
            ),
            installs = InstallTotals(
                total = installCount,
                fiscalProfiles = snapshot.installs.count { it.stage == InstallRegistry.STAGE_FISCAL_PROFILE },
                bySource = installsBySource
            ),
            purchases = PurchaseTotals(
                paid = paid.size,
                revoked = revoked,
                grossCents = paid.size * grossPriceCents,
                netCents = paid.size * netPerUnit(),
                attributed = attributed,
                byChannel = channels,
                unattributed = unattributed,
                unknownInstallation = unknownInstallation
            ),
            exports = ExportTotals(
                total = snapshot.exports.size,
                uniqueInstallations = snapshot.exports.map { it.installId }.distinct().size,
                byReferralCode = snapshot.exports
                    .mapNotNull { it.referralCode?.takeIf(String::isNotBlank) }
                    .groupingBy { it }
                    .eachCount(),
                byDeliveredTo = snapshot.exports
                    .mapNotNull { it.deliveredTo?.takeIf(String::isNotBlank) }
                    .groupingBy { it }
                    .eachCount(),
                byProfile = snapshot.exports
                    .mapNotNull { it.profile?.takeIf(String::isNotBlank) }
                    .groupingBy { it }
                    .eachCount(),
                withoutReferralCode = exportsWithoutReferral
            ),
            conversion = ConversionTotals(installToPaidPct = conversion),
            alerts = alerts
        )
    }

    /** Neto por venta con la comisión de Play, en céntimos y redondeado hacia abajo. */
    fun netPerUnit(): Long =
        grossPriceCents * (BASIS_POINTS - playFeeBasisPoints.coerceIn(0, BASIS_POINTS)) / BASIS_POINTS

    companion object {
        const val UNATTRIBUTED = "sin-atribuir"
        const val ORGANIC = "organico"
        private const val STATUS_ACTIVE = "active"
        private const val BASIS_POINTS = 10_000
        private const val READ_LIMIT = 5_000
    }
}

/**
 * Lectura de las tres colecciones. Es deliberadamente simple: un `get()` con límite, porque con el
 * volumen de un producto que empieza (cientos de documentos) una query agregada de Firestore sería
 * más cara y más difícil de auditar. Si algún día hay 5.000 compras, el propio informe lo avisa.
 */
class FirestoreRevenueSource(
    private val firestore: Firestore,
    private val limit: Int = 5_000
) : RevenueSource {

    override suspend fun snapshot(): RevenueSnapshot = withContext(Dispatchers.IO) {
        val truncated = mutableSetOf<String>()

        val installDocs = read(COLLECTION_INSTALLS, truncated)
        val entitlementDocs = read(COLLECTION_ENTITLEMENTS, truncated)
        val exportDocs = read(COLLECTION_EXPORTS, truncated)

        RevenueSnapshot(
            installs = installDocs.mapNotNull { it.toInstallRecord() },
            entitlements = entitlementDocs.mapNotNull { it.toEntitlementRecord() },
            exports = exportDocs.mapNotNull { it.toExportRecord() },
            truncated = truncated
        )
    }

    private fun read(collection: String, truncated: MutableSet<String>): List<DocumentSnapshot> {
        val docs = firestore.collection(collection).limit(limit).get().get().documents
        if (docs.size >= limit) truncated += collection
        return docs
    }

    private fun DocumentSnapshot.toInstallRecord(): InstallRecord? {
        val installId = getString("installId") ?: id
        return InstallRecord(
            installId = installId,
            stage = getString("stage") ?: InstallRegistry.STAGE_INSTALL,
            utmSource = getString("utmSource"),
            utmMedium = getString("utmMedium"),
            utmCampaign = getString("utmCampaign"),
            utmContent = getString("utmContent"),
            utmTerm = getString("utmTerm"),
            appVersionName = getString("appVersionName"),
            appVersionCode = getLong("appVersionCode")?.toInt(),
            locale = getString("locale"),
            country = getString("country"),
            firstRunAt = getLong("firstRunAt"),
            referrerClickTs = getLong("referrerClickTs"),
            installBeginTs = getLong("installBeginTs"),
            seenAt = getLong("lastSeenAt") ?: getLong("firstSeenAt") ?: 0L
        )
    }

    private fun DocumentSnapshot.toEntitlementRecord(): EntitlementRecord? {
        val tokenHash = getString("tokenHash") ?: id
        val status = getString("status") ?: return null
        return EntitlementRecord(
            tokenHash = tokenHash,
            packageName = getString("packageName") ?: "",
            productId = getString("productId") ?: "",
            orderId = getString("orderId"),
            status = status,
            updatedAt = getLong("updatedAt") ?: 0L,
            installId = getString("installId")
        )
    }

    private fun DocumentSnapshot.toExportRecord(): ExportRecord? {
        val installId = getString("installId") ?: return null
        return ExportRecord(
            eventId = getString("eventId") ?: id,
            installId = installId,
            period = getString("period") ?: getString("quarter") ?: "",
            format = getString("format") ?: "",
            profile = getString("profile"),
            referralCode = getString("referralCode"),
            artifactSha256 = getString("artifactSha256"),
            deliveredTo = getString("deliveredTo"),
            clientCreatedAt = getLong("clientCreatedAt"),
            receivedAt = getLong("receivedAt") ?: 0L
        )
    }

    private companion object {
        const val COLLECTION_INSTALLS = "installs"
        const val COLLECTION_ENTITLEMENTS = "entitlements"
        const val COLLECTION_EXPORTS = "exports"
    }
}
