package com.gastos.feature.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupRestoreState {
    data object Idle : BackupRestoreState

    data class Running(
        val sourceLabel: String,
        val stage: String,
        val canCancel: Boolean = true
    ) : BackupRestoreState
}

@Singleton
class BackupRestoreCoordinator @Inject constructor() {
    private val monitor: Any = Any()
    private val mutableState: MutableStateFlow<BackupRestoreState> =
        MutableStateFlow(BackupRestoreState.Idle)
    private var activeJob: Job? = null

    val state: StateFlow<BackupRestoreState> = mutableState.asStateFlow()

    fun start(
        scope: CoroutineScope,
        sourceLabel: String,
        block: suspend () -> Unit
    ): Boolean {
        val job: Job = synchronized(monitor) {
            if (activeJob != null) return false
            mutableState.value = BackupRestoreState.Running(sourceLabel, "Preparando backup...")
            scope.launch(start = CoroutineStart.LAZY) { block() }.also { createdJob ->
                activeJob = createdJob
                createdJob.invokeOnCompletion { finish(createdJob) }
            }
        }
        job.start()
        return true
    }

    fun updateStage(stage: String) {
        val running: BackupRestoreState.Running = mutableState.value as? BackupRestoreState.Running ?: return
        mutableState.value = running.copy(stage = stage)
    }

    fun beginCommit(): Boolean = synchronized(monitor) {
        val job: Job = activeJob ?: return false
        if (job.isCancelled) return false
        val running: BackupRestoreState.Running =
            mutableState.value as? BackupRestoreState.Running ?: return false
        mutableState.value = running.copy(
            stage = "Aplicando cambios. No cierres FinAI...",
            canCancel = false
        )
        true
    }

    fun cancel() {
        synchronized(monitor) {
            val running: BackupRestoreState.Running =
                mutableState.value as? BackupRestoreState.Running ?: return
            if (!running.canCancel) return
            mutableState.value = running.copy(stage = "Cancelando restauración...", canCancel = false)
            activeJob?.cancel(CancellationException("Restauración cancelada por el usuario."))
        }
    }

    fun isRunning(): Boolean = synchronized(monitor) { activeJob != null }

    private fun finish(completedJob: Job) {
        synchronized(monitor) {
            if (activeJob !== completedJob) return
            activeJob = null
            mutableState.value = BackupRestoreState.Idle
        }
    }
}
