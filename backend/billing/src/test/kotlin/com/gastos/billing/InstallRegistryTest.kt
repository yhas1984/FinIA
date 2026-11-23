package com.gastos.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallRegistryTest {

    @Test
    fun `register es idempotente por install id y guarda la campana`() = runTest {
        val store = FakeInstallStore()
        val registry = InstallRegistry(store) { 1_000L }

        val first = registry.register(
            RegisterInstallRequest(
                installId = ID,
                utmSource = "finkit",
                utmCampaign = "cuota-autonomos",
                appVersionName = "1.9.13",
                appVersionCode = 43,
                locale = "es-ES",
                country = "ES"
            )
        )
        assertEquals("ok", first.status)
        assertEquals(InstallRegistry.STAGE_INSTALL, first.stage)
        assertEquals(false, first.duplicate)

        val second = registry.register(RegisterInstallRequest(installId = ID, utmSource = "finkit"))
        assertEquals(true, second.duplicate)
        assertEquals(1, store.saved.size, "un installId no puede generar dos documentos")

        val record = store.saved.getValue(ID)
        assertEquals("finkit", record.utmSource)
        assertEquals("cuota-autonomos", record.utmCampaign)
        assertEquals(43, record.appVersionCode)
        assertEquals("ES", record.country)
    }

    @Test
    fun `el stage solo avanza y no se degrada con un reintento tardio`() = runTest {
        val store = FakeInstallStore()
        val registry = InstallRegistry(store) { 1_000L }

        registry.register(RegisterInstallRequest(installId = ID))
        val fiscal = registry.register(RegisterInstallRequest(installId = ID, stage = InstallRegistry.STAGE_FISCAL_PROFILE))
        assertEquals(InstallRegistry.STAGE_FISCAL_PROFILE, fiscal.stage)

        val late = registry.register(RegisterInstallRequest(installId = ID, stage = InstallRegistry.STAGE_INSTALL))
        assertEquals(InstallRegistry.STAGE_FISCAL_PROFILE, late.stage)
    }

    @Test
    fun `rechaza un install id que no son 32 hex en minuscula`() = runTest {
        listOf("", "abc", ID.uppercase(), "z".repeat(32), ID + "0").forEach { bad ->
            assertFailsWith<ClientInputException>("debía rechazar '$bad'") {
                InstallRegistry(FakeInstallStore()).register(RegisterInstallRequest(installId = bad))
            }
        }
    }

    @Test
    fun `rechaza etapas, paises y versiones fuera del contrato`() = runTest {
        val registry = InstallRegistry(FakeInstallStore())

        assertFailsWith<ClientInputException> {
            registry.register(RegisterInstallRequest(installId = ID, stage = "premium"))
        }
        assertFailsWith<ClientInputException> {
            registry.register(RegisterInstallRequest(installId = ID, country = "España"))
        }
        assertFailsWith<ClientInputException> {
            registry.register(RegisterInstallRequest(installId = ID, locale = "es_ES_extra_largo"))
        }
        assertFailsWith<ClientInputException> {
            registry.register(RegisterInstallRequest(installId = ID, appVersionCode = -1))
        }
        assertFailsWith<ClientInputException> {
            registry.register(RegisterInstallRequest(installId = ID, firstRunAt = -5))
        }
    }

    @Test
    fun `limpia caracteres de control y recorta las campanas largas`() = runTest {
        val store = FakeInstallStore()
        val registry = InstallRegistry(store) { 1_000L }

        registry.register(
            RegisterInstallRequest(
                installId = ID,
                utmSource = "  finkit\n",
                utmCampaign = "c".repeat(5_000),
                utmContent = "   "
            )
        )

        val record = store.saved.getValue(ID)
        assertEquals("finkit", record.utmSource)
        assertEquals(200, record.utmCampaign?.length)
        assertEquals(null, record.utmContent, "un utm vacío no se guarda como cadena vacía")
        assertTrue(record.utmCampaign!!.none { it == '\n' })
    }

    private class FakeInstallStore : InstallStore {
        val saved = mutableMapOf<String, InstallRecord>()

        /** Imita `SetOptions.merge()`: un campo que llega nulo no borra el que ya estaba. */
        override suspend fun upsert(record: InstallRecord): InstallStoreResult {
            val previous = saved[record.installId]
            val merged = if (previous == null) {
                record
            } else {
                record.copy(
                    utmSource = record.utmSource ?: previous.utmSource,
                    utmMedium = record.utmMedium ?: previous.utmMedium,
                    utmCampaign = record.utmCampaign ?: previous.utmCampaign,
                    utmContent = record.utmContent ?: previous.utmContent,
                    utmTerm = record.utmTerm ?: previous.utmTerm,
                    appVersionName = record.appVersionName ?: previous.appVersionName,
                    appVersionCode = record.appVersionCode ?: previous.appVersionCode,
                    locale = record.locale ?: previous.locale,
                    country = record.country ?: previous.country,
                    firstRunAt = previous.firstRunAt ?: record.firstRunAt,
                    referrerClickTs = record.referrerClickTs ?: previous.referrerClickTs,
                    installBeginTs = record.installBeginTs ?: previous.installBeginTs
                )
            }
            val stage = if (previous?.stage == InstallRegistry.STAGE_FISCAL_PROFILE) {
                InstallRegistry.STAGE_FISCAL_PROFILE
            } else {
                record.stage
            }
            saved[record.installId] = merged.copy(stage = stage)
            return InstallStoreResult(existed = previous != null, stage = stage)
        }
    }

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef"
    }
}
