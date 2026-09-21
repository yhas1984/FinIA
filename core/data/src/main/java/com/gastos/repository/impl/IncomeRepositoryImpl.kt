package com.gastos.repository.impl

import androidx.room.withTransaction
import com.gastos.local.database.AppDatabase
import com.gastos.domain.model.documentIdentity
import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.IncomeDao
import com.gastos.domain.model.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.gastos.repository.IncomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomeRepositoryImpl @Inject constructor(
    private val incomeDao: IncomeDao,
    private val database: AppDatabase
) : IncomeRepository {

    override fun getAllIncomes(): Flow<List<Income>> =
        combine(incomeDao.getAllIncomes(), database.invoiceDao().getInvoicesByType(InvoiceType.INGRESO)) { native, legacy ->
            mergeIncomes(legacy.map { it.toDomain() }, native.map { it.toDomain() })
        }

    override fun getIncomesByDateRange(startDate: Long, endDate: Long): Flow<List<Income>> =
        getAllIncomes().map { records -> records.filter { it.fecha in startDate..endDate } }

    override fun getIncomesByFuente(fuente: String): Flow<List<Income>> =
        getAllIncomes().map { records -> records.filter { it.fuente.orEmpty().contains(fuente, ignoreCase = true) } }

    override suspend fun getIncomeById(id: Long): Income? = if (id < 0)
        database.invoiceDao().getInvoiceById(-id)?.toDomain()?.takeIf { it.tipo == InvoiceType.INGRESO }?.asLegacyIncome()
        else incomeDao.getIncomeById(id)?.toDomain()

    override suspend fun insertIncome(income: Income): Long =
        database.withTransaction {
            DocumentGuard(database).check(income.documentIdentity())
            incomeDao.insertIncomeEntity(income.toEntity().copy(updatedAt = System.currentTimeMillis()))
        }

    override suspend fun updateIncome(income: Income) {
        database.withTransaction {
            DocumentGuard(database).check(income.documentIdentity())
            if (income.id < 0) {
                val original = requireNotNull(database.invoiceDao().getInvoiceById(-income.id))
                check(original.tipo == InvoiceType.INGRESO && original.documentUuid == income.documentUuid)
                val invoice = original.toDomain().copy(fecha = income.fecha, proveedor = income.concepto,
                    total = income.monto, moneda = income.moneda, categoria = income.categoria, subcategoria = income.subcategoria,
                    numeroFactura = income.evidence?.document?.number, nifEmisor = income.evidence?.document?.issuerTaxId,
                    ivaPercent = income.ivaPercent, irpfPercent = income.irpfPercent, taxes = income.taxes,
                    baseImponible = income.evidence?.document?.taxBase, cuotaIva = income.evidence?.document?.vatAmount,
                    evidence = income.evidence, notas = income.notas, updatedAt = System.currentTimeMillis())
                database.invoiceDao().updatePreservingImageState(invoice.toEntity())
            } else incomeDao.updatePreservingImageState(income.toEntity().copy(updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun updateImageSync(id: Long, documentUuid: String, sourceUri: String?, metadata: com.gastos.domain.model.DriveImageMetadata): Boolean =
        if (id < 0) database.invoiceDao().updateImageSync(-id, documentUuid, sourceUri, metadata.fileId, metadata.webViewLink,
            metadata.pending, metadata.accountId, metadata.contentHash, metadata.error) == 1
        else incomeDao.updateImageSync(id, documentUuid, sourceUri, metadata.fileId, metadata.webViewLink,
            metadata.pending, metadata.accountId, metadata.contentHash, metadata.error) == 1

    override suspend fun deleteIncome(income: Income) =
        if (income.id < 0) database.invoiceDao().deleteByIdentity(-income.id, income.documentUuid)
        else incomeDao.deleteByIdentity(income.id, income.documentUuid)

    override suspend fun getIncomeCount(): Int = getAllIncomes().first().size

    override suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double? =
        getIncomesByDateRange(startDate, endDate).first().takeIf { it.isNotEmpty() }?.sumOf { it.monto }
}
