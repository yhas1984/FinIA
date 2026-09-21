package com.gastos.feature.backup

import android.content.Context
import com.gastos.repository.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupViewModelReliabilityTest {
    @Test fun `successful backup followed by list failure remains a successful backup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val context = mockk<Context>(relaxed=true)
            every { context.getString(any()) } answers { "message-${firstArg<Int>()}" }
            every { context.getString(any(),any()) } answers { "saved" }
            val status = MutableStateFlow(CloudBackupStatus(false,null,null))
            val preferences = mockk<CloudBackupPreferences>(relaxed=true)
            every { preferences.status() } answers { status.value }
            every { preferences.statusFlow } returns status
            every { preferences.recordSuccess(any()) } answers { status.value = CloudBackupStatus(false,123L,null) }
            val cloud = mockk<CloudBackupService>()
            coEvery { cloud.createBackup() } returns CloudBackupInfo("file","copy",123,20,BackupPreview(123,"test",15,1,0,0,0))
            coEvery { cloud.listBackups() } throws java.io.IOException("synthetic list failure")
            val export = mockk<SheetsExportService>(relaxed=true)
            every { export.getLastSignedInAccount() } returns null
            val sync = mockk<SheetsSyncManager>(relaxed=true)
            every { sync.operations } returns MutableStateFlow(emptyList())
            val model = BackupViewModel(context,mockk(relaxed=true),cloud,mockk(relaxed=true),preferences,
                BackupRestoreCoordinator(),export,sync,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),
                mockk { every { isPremium } returns MutableStateFlow(false) }, mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
            advanceUntilIdle()
            model.createCloudBackupNow()
            advanceUntilIdle()
            assertTrue(model.uiState.value.backupResult!!.success)
            assertEquals(123L,model.uiState.value.cloudBackupStatus.lastSuccessAt)
            assertNotNull(model.uiState.value.cloudListError)
            assertNull(model.uiState.value.error)
            verify(exactly=0) { preferences.recordError(any()) }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `linked workbook opens immediately and a double tap waits for one remote sync`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = mockk<com.google.android.gms.auth.api.signin.GoogleSignInAccount>(relaxed=true)
            val export = mockk<SheetsExportService>(relaxed=true) {
                every { getLastSignedInAccount() } returns account
                every { isSignedIn() } returns true
            }
            val sync = mockk<SheetsSyncManager>(relaxed=true) {
                every { operations } returns MutableStateFlow(emptyList())
                every { isEnabled(account) } returns true
                every { getStoredId(account) } returns "existing-book"
            }
            val status = CloudBackupStatus(false,null,null)
            val prefs = mockk<CloudBackupPreferences> {
                every { status() } returns status
                every { statusFlow } returns MutableStateFlow(status)
            }
            val model = BackupViewModel(mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),prefs,
                BackupRestoreCoordinator(),export,sync,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),
                mockk { every { isPremium } returns MutableStateFlow(false) },mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
            advanceUntilIdle()
            model.clearSheetsResult()
            assertEquals("https://docs.google.com/spreadsheets/d/existing-book/edit",model.uiState.value.sheetsUrl)
            assertTrue(model.uiState.value.hasSheetLink)
            coVerify(exactly=0) { export.exportToSheets(any(),any(),any(),any(),any()) }
            coEvery { sync.syncChanges() } coAnswers { kotlinx.coroutines.delay(1_000); 0 }
            model.exportToSheets()
            model.exportToSheets()
            runCurrent()
            assertTrue(model.uiState.value.isExportingSheets)
            assertFalse(model.uiState.value.sheetsSynced)
            advanceUntilIdle()
            assertFalse(model.uiState.value.isExportingSheets)
            assertTrue(model.uiState.value.sheetsSynced)
            coVerify(exactly=1) { sync.syncChanges() }
            coVerify(exactly=0) { export.exportToSheets(any(),any(),any(),any(),any()) }
        } finally { Dispatchers.resetMain() }
    }

}
