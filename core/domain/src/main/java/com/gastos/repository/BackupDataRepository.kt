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
    val chatMessages: List<ChatMessageRecord>
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
    suspend fun replaceAll(dataset: BackupDataset)
}

interface BackupSettingsProvider {
    suspend fun snapshotSettings(): RestorableSettings
    suspend fun restoreSettings(settings: RestorableSettings)
}
