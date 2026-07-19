package com.gastos.feature.settings.di

import com.gastos.feature.settings.BillingManager
import com.gastos.repository.PremiumStatusProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Expone el estado Premium (BillingManager) como [PremiumStatusProvider]
 * para que otros módulos (p. ej. :feature:backup) puedan limitar
 * funciones de pago sin depender de :feature:settings.
 */
@Module
@InstallIn(SingletonComponent::class)
object PremiumStatusModule {

    @Provides
    fun providePremiumStatusProvider(billingManager: BillingManager): PremiumStatusProvider =
        billingManager
}
