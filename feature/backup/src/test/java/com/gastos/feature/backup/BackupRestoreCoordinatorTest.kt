package com.gastos.feature.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreCoordinatorTest {
    @Test
    fun `only one restore can run at a time`() = runTest {
        val coordinator: BackupRestoreCoordinator = BackupRestoreCoordinator()
        val release: CompletableDeferred<Unit> = CompletableDeferred()

        assertTrue(coordinator.start(this, "manual") { release.await() })
        runCurrent()
        assertFalse(coordinator.start(this, "cloud") {})
        assertTrue(coordinator.state.value is BackupRestoreState.Running)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(BackupRestoreState.Idle, coordinator.state.value)
        assertTrue(coordinator.start(this, "cloud") {})
        advanceUntilIdle()
        assertEquals(BackupRestoreState.Idle, coordinator.state.value)
    }

    @Test
    fun `cancel releases coordinator and runs cleanup`() = runTest {
        val coordinator: BackupRestoreCoordinator = BackupRestoreCoordinator()
        var hasCleanedUp = false
        assertTrue(
            coordinator.start(this, "manual") {
                try {
                    awaitCancellation()
                } finally {
                    hasCleanedUp = true
                }
            }
        )
        runCurrent()

        coordinator.cancel()
        advanceUntilIdle()

        assertTrue(hasCleanedUp)
        assertEquals(BackupRestoreState.Idle, coordinator.state.value)
        assertFalse(coordinator.isRunning())
    }

    @Test
    fun `commit prevents cancellation until restore completes`() = runTest {
        val coordinator: BackupRestoreCoordinator = BackupRestoreCoordinator()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        assertTrue(coordinator.start(this, "manual") { release.await() })
        runCurrent()

        assertTrue(coordinator.beginCommit())
        coordinator.cancel()
        runCurrent()

        assertTrue(coordinator.isRunning())
        val running: BackupRestoreState.Running =
            coordinator.state.value as BackupRestoreState.Running
        assertFalse(running.canCancel)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(BackupRestoreState.Idle, coordinator.state.value)
    }
}
