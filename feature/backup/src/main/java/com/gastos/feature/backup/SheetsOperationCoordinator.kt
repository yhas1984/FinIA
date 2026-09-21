package com.gastos.feature.backup

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SheetsOperationCoordinator @Inject constructor() {
    val syncMutex: Mutex = Mutex()
    val localDeletionMutex: Mutex = Mutex()
    val mutex: Mutex = Mutex()
    val migrationMutex: Mutex = Mutex()
}
