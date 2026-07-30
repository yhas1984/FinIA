package com.gastos.repository.impl

import com.gastos.data.local.entity.CountryFiscalConfigEntity
import com.gastos.domain.model.SUPPORTED_FISCAL_COUNTRIES
import com.gastos.domain.model.fiscalCountryLabel
import com.gastos.local.dao.CountryFiscalConfigDao
import io.mockk.CapturingSlot
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFiscalConfigRepositoryImplTest {
    private val dao: CountryFiscalConfigDao = mockk()
    private val repository: CountryFiscalConfigRepositoryImpl = CountryFiscalConfigRepositoryImpl(dao)

    @Test
    fun `default seeding delegates every supported country to insert if missing`() = runTest {
        val capturedConfigs: CapturingSlot<List<CountryFiscalConfigEntity>> = slot()
        coJustRun { dao.insertConfigsIfMissing(capture(capturedConfigs)) }
        repository.insertDefaultConfigs()
        assertEquals(SUPPORTED_FISCAL_COUNTRIES.toSet(), capturedConfigs.captured.map { it.paisCodigo }.toSet())
        coVerify(exactly = 1) { dao.insertConfigsIfMissing(any()) }
    }

    @Test
    fun `default seeding includes current Ecuador rates`() = runTest {
        val capturedConfigs: CapturingSlot<List<CountryFiscalConfigEntity>> = slot()
        coJustRun { dao.insertConfigsIfMissing(capture(capturedConfigs)) }
        repository.insertDefaultConfigs()
        val ecuadorConfig: CountryFiscalConfigEntity = capturedConfigs.captured.single { it.paisCodigo == "EC" }
        assertEquals("0.0,5.0,15.0", ecuadorConfig.ivaRates)
    }

    @Test
    fun `default seeding includes fiscal countries for every supported Latin American currency`() = runTest {
        val capturedConfigs: CapturingSlot<List<CountryFiscalConfigEntity>> = slot()
        coJustRun { dao.insertConfigsIfMissing(capture(capturedConfigs)) }
        repository.insertDefaultConfigs()
        val expectedConfigs: Map<String, Pair<String, String>> = mapOf(
            "BO" to ("Bolivia" to "0.0,13.0"),
            "GT" to ("Guatemala" to "0.0,12.0"),
            "NI" to ("Nicaragua" to "0.0,15.0"),
            "PY" to ("Paraguay" to "0.0,5.0,10.0"),
            "UY" to ("Uruguay" to "0.0,10.0,22.0"),
            "VE" to ("Venezuela" to "0.0,8.0,16.0,31.0")
        )
        expectedConfigs.forEach { entry: Map.Entry<String, Pair<String, String>> ->
            val countryCode: String = entry.key
            val expected: Pair<String, String> = entry.value
            val config: CountryFiscalConfigEntity =
                capturedConfigs.captured.single { it.paisCodigo == countryCode }
            assertEquals(expected.first, config.nombrePais)
            assertEquals(expected.second, config.ivaRates)
        }
        assertEquals("Venezuela (VE)", fiscalCountryLabel("ve"))
    }
}
