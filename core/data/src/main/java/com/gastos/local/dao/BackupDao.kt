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

internal data class BackupEntitySnapshot(
    val invoices: List<InvoiceEntity>,
    val products: List<ProductEntity>,
    val incomes: List<IncomeEntity>,
    val fiscalConfigs: List<CountryFiscalConfigEntity>,
    val chatMessages: List<ChatMessageEntity>
)

@Dao
abstract class BackupDao {
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
        chatMessages = chatMessages()
    )

    @Transaction
    internal open suspend fun replaceAll(snapshot: BackupEntitySnapshot) {
        clearProducts()
        clearInvoices()
        clearIncomes()
        clearFiscalConfigs()
        clearChatMessages()

        insertInvoices(snapshot.invoices)
        insertProducts(snapshot.products)
        insertIncomes(snapshot.incomes)
        insertFiscalConfigs(snapshot.fiscalConfigs)
        insertChatMessages(snapshot.chatMessages)
    }
}
