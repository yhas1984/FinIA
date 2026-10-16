package com.gastos.feature.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Publica el recordatorio diario. Si no se puede notificar, termina en silencio. */
@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT)
            ?.let { name -> runCatching { ReminderSlot.valueOf(name) }.getOrNull() }
            ?: ReminderSlot.EVENING
        postReminder(context, slot)
        return Result.success()
    }

    companion object {
        const val KEY_SLOT = "slot"
    }
}

internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

internal fun postReminder(context: Context, slot: ReminderSlot) {
    try {
        if (!hasNotificationPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(reminderChannel(context))
        val (titleRes, bodyRes) = when (slot) {
            ReminderSlot.MIDDAY -> R.string.reminder_midday_title to R.string.reminder_midday_body
            ReminderSlot.EVENING -> R.string.reminder_evening_title to R.string.reminder_evening_body
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val body = context.getString(bodyRes)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_finai)
            .setContentTitle(context.getString(titleRes))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(REMINDER_NOTIFICATION_ID_BASE + slot.ordinal, notification)
    } catch (_: Exception) {
        // Un recordatorio jamás debe tumbar el trabajo en segundo plano.
    }
}

internal fun reminderChannel(context: Context): NotificationChannel =
    NotificationChannel(
        REMINDER_CHANNEL_ID,
        context.getString(R.string.reminder_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT
    )

private const val REMINDER_CHANNEL_ID = "daily_reminders"
private const val REMINDER_NOTIFICATION_ID_BASE = 20800
