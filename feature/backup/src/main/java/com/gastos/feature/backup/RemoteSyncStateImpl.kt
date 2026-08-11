package com.gastos.feature.backup

import com.gastos.repository.PremiumStatusProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSyncStateImpl @Inject constructor(
    private val premiumStatus: PremiumStatusProvider,
    private val sheetsExportService: SheetsExportService
) : RemoteSyncState {
    override fun shouldDefer(): Boolean = !premiumStatus.isPremium.value || !sheetsExportService.isSignedIn()
}
