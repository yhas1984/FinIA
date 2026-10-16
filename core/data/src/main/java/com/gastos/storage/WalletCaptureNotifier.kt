package com.gastos.storage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gastos.data.R
import com.gastos.domain.model.WalletPayment
import com.gastos.domain.model.formatMoney

/**
 * Confirma al usuario que un pago detectado de Google Wallet ya se añadió como gasto.
 * Es un aviso best-effort: nunca debe romper la captura del gasto.
 */
class WalletCaptureNotifier(private val context: Context) {

    fun notifyCaptured(payment: WalletPayment) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.wallet_capture_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentIntent = launchIntent?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val body = context.getString(
                R.string.wallet_capture_body, payment.merchant, formatMoney(payment.amount, payment.currency)
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_finai)
                .setContentTitle(context.getString(R.string.wallet_capture_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID_PREFIX + payment.eventId.hashCode(), notification)
        } catch (_: Exception) {
            // El aviso es informativo: un fallo aquí jamás debe afectar al gasto registrado.
        }
    }

    private companion object {
        const val CHANNEL_ID = "wallet_capture"
        // Base estable; el hash del evento evita colisiones entre gastos distintos.
        const val NOTIFICATION_ID_PREFIX = 20700
    }
}
