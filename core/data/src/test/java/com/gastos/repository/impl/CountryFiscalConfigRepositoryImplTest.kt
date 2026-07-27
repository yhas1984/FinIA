package com.gastos.repository.impl

import com.gastos.data.local.entity.CountryFiscalConfigEntity
import com.gastos.domain.model.SUPPORTED_FISCAL_COUNTRIES
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
}
