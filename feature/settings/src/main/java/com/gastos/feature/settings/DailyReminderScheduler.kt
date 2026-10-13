package com.gastos.feature.settings

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Programa los recordatorios diarios según los ajustes persistidos.
 * Idempotente: se puede llamar en cada arranque de la app y al cambiar los ajustes.
 */
@Singleton
class DailyReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun reconcile() {
        val settings = settingsRepository.settings.first()
        if (!settings.remindersEnabled) {
            workManager.cancelUniqueWork(MIDDAY_WORK)
            workManager.cancelUniqueWork(EVENING_WORK)
            return
        }
        if (settings.remindersPerDay >= 2) {
            scheduleSlot(MIDDAY_WORK, ReminderSlot.MIDDAY)
        } else {
            workManager.cancelUniqueWork(MIDDAY_WORK)
        }
        scheduleSlot(EVENING_WORK, ReminderSlot.EVENING)
    }

    private fun scheduleSlot(uniqueName: String, slot: ReminderSlot) {
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(slot.delayUntilNext(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DailyReminderWorker.KEY_SLOT to slot.name))
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    private companion object {
        const val MIDDAY_WORK = "finai-daily-reminder-midday"
        const val EVENING_WORK = "finai-daily-reminder-evening"
    }
}
