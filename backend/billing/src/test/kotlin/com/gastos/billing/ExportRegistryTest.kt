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
    fun `un hecho por cliente, trimestre y gestoria, no por envio`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }
        fun evento(id: String, profile: String, format: String, code: String? = "gestoriavalencia") =
            RecordExportRequest(
                eventId = id,
                installId = INSTALL_ID,
                period = "2026-4T",
                format = format,
                profile = profile,
                referralCode = code
            )

        // PDF y luego CSV del mismo trimestre a la misma gestoría: un solo hecho.
        val pdf = registry.record(evento("3f2504e0-4f89-41d3-9a0c-0305e82c3301", "summary", "pdf"))
        val csv = registry.record(evento("3f2504e0-4f89-41d3-9a0c-0305e82c3302", "ledger", "csv"))
        assertEquals(true, pdf.firstFact)
        assertEquals(false, csv.firstFact)
        assertEquals(false, csv.duplicate, "son eventos distintos, no un reintento")

        // Otra gestoría es otro hecho, y se factura aparte.
        val otra = registry.record(
            evento("3f2504e0-4f89-41d3-9a0c-0305e82c3303", "ledger", "csv", code = "otragestoria")
        )
        assertEquals(true, otra.firstFact)

        // Reintento del mismo evento: duplicado y conserva su firstFact.
        val reintento = registry.record(evento("3f2504e0-4f89-41d3-9a0c-0305e82c3301", "summary", "pdf"))
        assertEquals(true, reintento.duplicate)
        assertEquals(true, reintento.firstFact)
    }

    @Test
    fun `sin codigo de gestoria es telemetria y no consume el hecho`() = runTest {
        val store = FakeExportStore()
        val registry = ExportRegistry(store) { 1_000L }

        val sinCodigo = registry.record(
            RecordExportRequest(
                eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3311",
                installId = INSTALL_ID,
                period = "2026-4T",
                format = "csv",
                profile = "ledger"
            )
        )
        assertEquals(false, sinCodigo.firstFact)

        // El primer evento CON código del mismo trimestre sigue siendo el hecho que se factura.
        val conCodigo = registry.record(
            RecordExportRequest(
                eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3312",
                installId = INSTALL_ID,
                period = "2026-4T",
                format = "csv",
                profile = "ledger",
                referralCode = "gestoriavalencia"
            )
        )
        assertEquals(true, conCodigo.firstFact)
    }

    @Test
    fun `el libro no puede declararse en pdf`() = runTest {
        val registry = ExportRegistry(FakeExportStore()) { 1_000L }
        assertFailsWith<ClientInputException> {
            registry.record(
                RecordExportRequest(
                    eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3321",
                    installId = INSTALL_ID,
                    period = "2026-4T",
                    format = "pdf",
                    profile = "ledger"
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
        val facts = mutableSetOf<String>()

        /** Imita al de Firestore: el hecho es (installId, period, referralCode). */
        override suspend fun save(record: ExportRecord): ExportStoreResult {
            val existing = saved[record.eventId]
            if (existing != null) return ExportStoreResult(existed = true, firstFact = existing.firstFact)

            val key = "${record.installId}|${record.period}|${record.referralCode}"
            val firstFact = record.referralCode != null && facts.add(key)
            saved[record.eventId] = record.copy(firstFact = firstFact)
            return ExportStoreResult(existed = false, firstFact = firstFact)
        }
    }

    private companion object {
        const val EVENT_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val INSTALL_ID = "0123456789abcdef0123456789abcdef"
    }
}
