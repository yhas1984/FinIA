package com.gastos.di

import com.gastos.repository.*
import com.gastos.repository.impl.*
import com.gastos.feature.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInvoiceRepository(impl: InvoiceRepositoryImpl): InvoiceRepository

    @Binds
    @Singleton
    abstract fun bindProductRepository(impl: ProductRepositoryImpl): ProductRepository

    @Binds
    @Singleton
    abstract fun bindIncomeRepository(impl: IncomeRepositoryImpl): IncomeRepository



    @Binds
    @Singleton
    abstract fun bindCountryFiscalConfigRepository(impl: CountryFiscalConfigRepositoryImpl): CountryFiscalConfigRepository

    @Binds
    @Singleton
    abstract fun bindExchangeRateProvider(impl: ExchangeRateProviderImpl): ExchangeRateProvider

    @Binds
    @Singleton
    abstract fun bindChatMessageRepository(impl: ChatMessageRepositoryImpl): ChatMessageRepository

    @Binds
    @Singleton
    abstract fun bindBackupDataRepository(impl: BackupDataRepositoryImpl): BackupDataRepository

    @Binds
    @Singleton
    abstract fun bindBackupSettingsProvider(impl: SettingsRepository): BackupSettingsProvider

    @Binds
    @Singleton
    abstract fun bindDashboardLayoutPreference(impl: SettingsRepository): DashboardLayoutPreference
}
