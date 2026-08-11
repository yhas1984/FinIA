package com.gastos.feature.backup

import com.gastos.repository.FloatingButtonEdge
import com.gastos.repository.FloatingButtonIds
import com.gastos.repository.FloatingButtonPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPayloadTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun `payload serialization preserves ids relationships settings and image names`() {
        val payload = BackupPayloadDto(
            createdAt = 123L,
            invoices = listOf(
                InvoiceDto(
                    id = 7,
                    fecha = 10,
                    proveedor = "Mercadona",
                    tipo = "GASTO",
                    categoria = "Alimentación",
                    moneda = "EUR",
                    total = 42.5,
                    numeroFactura = "F-7",
                    baseImponible = 38.64,
                    cuotaIva = 3.86,
                    ivaPercent = 10.0,
                    irpfPercent = 0.0,
                    paisCodigo = "ES",
                    nifEmisor = null,
                    nifReceptor = null,
                    imageFileName = "invoice_7.jpg",
                    driveFileId = "drive-7",
                    driveWebViewLink = "https://drive.test/7",
                    driveUploadPending = false,
                    ocrRawText = "ocr",
                    notas = "nota",
                    createdAt = 11,
                    updatedAt = 12
                )
            ),
            products = listOf(
                ProductDto(9, 7, "Café", 2.0, 3.0, 6.0, 10.0, 0.55, 13)
            ),
            incomes = listOf(
                IncomeDto(
                    id = 4,
                    fecha = 14,
                    concepto = "Nómina",
                    monto = 1_500.0,
                    totalDevengado = 1_800.0,
                    totalNeto = 1_500.0,
                    moneda = "EUR",
                    fuente = "Empresa",
                    categoria = "Nómina",
                    ivaPercent = 0.0,
                    irpfPercent = 15.0,
                    imageFileName = null,
                    notas = null,
                    createdAt = 15,
                    updatedAt = 16
                )
            ),
            fiscalConfigs = emptyList(),
            chatMessages = listOf(ChatMessageDto(3, "model", "Hola", "contexto", true, 17)),
            settings = RestorableSettingsDto(
                systemInstructions = "sé conciso",
                defaultCurrency = "USD",
                defaultCountry = "MX",
                darkMode = "dark",
                floatingButtonPositions = mapOf(
                    FloatingButtonIds.DASHBOARD_AI to FloatingButtonPositionDto(
                        edge = FloatingButtonEdge.LEFT.name,
                        verticalFraction = 0.4f
                    )
                )
            )
        )

        val decoded = json.decodeFromString<BackupPayloadDto>(json.encodeToString(payload))
        val dataset = decoded.toDataset(mapOf("invoice_7.jpg" to "content://restored/invoice_7.jpg"))

        assertEquals(7L, dataset.invoices.single().id)
        assertEquals("F-7", dataset.invoices.single().numeroFactura)
        assertEquals(38.64, dataset.invoices.single().baseImponible!!, 0.001)
        assertEquals(3.86, dataset.invoices.single().cuotaIva!!, 0.001)
        assertEquals("content://restored/invoice_7.jpg", dataset.invoices.single().imagenUri)
        assertEquals(7L, dataset.products.single().invoiceId)
        assertEquals(4L, dataset.incomes.single().id)
        assertEquals("contexto", dataset.chatMessages.single().contextText)
        assertEquals("USD", decoded.settings.toDomain().defaultCurrency)
        assertEquals(
            FloatingButtonPosition(FloatingButtonEdge.LEFT, 0.4f),
            decoded.settings.toDomain().floatingButtonPositions[FloatingButtonIds.DASHBOARD_AI]
        )
        assertEquals(setOf("invoice_7.jpg"), decoded.imageFileNames)
    }

    @Test
    fun `missing restored image becomes null instead of broken uri`() {
        val invoice = InvoiceDto(
            id = 1,
            fecha = 1,
            proveedor = "Tienda",
            tipo = "GASTO",
            categoria = null,
            moneda = "EUR",
            total = 1.0,
            ivaPercent = 0.0,
            irpfPercent = 0.0,
            paisCodigo = "ES",
            nifEmisor = null,
            nifReceptor = null,
            imageFileName = "missing.jpg",
            driveFileId = null,
            driveWebViewLink = null,
            driveUploadPending = false,
            ocrRawText = null,
            notas = null,
            createdAt = 1,
            updatedAt = 1
        )

        assertNull(invoice.toDomain(emptyMap()).imagenUri)
    }
}
