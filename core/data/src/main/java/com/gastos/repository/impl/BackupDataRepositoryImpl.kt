package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.BackupDao
import com.gastos.local.dao.BackupEntitySnapshot
import com.gastos.repository.BackupDataRepository
import com.gastos.repository.BackupDataset
import com.gastos.domain.model.*
import com.gastos.data.local.entity.*
import kotlinx.serialization.encodeToString
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupDataRepositoryImpl @Inject constructor(
    private val dao: BackupDao,
    private val database: com.gastos.local.database.AppDatabase? = null
) : BackupDataRepository {
    override suspend fun snapshot(): BackupDataset = map(dao.snapshot())
    override suspend fun financialSnapshot(): BackupDataset = map(dao.financialSnapshot())
    override suspend fun documentSnapshot(income: Boolean, id: Long): BackupDataset = map(dao.documentSnapshot(income, id))

    private fun map(snapshot: BackupEntitySnapshot): BackupDataset {
        return BackupDataset(
            invoices = snapshot.invoices.map { it.toDomain() },
            products = snapshot.products.map { it.toDomain() },
            incomes = snapshot.incomes.map { it.toDomain() },
            fiscalConfigs = snapshot.fiscalConfigs.map { it.toDomain() },
            chatMessages = snapshot.chatMessages.map { it.toDomain() },
            automation = AutomationData(snapshot.categories.map { it.toDomain() }, snapshot.automationRecords.map { it.toDomain() }, snapshot.monthlyLimits.map { AutomationCodec.json.decodeFromString<MonthlyLimit>(it.payload) }),
            commandOperations = snapshot.commandOperations.map { it.toDomain() }
        )
    }

    override suspend fun replaceAll(dataset: BackupDataset) {
        replaceAllSnapshot(dataset, restoreId = null)
    }

    override suspend fun replaceAllWithRestoreMarker(dataset: BackupDataset, restoreId: String) {
        replaceAllSnapshot(dataset, restoreId)
    }

    override suspend fun committedRestoreId(): String? = dao.restoreMarker()?.restoreId

    override suspend fun clearRestoreMarker(restoreId: String) {
        dao.clearRestoreMarker(restoreId)
    }

    private suspend fun replaceAllSnapshot(dataset: BackupDataset, restoreId: String?) {
        AutomationValidation.validate(dataset.automation)
        if (database != null) {
            database.withTransaction { replaceSnapshot(dataset, restoreId); rebuildCatalog(database) }
        } else replaceSnapshot(dataset, restoreId)
    }
    private suspend fun rebuildCatalog(database: com.gastos.local.database.AppDatabase) {
        val catalog = com.gastos.storage.CategoryCatalog(database)
        catalog.initialize()
        for (row in database.invoiceDao().documentRecords()) {
            val bound = catalog.assign(row.toDomain())
            database.invoiceDao().updatePreservingImageState(bound.toEntity())
        }
        for (row in database.incomeDao().documentRecords()) {
            val bound = catalog.assign(row.toDomain())
            database.incomeDao().updatePreservingImageState(bound.toEntity())
        }
    }
    private suspend fun replaceSnapshot(dataset: BackupDataset, restoreId: String?) {
        dao.replaceAll(
            BackupEntitySnapshot(
                invoices = dataset.invoices.map { it.toEntity() },
                products = dataset.products.map { it.toEntity() },
                incomes = dataset.incomes.map { it.toEntity() },
                fiscalConfigs = dataset.fiscalConfigs.map { it.toEntity() },
                chatMessages = dataset.chatMessages.map { it.toEntity() },
                categories = dataset.automation.categories.map { it.toEntity() },
                automationRecords = dataset.automation.records.filter { it.type != "SYNC" }.map { it.toEntity() },
                monthlyLimits = dataset.automation.limits.map { MonthlyLimitEntity(it.id, it.categoryId, it.month, AutomationCodec.json.encodeToString(it)) },
                commandOperations = dataset.commandOperations.map { it.toEntity() }
            ),
            restoreId
        )
    }
}
