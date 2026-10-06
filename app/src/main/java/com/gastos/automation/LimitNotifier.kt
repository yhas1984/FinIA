package com.gastos.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gastos.R
import com.gastos.domain.model.formatMoney
import com.gastos.storage.MonthlyLimitStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

class LimitNotifier @Inject constructor(@ApplicationContext private val context: Context, private val store: MonthlyLimitStore) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe() {
        flow { while (currentCoroutineContext().isActive) { emit(MonthlyLimitStore.currentMonth()); delay(60_000) } }.distinctUntilChanged()
            .flatMapLatest(store::progress).collect { values ->
                for (progress in values) {
                    val limit = progress.limit
                    val spent = progress.spent ?: continue
                    if (!limit.notify || limit.notified || progress.partial || spent < limit.amount) continue
                    if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) continue
                    val manager = context.getSystemService(NotificationManager::class.java)
                    if (!manager.areNotificationsEnabled()) continue
                    manager.createNotificationChannel(NotificationChannel("monthly_limits", context.getString(R.string.limits_tab), NotificationManager.IMPORTANCE_DEFAULT))
                    val intent = android.content.Intent(context, com.gastos.MainActivity::class.java)
                    val pending = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    val notification = NotificationCompat.Builder(context, "monthly_limits").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.limit_notification_title)).setContentText(context.getString(R.string.limit_notification_body, progress.category,
                            formatMoney(spent, limit.currency), formatMoney(limit.amount, limit.currency))).setContentIntent(pending).setAutoCancel(true).build()
                    manager.notify(limit.id.hashCode(), notification)
                    store.markNotified(limit)
                }
            }
    }
}
