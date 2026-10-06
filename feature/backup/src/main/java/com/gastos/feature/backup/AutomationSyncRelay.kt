package com.gastos.feature.backup

import androidx.room.withTransaction
import com.gastos.local.database.AppDatabase
import com.gastos.domain.model.*
import com.gastos.data.local.entity.toDomain
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Transfers durable local intents into the remote outbox without changing their destination. */
@Singleton
class AutomationSyncRelay @Inject constructor(private val database: AppDatabase, private val outbox: RemoteSyncOutboxRepository) {
    suspend fun observe() {
        database.automationDao().observeRecords("SYNC").onEach { drain() }.retryWhen { cause, _ ->
            if (cause is CancellationException) throw cause
            delay(30_000)
            true
        }.collect()
    }
    suspend fun drain() {
        var failure: Exception? = null
        for (record in database.automationDao().records("SYNC")) {
            try {
            val intent: SyncIntent = AutomationCodec.json.decodeFromString(record.payload)
            val invoice = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == intent.documentUuid }?.toDomain()
            val income = database.incomeDao().documentRecords().firstOrNull { it.documentUuid == intent.documentUuid }?.toDomain()
            val row = income ?: invoice?.takeIf { it.tipo == InvoiceType.INGRESO }?.toIncome()
            if (row != null) outbox.enqueue(RemoteSyncTarget.INCOME_SHEETS, row.id, RemoteSyncAction.UPSERT,
                documentUuid = row.documentUuid, accountId = intent.accountId, spreadsheetId = intent.spreadsheetId)
            else if (invoice != null) outbox.enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, RemoteSyncAction.UPSERT,
                documentUuid = invoice.documentUuid, accountId = intent.accountId, spreadsheetId = intent.spreadsheetId)
            database.withTransaction {
                if (database.automationDao().record(record.id)?.payload == record.payload) database.automationDao().deleteRecord(record.id)
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }
}
