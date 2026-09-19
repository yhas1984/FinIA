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

    @Query("""UPDATE invoices SET driveFileId = :fileId, driveWebViewLink = :webViewLink,
        driveUploadPending = :pending, driveAccountId = :accountId,
        driveContentHash = :contentHash, driveSyncError = :error
        WHERE id = :id AND documentUuid = :documentUuid AND imagenUri IS :sourceUri""")
    suspend fun updateImageSync(id: Long, documentUuid: String, sourceUri: String?, fileId: String?,
        webViewLink: String?, pending: Boolean, accountId: String?, contentHash: String?, error: String?): Int

    @Transaction
    suspend fun updatePreservingImageState(record: InvoiceEntity) {
        val current = getInvoiceById(record.id) ?: error("Document no longer exists")
        check(current.documentUuid == record.documentUuid) { "Document identity changed" }
        updateInvoice(if (record.imagenUri == current.imagenUri) record.copy(
            driveFileId = current.driveFileId, driveWebViewLink = current.driveWebViewLink,
            driveUploadPending = current.driveUploadPending, driveAccountId = current.driveAccountId,
            driveContentHash = current.driveContentHash, driveSyncError = current.driveSyncError
        ) else record)
    }

    @Query("DELETE FROM invoices WHERE id = :id AND documentUuid = :uuid")
    suspend fun deleteByIdentity(id: Long, uuid: String)

    @Delete
    suspend fun deleteInvoice(invoice: InvoiceEntity)

    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun getInvoiceCount(): Int

    @Query("SELECT SUM(total) FROM invoices WHERE tipo = :type AND fecha BETWEEN :startDate AND :endDate")
    suspend fun getTotalByTypeAndDateRange(type: InvoiceType, startDate: Long, endDate: Long): Double?
}
