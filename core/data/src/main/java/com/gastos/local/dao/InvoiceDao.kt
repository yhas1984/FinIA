package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.InvoiceEntity
import com.gastos.domain.model.InvoiceType
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {

    @Query("SELECT * FROM invoices ORDER BY fecha DESC")
    fun getAllInvoices(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE tipo = :type ORDER BY fecha DESC")
    fun getInvoicesByType(type: InvoiceType): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE fecha BETWEEN :startDate AND :endDate ORDER BY fecha DESC")
    fun getInvoicesByDateRange(startDate: Long, endDate: Long): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE proveedor LIKE '%' || :proveedor || '%' ORDER BY fecha DESC")
    fun getInvoicesByProveedor(proveedor: String): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun getInvoiceById(id: Long): InvoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity): Long

    @Update
    suspend fun updateInvoice(invoice: InvoiceEntity)

    @Query(
        """
        UPDATE invoices
        SET driveFileId = :fileId,
            driveWebViewLink = :webViewLink,
            driveUploadPending = :pending,
            updatedAt = :updatedAt
        WHERE id = :invoiceId
        """
    )
    suspend fun updateDriveMetadata(
        invoiceId: Long,
        fileId: String?,
        webViewLink: String?,
        pending: Boolean,
        updatedAt: Long
    )

    @Delete
    suspend fun deleteInvoice(invoice: InvoiceEntity)

    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun getInvoiceCount(): Int

    @Query("SELECT SUM(total) FROM invoices WHERE tipo = :type AND fecha BETWEEN :startDate AND :endDate")
    suspend fun getTotalByTypeAndDateRange(type: InvoiceType, startDate: Long, endDate: Long): Double?
}
