package com.gastos.feature.backup

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetsSchemaTest {
    @Test
    fun `expense row matches headers and keeps id before Drive link`() {
        val row = SheetsSchema.expenseRow(
            Invoice(
                id = 9,
                fecha = 1L,
                proveedor = "Proveedor",
                tipo = InvoiceType.GASTO,
                total = 121.0,
                driveWebViewLink = "https://drive.test/9"
            )
        )

        assertEquals(SheetsSchema.recibidasHeaders.size, row.size)
        assertEquals(9L, row[13])
        assertEquals("https://drive.test/9", row[14])
    }

    @Test
    fun `product row stores invoice and product ids`() {
        val row = SheetsSchema.productRow(
            Product(
                id = 17,
                invoiceId = 9,
                descripcion = "Producto",
                precioUnitario = 10.0
            ),
            "Proveedor"
        )

        assertEquals(SheetsSchema.productosHeaders.size, row.size)
        assertEquals(9L, row[7])
        assertEquals(17L, row[8])
    }
}
