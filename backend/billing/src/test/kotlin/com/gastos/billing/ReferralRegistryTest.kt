package com.gastos.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferralRegistryTest {

    private val installId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `emite un codigo de seis caracteres y es idempotente por instalacion`() = runTest {
        val store = FakeReferralStore()
        val registry = ReferralRegistry(store, now = { 1_000L })

        val first = registry.issue(IssueReferralRequest(installId))
        assertEquals(6, first.code.length)
        assertTrue(first.code.all { it in ReferralRegistry.ALPHABET }, "código: ${first.code}")
        assertEquals(installId, first.installId)
        assertEquals(1_000L, first.createdAt)

        // Regenerar el PDF no puede cambiar el código: si cambiara, la gestoría citaría uno muerto.
        val second = registry.issue(IssueReferralRequest(installId))
        assertEquals(first.code, second.code)
        assertEquals(1, store.created)

        // Dos instalaciones distintas no comparten código.
        val other = registry.issue(IssueReferralRequest("f".repeat(32)))
        assertFalse(other.code == first.code)
    }

    @Test
    fun `el alfabeto no tiene caracteres que se confunden al leer`() {
        listOf('0', 'o', '1', 'l', 'i').forEach { ambiguous ->
            assertFalse(ambiguous in ReferralRegistry.ALPHABET, "«$ambiguous» no puede estar en el alfabeto")
        }
        val code = ReferralRegistry.randomCode()
        assertEquals(6, code.length)
        assertNull(code.firstOrNull { it !in ReferralRegistry.ALPHABET })
    }

    @Test
    fun `rechaza un installId que no cumple el contrato`() = runTest {
        val registry = ReferralRegistry(FakeReferralStore(), now = { 1L })
        assertFailsWith<ClientInputException> { registry.issue(IssueReferralRequest("ABC")) }
        assertFailsWith<ClientInputException> { registry.issue(IssueReferralRequest("0123456789ABCDEF0123456789ABCDEF")) }
    }

    @Test
    fun `si el codigo esta ocupado prueba otro en vez de romper`() = runTest {
        val store = FakeReferralStore()
        store.occupy("aaaaaa", "f".repeat(32))

        val codes = ArrayDeque(listOf("aaaaaa", "bbbbbb"))
        val registry = ReferralRegistry(store, { 1L }, { codes.removeFirst() })

        val issued = registry.issue(IssueReferralRequest(installId))
        assertEquals("bbbbbb", issued.code)
        // El código ocupado sigue perteneciendo a su instalación original.
        assertEquals("f".repeat(32), store.findByCode("aaaaaa")?.installId)
    }

    private class FakeReferralStore : ReferralStore {
        val byCode = mutableMapOf<String, ReferralRecord>()
        val byInstall = mutableMapOf<String, ReferralRecord>()
        var created = 0

        fun occupy(code: String, installId: String) {
            byCode[code] = ReferralRecord(code, installId, 0L)
        }

        override suspend fun findByInstall(installId: String): ReferralRecord? = byInstall[installId]

        override suspend fun create(installId: String, code: String, now: Long): ReferralRecord? {
            byInstall[installId]?.let { return it }
            if (byCode.containsKey(code)) return null
            val record = ReferralRecord(code, installId, now)
            byCode[code] = record
            byInstall[installId] = record
            created++
            return record
        }

        override suspend fun findByCode(code: String): ReferralRecord? = byCode[code]
    }
}
