package com.gastos.repository

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import kotlinx.coroutines.flow.Flow

interface InvoiceRepository {
    fun getAllInvoices(): Flow<List<Invoice>>
    fun getInvoicesByType(type: InvoiceType): Flow<List<Invoice>>
    fun getInvoicesByDateRange(startDate: Long, endDate: Long): Flow<List<Invoice>>
    fun getInvoicesByProveedor(proveedor: String): Flow<List<Invoice>>
    suspend fun getInvoiceById(id: Long): Invoice?
    suspend fun insertInvoice(invoice: Invoice): Long
    suspend fun insertInvoiceWithProducts(invoice: Invoice, products: List<Product>): Long
    suspend fun updateInvoice(invoice: Invoice)
    suspend fun deleteInvoice(invoice: Invoice)
    suspend fun getInvoiceCount(): Int
    suspend fun getTotalByTypeAndDateRange(type: InvoiceType, startDate: Long, endDate: Long): Double?
}
