package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.BackupDao
import com.gastos.local.dao.BackupEntitySnapshot
import com.gastos.repository.BackupDataRepository
import com.gastos.repository.BackupDataset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupDataRepositoryImpl @Inject constructor(
    private val dao: BackupDao
) : BackupDataRepository {
    override suspend fun snapshot(): BackupDataset {
        val snapshot = dao.snapshot()
        return BackupDataset(
            invoices = snapshot.invoices.map { it.toDomain() },
            products = snapshot.products.map { it.toDomain() },
            incomes = snapshot.incomes.map { it.toDomain() },
            fiscalConfigs = snapshot.fiscalConfigs.map { it.toDomain() },
            chatMessages = snapshot.chatMessages.map { it.toDomain() }
        )
    }

    override suspend fun replaceAll(dataset: BackupDataset) {
        dao.replaceAll(
            BackupEntitySnapshot(
                invoices = dataset.invoices.map { it.toEntity() },
                products = dataset.products.map { it.toEntity() },
                incomes = dataset.incomes.map { it.toEntity() },
                fiscalConfigs = dataset.fiscalConfigs.map { it.toEntity() },
                chatMessages = dataset.chatMessages.map { it.toEntity() }
            )
        )
    }
}
