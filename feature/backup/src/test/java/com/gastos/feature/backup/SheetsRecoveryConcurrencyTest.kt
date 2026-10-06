@file:Suppress("DEPRECATION")
package com.gastos.feature.backup

import com.gastos.repository.PremiumStatusProvider
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetsRecoveryConcurrencyTest {
    @Test fun `worker queued before recovery checks the persisted pause after acquiring the workbook lock`() = runTest {
        val account = mockk<GoogleSignInAccount> {
            every { id } returns "account"
            every { email } returns "test@example.invalid"
        }
        var paused = false
        val links = mockk<SheetsLinkStore> {
            every { getSpreadsheetId(account) } returns "book"
            every { isRecoveryPaused(any(), "book") } answers { paused }
        }
        val coordinator = SheetsOperationCoordinator()
        val service = spyk(SheetsExportService(mockk(relaxed=true),
            mockk<PremiumStatusProvider> { every { isPremium } returns MutableStateFlow(true) },
            mockk(relaxed=true), mockk(relaxed=true), links, coordinator))
        every { service.getLastSignedInAccount() } returns account
        coordinator.mutex.lock()
        val pending = async {
            runCatching { service.syncDocuments(account,"book",emptyList(),emptyList(),emptyList()) }
        }
        yield()
        paused = true
        coordinator.mutex.unlock()
        assertEquals("SHEETS_RECOVERY_PAUSED", pending.await().exceptionOrNull()?.message)
    }
}
