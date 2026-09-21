package com.gastos.storage

import android.content.Context
import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.DocumentGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CommandCommit(val invoice: Invoice? = null, val income: Income? = null, val receipt: ChatMessageRecord)

@Singleton
class CommandOperationStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    suspend fun latestPending(): CommandOperation? = database.commandOperationDao().latestPending()?.toDomain()
    suspend fun get(uuid: String): CommandOperation? = database.commandOperationDao().get(uuid)?.toDomain()

    suspend fun begin(uuid: String, text: String): CommandOperation = database.withTransaction {
        val previous: CommandOperationEntity? = database.commandOperationDao().get(uuid)
        if (previous != null) {
            check(previous.text == text) { "Operation content changed" }
            return@withTransaction previous.toDomain()
        }
        val operation: CommandOperation = CommandOperation(uuid, text)
        database.commandOperationDao().insert(operation.toEntity())
        database.chatMessageDao().insertAndTrim(ChatMessageEntity(role = "user", visibleText = text,
            contextText = text, operationUuid = uuid))
        operation
    }

    suspend fun finish(uuid: String) {
        database.withTransaction {
            val operation: CommandOperationEntity = database.commandOperationDao().get(uuid) ?: return@withTransaction
            if (operation.status == "PENDING") database.commandOperationDao().update(operation.copy(status = "COMPLETE"))
        }
    }

    suspend fun commit(uuid: String, invoice: Invoice?, income: Income?, products: List<Product>): CommandCommit = database.withTransaction {
        val operation: CommandOperationEntity = requireNotNull(database.commandOperationDao().get(uuid))
        if (operation.status == "SAVED") return@withTransaction saved(operation)
        check(operation.status == "PENDING") { "Operation already completed" }
        require((invoice == null) != (income == null))
        val savedInvoice: Invoice?
        val savedIncome: Income?
        if (invoice != null) {
            require(invoice.tipo == InvoiceType.GASTO && invoice.total.isFinite() && invoice.total > 0)
            val record: Invoice = invoice.copy(documentUuid = uuid)
            DocumentGuard(database).check(record.documentIdentity())
            val id: Long = database.invoiceDao().insertInvoice(record.toEntity())
            database.productDao().insertProducts(products.map { it.copy(invoiceId = id).toEntity() })
            savedInvoice = record.copy(id = id)
            savedIncome = null
        } else {
            val record: Income = requireNotNull(income).copy(documentUuid = uuid)
            require(record.monto.isFinite() && record.monto > 0)
            DocumentGuard(database).check(record.documentIdentity())
            val id: Long = database.incomeDao().insertIncomeEntity(record.toEntity())
            savedIncome = record.copy(id = id)
            savedInvoice = null
        }
        val identity: DocumentIdentity = savedInvoice?.documentIdentity() ?: requireNotNull(savedIncome).documentIdentity()
        val message: ChatMessageEntity = createDocumentChatMessage(context, identity).copy(operationUuid = uuid)
        val id: Long = database.chatMessageDao().insert(message)
        database.chatMessageDao().trimToLast(200)
        database.commandOperationDao().update(operation.copy(status = "SAVED", resultKind = message.documentKind,
            resultUuid = identity.uuid, resultText = message.visibleText))
        CommandCommit(savedInvoice, savedIncome, message.copy(id = id).toDomain())
    }

    private suspend fun saved(operation: CommandOperationEntity): CommandCommit {
        val invoice: Invoice? = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == operation.resultUuid }?.toDomain()
        val income: Income? = database.incomeDao().documentRecords().firstOrNull { it.documentUuid == operation.resultUuid }?.toDomain()
        val receipt: ChatMessageRecord = database.chatMessageDao().getAllMessages().firstOrNull {
            it.role == "document" && it.operationUuid == operation.uuid
        }?.toDomain() ?: ChatMessageRecord(role = "document", visibleText = operation.resultText.orEmpty(),
            operationUuid = operation.uuid, documentUuid = operation.resultUuid, documentKind = operation.resultKind,
            includeInContext = false, createdAt = operation.createdAt)
        // Even if the user later deleted the record or cleared the chat, never recreate it on retry.
        return CommandCommit(invoice, income, receipt)
    }
}
