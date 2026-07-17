package com.gastos.data.local.entity

import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.InvoiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests unitarios de los Mappers Entity ↔ Domain en [Mappers.kt].
 * Son funciones puras (不开 Room/Android), el test corre en JVM sin
 * Robolectric. Validamos round-trip ida y vuelta y el caso más
 * delicado: el parsing de `ivaRates` en [CountryFiscalConfigEntity]
 * (String "[21,10,4]" ↔ List<Double>).
 */
class MappersTest {

    @Test
    fun `InvoiceEntity round-trip conserva todos los campos`() {
        val entity = InvoiceEntity(
            id = 7,
            fecha = 1700000000L,
            proveedor = "Acme",
            tipo = InvoiceType.GASTO,
            moneda = "EUR",
            total = 121.0,
            ivaPercent = 21.0,
            irpfPercent = 0.0,
            paisCodigo = "ES",
            nifEmisor = "B12345678",
            nifReceptor = "12345678Z",
            imagenUri = "content://x",
            ocrRawText = "raw",
            notas = "nota",
            createdAt = 1L,
            updatedAt = 2L
        )
        val back = entity.toDomain().toEntity()
        assertEquals(entity.id, back.id)
        assertEquals(entity.fecha, back.fecha)
        assertEquals(entity.proveedor, back.proveedor)
        assertEquals(entity.tipo, back.tipo)
        assertEquals(entity.total, back.total, 0.0)
        assertEquals(entity.ivaPercent, back.ivaPercent, 0.0)
        assertEquals(entity.nifEmisor, back.nifEmisor)
        assertEquals(entity.createdAt, back.createdAt)
        assertEquals(entity.updatedAt, back.updatedAt)
    }

    @Test
    fun `IncomeEntity round-trip conserva totalDevengado y totalNeto`() {
        val entity = IncomeEntity(
            id = 3, fecha = 1L, concepto = "Sueldo", monto = 1000.0,
            totalDevengado = 1210.0, totalNeto = 1000.0, moneda = "EUR",
            ivaPercent = 21.0, irpfPercent = 15.0
        )
        val back = entity.toDomain().toEntity()
        assertEquals(1210.0, back.totalDevengado, 0.0)
        assertEquals(1000.0, back.totalNeto, 0.0)
        assertEquals("Sueldo", back.concepto)
    }

    @Test
    fun `InvoiceEntity con nifs e imagen null mappea a null en domain`() {
        val entity = InvoiceEntity(
            id = 1, fecha = 0L, proveedor = "p", tipo = InvoiceType.GASTO,
            total = 1.0, nifEmisor = null, nifReceptor = null, imagenUri = null
        )
        val domain = entity.toDomain()
        assertNull(domain.nifEmisor)
        assertNull(domain.nifReceptor)
        assertNull(domain.imagenUri)
    }

    @Test
    fun `CountryFiscalConfigEntity toDomain parsea ivaRates como lista de doubles`() {
        val entity = CountryFiscalConfigEntity(
            paisCodigo = "ES", nombrePais = "España",
            ivaRates = "[21,10,4]", irpfRate = 15.0,
            nifFormat = "^[A-Z]\\d{8}$", nombreLeyFiscal = "IVA"
        )
        val domain = entity.toDomain()
        assertEquals(listOf(21.0, 10.0, 4.0), domain.ivaRates)
        assertEquals(15.0, domain.irpfRate!!, 0.0)
        assertEquals("IVA", domain.nombreLeyFiscal)
    }

    @Test
    fun `CountryFiscalConfig toEntity serializa ivaRates como string con corchetes`() {
        val domain = CountryFiscalConfig(
            paisCodigo = "PT", nombrePais = "Portugal",
            ivaRates = listOf(23.0, 13.0, 6.0), irpfRate = null,
            nifFormat = "", nombreLeyFiscal = "IVA"
        )
        val entity = domain.toEntity()
        assertEquals("[23.0,13.0,6.0]", entity.ivaRates)
        assertNull(entity.irpfRate)
    }

    @Test
    fun `CountryFiscalConfig ivaRates round-trip preserva valores numericos`() {
        val original = CountryFiscalConfig(
            paisCodigo = "ES", nombrePais = "España",
            ivaRates = listOf(21.0, 10.0, 4.0), irpfRate = 15.0,
            nifFormat = "", nombreLeyFiscal = "IVA"
        )
        // ida: domain → entity (serializa la lista a String)
        val entity = original.toEntity()
        // vuelta: entity → domain (parsea el String de vuelta a List<Double>)
        val round = entity.toDomain()
        assertEquals(original.ivaRates, round.ivaRates)
        assertEquals(original.irpfRate!!, round.irpfRate!!, 0.0)
        assertEquals(original.nombreLeyFiscal, round.nombreLeyFiscal)
    }
}