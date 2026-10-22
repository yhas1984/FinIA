package com.gastos.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySpecValidatorTest {

    @Test
    fun `a valid query passes`() {
        val consulta = """{"action":"query","query_type":"gastos","periodo":"2026-08","desde":null,"hasta":null,"proveedor":null}"""
        assertTrue(QuerySpecValidator.validate(consulta).isEmpty())
    }

    @Test
    fun `an explicit range with desde and hasta passes`() {
        val consulta = """{"action":"query","query_type":"gastos","desde":"2026-08-01","hasta":"2026-08-15","periodo":null}"""
        assertTrue(QuerySpecValidator.validate(consulta).isEmpty())
    }

    @Test
    fun `an unknown period is rejected`() {
        val consulta = """{"action":"query","query_type":"gastos","periodo":"el verano pasado"}"""
        assertEquals(1, QuerySpecValidator.validate(consulta).size)
        assertTrue(QuerySpecValidator.validate(consulta).first().startsWith("periodo no reconocido"))
    }

    @Test
    fun `a malformed date is rejected`() {
        val consulta = """{"action":"query","query_type":"gastos","desde":"01/08/2026"}"""
        assertEquals(1, QuerySpecValidator.validate(consulta).size)
    }

    @Test
    fun `dates in the wrong order are rejected`() {
        val consulta = """{"action":"query","query_type":"gastos","desde":"2026-08-31","hasta":"2026-08-01"}"""
        assertTrue(QuerySpecValidator.validate(consulta).any { it.contains("posterior") })
    }

    @Test
    fun `an unknown query type or match mode is rejected`() {
        assertTrue(QuerySpecValidator.validate("""{"action":"query","query_type":"cripto"}""").isNotEmpty())
        assertTrue(QuerySpecValidator.validate("""{"action":"query","query_type":"gastos","match_mode":"loose"}""").isNotEmpty())
    }

    @Test
    fun `plain conversational text is not a query and passes`() {
        assertTrue(QuerySpecValidator.validate("No puedo consultar gastos futuros.").isEmpty())
    }

    @Test
    fun `broken json is reported`() {
        assertTrue(QuerySpecValidator.validate("""{"action":"query",,}""").isNotEmpty())
    }
}
