package com.gastos

import android.app.Application
import com.gastos.repository.CountryFiscalConfigRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GastosApp : Application() {
    @Inject lateinit var fiscalConfigRepository: CountryFiscalConfigRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching { fiscalConfigRepository.insertDefaultConfigs() }
        }
    }
}
