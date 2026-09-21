package com.gastos.di

import com.gastos.feature.ai.AIService
import com.gastos.feature.backup.*
import com.gastos.feature.settings.BillingManager
import com.gastos.local.database.AppDatabase
import com.gastos.repository.*
import com.gastos.storage.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for explicitly authorized tests; credentials never leave the app. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveAccountTestEntryPoint {
    fun database(): AppDatabase
    fun reader(): AIService
    fun captureStore(): DocumentCaptureStore
    fun snapshots(): BackupDataRepository
    fun settings(): BackupSettingsProvider
    fun invoices(): InvoiceRepository
    fun incomes(): IncomeRepository
    fun sheets(): SheetsExportService
    fun sync(): SheetsSyncManager
    fun drive(): InvoiceDriveService
    fun images(): InvoiceImageStorage
    fun cache(): DriveImageCache
    fun outbox(): RemoteSyncOutboxRepository
    fun premium(): PremiumStatusProvider
    fun billing(): BillingManager
}
