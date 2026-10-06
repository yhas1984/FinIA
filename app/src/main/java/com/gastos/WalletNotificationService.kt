package com.gastos

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gastos.domain.model.WalletPaymentParser
import com.gastos.storage.WalletPaymentStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WalletNotificationService : NotificationListenerService() {
    @Inject lateinit var payments: WalletPaymentStore
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!payments.enabled || sbn.packageName != WalletPaymentParser.PACKAGE || sbn.isOngoing) return
        val occurredAt = sbn.notification.`when`
        // Do not import historic notifications when access is enabled or after reconnecting.
        if (occurredAt <= 0 || kotlin.math.abs(System.currentTimeMillis() - occurredAt) > 120_000) return
        val extras = sbn.notification.extras
        val payment = WalletPaymentParser.parse(sbn.packageName, sbn.key, occurredAt,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()) ?: return
        WalletCaptureWorker.schedule(applicationContext, payment, payments.captureSession())
    }
}
