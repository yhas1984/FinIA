package com.gastos.domain.usecase

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.InvoiceRepository
import javax.inject.Inject

/**
 * Caso de uso para guardar una factura (gasto) en la base de datos local.
 *
 * Encapsula la lógica que estaba duplicada en `ChatbotViewModel`,
 * `VoiceCommandViewModel` y `ScanInvoiceViewModel`:
 *   1. Insertar la invoice
 *   2. Insertar los productos asociados (si los hay)
 *   3. Devolver el ID de la invoice guardada
 *
 * Sin scopes de Google: el sync con Sheets lo hace el ViewModel que
 * llama a este use case (porque SheetsSyncManager ya se inyecta en
 * los ViewModels).
 */
class SaveInvoiceUseCase @Inject constructor(
    private val invoiceRepository: InvoiceRepository
) {
    /**
     * @return ID de la invoice guardada.
     */
    suspend operator fun invoke(
        invoice: Invoice,
        products: List<Product> = emptyList()
    ): Long {
        require(invoice.tipo == InvoiceType.GASTO) {
            "SaveInvoiceUseCase solo admite facturas de tipo GASTO"
        }
        return invoiceRepository.insertInvoiceWithProducts(invoice, products)
    }
}
