package com.gastos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.data.local.entity.CountryFiscalConfigEntity
import com.gastos.data.local.entity.IncomeEntity
import com.gastos.data.local.entity.InvoiceEntity
import com.gastos.data.local.entity.ProductEntity
import com.gastos.data.local.entity.RestoreMarkerEntity

internal data class BackupEntitySnapshot(
    val invoices: List<InvoiceEntity>,
    val products: List<ProductEntity>,
    val incomes: List<IncomeEntity>,
    val fiscalConfigs: List<CountryFiscalConfigEntity>,
    val chatMessages: List<ChatMessageEntity>,
    val commandOperations: List<com.gastos.data.local.entity.CommandOperationEntity> = emptyList()
)

@Dao
abstract class BackupDao {
    @Query("SELECT * FROM invoices WHERE id = :id")
    internal abstract suspend fun invoice(id: Long): InvoiceEntity?

    @Query("SELECT * FROM incomes WHERE id = :id")
    internal abstract suspend fun income(id: Long): IncomeEntity?

    @Query("SELECT * FROM products WHERE invoiceId = :id ORDER BY id")
    internal abstract suspend fun productsForInvoice(id: Long): List<ProductEntity>

    @Transaction
    internal open suspend fun financialSnapshot(): BackupEntitySnapshot = BackupEntitySnapshot(
        invoices(), products(), incomes(), emptyList(), emptyList())

    @Transaction
    internal open suspend fun documentSnapshot(isIncome: Boolean, id: Long): BackupEntitySnapshot {
        val invoice = if (isIncome && id > 0) null else invoice(kotlin.math.abs(id))?.takeIf {
            (it.tipo == com.gastos.domain.model.InvoiceType.INGRESO) == isIncome
        }
        return BackupEntitySnapshot(listOfNotNull(invoice), invoice?.let { productsForInvoice(it.id) }.orEmpty(),
            if (isIncome && id > 0) listOfNotNull(income(id)) else emptyList(), emptyList(), emptyList())
    }
    @Query("SELECT * FROM command_operations ORDER BY createdAt")
    internal abstract suspend fun commandOperations(): List<com.gastos.data.local.entity.CommandOperationEntity>

    @Query("DELETE FROM command_operations")
    internal abstract suspend fun clearCommandOperations()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    internal abstract suspend fun insertCommandOperations(values: List<com.gastos.data.local.entity.CommandOperationEntity>)

    @Query("SELECT * FROM invoices ORDER BY id")
    internal abstract suspend fun invoices(): List<InvoiceEntity>

    @Query("SELECT * FROM products ORDER BY id")
    internal abstract suspend fun products(): List<ProductEntity>

    @Query("SELECT * FROM incomes ORDER BY id")
    internal abstract suspend fun incomes(): List<IncomeEntity>

    @Query("SELECT * FROM country_fiscal_config ORDER BY paisCodigo")
    internal abstract suspend fun fiscalConfigs(): List<CountryFiscalConfigEntity>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt, id")
    internal abstract suspend fun chatMessages(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertInvoices(values: List<InvoiceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertProducts(values: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertIncomes(values: List<IncomeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertFiscalConfigs(values: List<CountryFiscalConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertChatMessages(values: List<ChatMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun upsertRestoreMarker(marker: RestoreMarkerEntity)

    @Query("SELECT * FROM restore_markers WHERE id = 1 LIMIT 1")
    internal abstract suspend fun restoreMarker(): RestoreMarkerEntity?

    @Query("DELETE FROM restore_markers WHERE id = 1 AND restoreId = :restoreId")
    internal abstract suspend fun clearRestoreMarker(restoreId: String)

    @Query("DELETE FROM products")
    internal abstract suspend fun clearProducts()

    @Query("DELETE FROM invoices")
    internal abstract suspend fun clearInvoices()

    @Query("DELETE FROM incomes")
    internal abstract suspend fun clearIncomes()

    @Query("DELETE FROM country_fiscal_config")
    internal abstract suspend fun clearFiscalConfigs()

    @Query("DELETE FROM chat_messages")
    internal abstract suspend fun clearChatMessages()

    @Transaction
    internal open suspend fun snapshot(): BackupEntitySnapshot = BackupEntitySnapshot(
        invoices = invoices(),
        products = products(),
        incomes = incomes(),
        fiscalConfigs = fiscalConfigs(),
        chatMessages = chatMessages(),
        commandOperations = commandOperations()
    )

    @Transaction
    internal open suspend fun replaceAll(snapshot: BackupEntitySnapshot, restoreId: String?) {
        clearProducts()
        clearInvoices()
        clearIncomes()
        clearFiscalConfigs()
        clearChatMessages()
        clearCommandOperations()

        insertInvoices(snapshot.invoices)
        insertProducts(snapshot.products)
        insertIncomes(snapshot.incomes)
        insertFiscalConfigs(snapshot.fiscalConfigs)
        insertChatMessages(snapshot.chatMessages)
        insertCommandOperations(snapshot.commandOperations)
        if (restoreId != null) upsertRestoreMarker(RestoreMarkerEntity(restoreId = restoreId))
    }
}
