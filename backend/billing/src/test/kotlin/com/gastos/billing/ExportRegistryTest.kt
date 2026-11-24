package com.gastos.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportRegistryTest {

    @Test
    fun `registra el export y es idempotente por eventId`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }
        val request = RecordExportRequest(
            eventId = EVENT_ID,
            installId = INSTALL_ID,
            period = "2026-4T",
            format = "pdf",
            referralCode = "gestoriavalencia",
            artifactSha256 = "a".repeat(64),
            deliveredTo = "com.whatsapp"
        )

        val first = registry.record(request)
        assertEquals("ok", first.status)
        assertEquals(false, first.duplicate)

        // La cola durable reintenta: no puede contar dos veces el mismo export.
        val retry = registry.record(request)
        assertEquals(true, retry.duplicate)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `no acepta importes ni campos fuera del contrato`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        // El DTO no tiene `total`; validar aquí la forma es lo que hace que el rechazo sea del
        // contrato y no una casualidad de la deserialización.
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(eventId = "no-es-un-uuid", installId = INSTALL_ID, period = "2026-4T", format = "pdf")
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(eventId = EVENT_ID, installId = "ABC", period = "2026-4T", format = "pdf")
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(eventId = EVENT_ID, installId = INSTALL_ID, period = "4T-2026", format = "pdf")
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(eventId = EVENT_ID, installId = INSTALL_ID, period = "2026-4T", format = "xlsx")
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = EVENT_ID, installId = INSTALL_ID, period = "2026-4T",
                    format = "pdf", referralCode = "con espacio"
                )
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = EVENT_ID, installId = INSTALL_ID, period = "2026-4T",
                    format = "pdf", deliveredTo = "WhatsApp"
                )
            )
        }
        assertTrue(store.saved.isEmpty(), "ninguna petición inválida puede guardar nada")
    }

    @Test
    fun `el export guardado en disco no lleva deliveredTo y sigue siendo valido`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        val response = registry.record(
            RecordExportRequest(
                eventId = EVENT_ID,
                installId = INSTALL_ID,
                period = "2026-1T",
                format = "csv"
            )
        )

        assertEquals(false, response.duplicate)
        val record = store.saved.getValue(EVENT_ID)
        assertEquals(null, record.deliveredTo)
        assertEquals(null, record.referralCode)
        assertEquals("2026-1T", record.period)
        assertEquals(1_000L, record.receivedAt)
    }

    @Test
    fun `acepta rango de fechas y perfiles, y rechaza uno desconocido`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        // El libro puede salir con un filtro que no es un trimestre exacto: el periodo lo admite.
        val rango = registry.record(
            RecordExportRequest(
                eventId = EVENT_ID,
                installId = INSTALL_ID,
                period = "2026-01-01_2026-03-31",
                format = "csv",
                profile = "ledger"
            )
        )
        assertEquals(false, rango.duplicate)
        assertEquals("2026-01-01_2026-03-31", store.saved.getValue(EVENT_ID).period)
        assertEquals("ledger", store.saved.getValue(EVENT_ID).profile)

        // Y un perfil que no existe no se guarda.
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3302",
                    installId = INSTALL_ID,
                    period = "2026-4T",
                    format = "csv",
                    profile = "libro"
                )
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3303",
                    installId = INSTALL_ID,
                    period = "2026-01-01",
                    format = "csv"
                )
            )
        }
    }

    @Test
    fun `acepta el nombre viejo quarter y lo normaliza a period`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        // El backend desplegado antes del rename solo entiende `quarter`; el cliente no puede
        // quedarse sin registrar el export porque el nombre del campo cambie de un despliegue a otro.
        val legacy = registry.record(
            RecordExportRequest(
                eventId = EVENT_ID,
                installId = INSTALL_ID,
                quarter = "2026-4T",
                format = "csv"
            )
        )
        assertEquals(false, legacy.duplicate)
        assertEquals("2026-4T", store.saved.getValue(EVENT_ID).period)

        // Y lo canónico sigue funcionando.
        val canonical = registry.record(
            RecordExportRequest(
                eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3390",
                installId = INSTALL_ID,
                period = "2026-3T",
                format = "pdf"
            )
        )
        assertEquals(false, canonical.duplicate)
        assertEquals("2026-3T", store.saved.getValue("3f2504e0-4f89-41d3-9a0c-0305e82c3390").period)
    }

    @Test
    fun `rechaza period y quarter contradictorios o ausentes`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = EVENT_ID,
                    installId = INSTALL_ID,
                    period = "2026-4T",
                    quarter = "2026-3T",
                    format = "csv"
                )
            )
        }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = EVENT_ID,
                    installId = INSTALL_ID,
                    format = "csv"
                )
            )
        }
        assertTrue(store.saved.isEmpty())
    }

    private class FakeExportStore : ExportStore {
        val saved = mutableMapOf<String, ExportRecord>()

        override suspend fun save(record: ExportRecord): Boolean {
            val existed = saved.containsKey(record.eventId)
            if (!existed) saved[record.eventId] = record
            return existed
        }
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val INSTALL_ID = "0123456789abcdef0123456789abcdef"
    }
}
