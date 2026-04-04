package com.gastos.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IncomeQueryResultParserTest {

    @Test
    fun incomeRecord_parsesCategoriaAndTotals() {
        val json = """{"concepto":"Nomina","monto":1000.0,"moneda":"EUR","fecha":1700000000000,"total_devengado":1200.0,"total_neto":950.0,"fuente":null,"categoria":"Salario"}"""
        val income = IncomeQueryResultParser.parse("${IncomeQueryResultParser.PREFIX_RECORD}$json")
        assertNotNull(income)
        assertEquals("Salario", income!!.categoria)
        assertEquals("Nomina", income.concepto)
        assertEquals(950.0, income.totalNeto, 0.01)
        assertEquals(1200.0, income.totalDevengado, 0.01)
    }

    @Test
    fun legacyColonFormat_readsCategoriaWhenSevenParts() {
        val income = IncomeQueryResultParser.parse("INCOME:Nómina:1000:EUR:1700000000000:null:Salario")
        assertNotNull(income)
        assertEquals("Salario", income!!.categoria)
    }
}
