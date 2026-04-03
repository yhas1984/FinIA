package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.InvoiceDao
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.InvoiceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvoiceRepositoryImpl @Inject constructor(
    private val invoiceDao: InvoiceDao
) : InvoiceRepository {

    override fun getAllInvoices(): Flow<List<Invoice>> =
        invoiceDao.getAllInvoices().map { list -> list.map { it.toDomain() } }

    override fun getInvoicesByType(type: InvoiceType): Flow<List<Invoice>> =
        invoiceDao.getInvoicesByType(type).map { list -> list.map { it.toDomain() } }

    override fun getInvoicesByDateRange(startDate: Long, endDate: Long): Flow<List<Invoice>> =
        invoiceDao.getInvoicesByDateRange(startDate, endDate).map { list -> list.map { it.toDomain() } }

    override fun getInvoicesByProveedor(proveedor: String): Flow<List<Invoice>> =
        invoiceDao.getInvoicesByProveedor(proveedor).map { list -> list.map { it.toDomain() } }

    override suspend fun getInvoiceById(id: Long): Invoice? =
        invoiceDao.getInvoiceById(id)?.toDomain()

    override suspend fun insertInvoice(invoice: Invoice): Long =
        invoiceDao.insertInvoice(invoice.toEntity().copy(updatedAt = System.currentTimeMillis()))

    override suspend fun updateInvoice(invoice: Invoice) =
        invoiceDao.updateInvoice(invoice.toEntity().copy(updatedAt = System.currentTimeMillis()))

    override suspend fun deleteInvoice(invoice: Invoice) =
        invoiceDao.deleteInvoice(invoice.toEntity())

    override suspend fun getInvoiceCount(): Int = invoiceDao.getInvoiceCount()

    override suspend fun getTotalByTypeAndDateRange(
        type: InvoiceType,
        startDate: Long,
        endDate: Long
    ): Double? = invoiceDao.getTotalByTypeAndDateRange(type, startDate, endDate)
}