package com.gastos.repository

import com.gastos.domain.model.ChatMessageRecord
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product

data class BackupDataset(
    val invoices: List<Invoice>,
    val products: List<Product>,
    val incomes: List<Income>,
    val fiscalConfigs: List<CountryFiscalConfig>,
    val chatMessages: List<ChatMessageRecord>,
    val commandOperations: List<com.gastos.domain.model.CommandOperation> = emptyList()
)

data class RestorableSettings(
    val systemInstructions: String = "",
    val defaultCurrency: String = "EUR",
    val defaultCountry: String = "ES",
    val darkMode: String = "system",
    val dashboardWidgetOrder: List<String> = emptyList(),
    val dashboardHiddenWidgets: List<String> = emptyList(),
    val floatingButtonPositions: Map<String, FloatingButtonPosition> = emptyMap()
)

interface BackupDataRepository {
    suspend fun snapshot(): BackupDataset
    suspend fun financialSnapshot(): BackupDataset = snapshot()
    suspend fun documentSnapshot(income: Boolean, id: Long): BackupDataset {
        val all = financialSnapshot()
        val invoiceId = if (income && id < 0) -id else id
        return BackupDataset(
            all.invoices.filter { it.id == invoiceId && (it.tipo == com.gastos.domain.model.InvoiceType.INGRESO) == income && (!income || id < 0) },
            all.products.filter { it.invoiceId == invoiceId && (!income || id < 0) },
            all.incomes.filter { income && it.id == id }, emptyList(), emptyList())
    }
    suspend fun replaceAll(dataset: BackupDataset)
    suspend fun replaceAllWithRestoreMarker(dataset: BackupDataset, restoreId: String)
    suspend fun committedRestoreId(): String?
    suspend fun clearRestoreMarker(restoreId: String)
}

interface BackupSettingsProvider {
    suspend fun snapshotSettings(): RestorableSettings
    suspend fun restoreSettings(settings: RestorableSettings)
}
