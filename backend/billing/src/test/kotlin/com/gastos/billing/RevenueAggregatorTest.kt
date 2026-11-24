package com.gastos.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El informe de ingresos solo vale si sus cifras se pueden reproducir a mano. Cada prueba fija una
 * regla que, mal puesta, haría decir al panel que se ha ganado dinero que no existe (o al revés).
 */
class RevenueAggregatorTest {

    private val aggregator = RevenueAggregator(
        grossPriceCents = 599,
        playFeeBasisPoints = 1500,
        currency = "EUR"
    )

    @Test
    fun `el neto por venta descuenta la comision de Play`() {
        assertEquals(509, aggregator.netPerUnit())

        val other = RevenueAggregator(grossPriceCents = 1000, playFeeBasisPoints = 0, currency = "EUR")
        assertEquals(1000, other.netPerUnit())
    }

    @Test
    fun `sin datos el informe es cero y lo declara`() {
        val report = aggregator.build(RevenueSnapshot(emptyList(), emptyList(), emptyList()), now = 10L)

        assertEquals(10L, report.generatedAt)
        assertEquals(0, report.installs.total)
        assertEquals(0, report.purchases.paid)
        assertEquals(0L, report.purchases.grossCents)
        assertEquals(0, report.exports.total)
        assertEquals(0.0, report.conversion.installToPaidPct)
        assertTrue(report.alerts.any { it.contains("Sin compras activas") })
    }

    @Test
    fun `una compra atribuida suma por el canal de su instalacion`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = listOf(install("a".repeat(32), utmSource = "finkit")),
                entitlements = listOf(entitlement("token-1", installId = "a".repeat(32))),
                exports = emptyList()
            )
        )

        assertEquals(1, report.purchases.paid)
        assertEquals(1, report.purchases.attributed)
        assertEquals(0, report.purchases.unattributed)
        assertEquals(mapOf("finkit" to 1), report.purchases.byChannel)
        assertEquals(599L, report.purchases.grossCents)
        assertEquals(509L, report.purchases.netCents)
        assertEquals(100.0, report.conversion.installToPaidPct)
        assertTrue(report.alerts.isEmpty(), "sin huecos no debe haber avisos: ${report.alerts}")
    }

    @Test
    fun `una compra sin installId no se reparte por canal`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = listOf(install("a".repeat(32), utmSource = "finkit")),
                entitlements = listOf(entitlement("token-1", installId = null)),
                exports = emptyList()
            )
        )

        assertEquals(1, report.purchases.paid)
        assertEquals(0, report.purchases.attributed)
        assertEquals(1, report.purchases.unattributed)
        assertEquals(mapOf(RevenueAggregator.UNATTRIBUTED to 1), report.purchases.byChannel)
        assertTrue(report.alerts.any { it.contains("sin installId") })
    }

    @Test
    fun `un installId que no existe no se cuenta como organico`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = emptyList(),
                entitlements = listOf(entitlement("token-1", installId = "b".repeat(32))),
                exports = emptyList()
            )
        )

        assertEquals(1, report.purchases.unknownInstallation)
        assertEquals(0, report.purchases.attributed)
        assertEquals(0.0, report.conversion.installToPaidPct)
        assertTrue(report.alerts.any { it.contains("no está en `installs`") })
    }

    @Test
    fun `las revocadas se cuentan pero no suman ingresos`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = listOf(install("a".repeat(32), utmSource = null)),
                entitlements = listOf(
                    entitlement("token-1", installId = "a".repeat(32)),
                    entitlement("token-2", installId = "a".repeat(32), status = "revoked")
                ),
                exports = emptyList()
            )
        )

        assertEquals(1, report.purchases.paid)
        assertEquals(1, report.purchases.revoked)
        assertEquals(599L, report.purchases.grossCents)
        // Sin utmSource la instalación es orgánica, no «sin atribuir».
        assertEquals(mapOf(RevenueAggregator.ORGANIC to 1), report.purchases.byChannel)
    }

    @Test
    fun `los exports se agrupan por gestoria y avisan de los que no la tienen`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = emptyList(),
                entitlements = emptyList(),
                exports = listOf(
                    export("a".repeat(32), referralCode = "gestoriavalencia", deliveredTo = "com.google.android.gm"),
                    export("a".repeat(32), referralCode = "gestoriavalencia", deliveredTo = "com.whatsapp"),
                    export("a".repeat(32), referralCode = null, deliveredTo = null)
                )
            )
        )

        assertEquals(3, report.exports.total)
        assertEquals(1, report.exports.uniqueInstallations)
        assertEquals(mapOf("gestoriavalencia" to 2), report.exports.byReferralCode)
        assertEquals(1, report.exports.withoutReferralCode)
        assertEquals(mapOf("com.google.android.gm" to 1, "com.whatsapp" to 1), report.exports.byDeliveredTo)
        assertTrue(report.alerts.any { it.contains("sin referralCode") })
    }

    @Test
    fun `una lectura truncada invalida el informe como cifra cerrada`() {
        val report = aggregator.build(
            RevenueSnapshot(
                installs = emptyList(),
                entitlements = emptyList(),
                exports = emptyList(),
                truncated = setOf("entitlements")
            )
        )

        assertTrue(report.alerts.any { it.contains("truncada") && it.contains("entitlements") })
    }

    private fun install(installId: String, utmSource: String?) = InstallRecord(
        installId = installId,
        stage = InstallRegistry.STAGE_INSTALL,
        utmSource = utmSource,
        utmMedium = null,
        utmCampaign = null,
        utmContent = null,
        utmTerm = null,
        appVersionName = "1.9.14",
        appVersionCode = 44,
        locale = "es-ES",
        country = "ES",
        firstRunAt = 1L,
        referrerClickTs = null,
        installBeginTs = null,
        seenAt = 2L
    )

    private fun entitlement(tokenHash: String, installId: String?, status: String = "active") =
        EntitlementRecord(
            tokenHash = tokenHash,
            packageName = "com.gastos.ingresos",
            productId = "finai_premium",
            orderId = "GPA.$tokenHash",
            status = status,
            updatedAt = 3L,
            installId = installId
        )

    private fun export(
        installId: String,
        referralCode: String?,
        deliveredTo: String?,
        profile: String? = "ledger"
    ) = ExportRecord(
        eventId = "evt-$installId-$referralCode-$deliveredTo",
        installId = installId,
        period = "2026-4T",
        format = "csv",
        profile = profile,
        referralCode = referralCode,
        artifactSha256 = null,
        deliveredTo = deliveredTo,
        clientCreatedAt = null,
        receivedAt = 4L
    )
}
