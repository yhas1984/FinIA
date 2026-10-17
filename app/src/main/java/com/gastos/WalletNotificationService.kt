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

    /**
     * Al conectar (o reconectar tras morir el proceso por MIUI/Doze) recoge los avisos de pago que sigan en la
     * barra. Sin esto, un pago notificado mientras el servicio estaba muerto se pierde para siempre: reportado por
     * el usuario el 16-oct-2026 (CAJE BAR PAREJA 3,00 EUR, aviso publicado 12:12:12 y servicio arrancado a las
     * 13:04 tras la actualización de las 11:51 → el aviso quedó huérfano en la barra y nunca se registró).
     * Las ventanas de deduplicación del store evitan que esto reintroduzca un gasto ya guardado.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedAt = System.currentTimeMillis()
        recoverActiveNotifications()
    }

    /** Un listener desconectado deja de capturar; se marca para que la app pida rebind al volver a primer plano. */
    override fun onListenerDisconnected() {
        connectedAt = 0L
        super.onListenerDisconnected()
    }

    private fun recoverActiveNotifications() {
        try {
            if (!payments.enabled) return
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (sbn.packageName != WalletPaymentParser.PACKAGE || sbn.isOngoing) continue
                // Ventana amplia a propósito: el between pago y publicación puede ser grande y el aviso puede llevar
                // horas en la barra; la deduplicación por contenido del store decide si ya estaba registrado.
                val occurredAt = sbn.notification.`when`
                if (occurredAt <= 0 || System.currentTimeMillis() - occurredAt > RECOVERY_WINDOW_MS) continue
                val extras = sbn.notification.extras
                val payment = WalletPaymentParser.parse(sbn.packageName, sbn.key, occurredAt,
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()) ?: continue
                WalletCaptureWorker.schedule(applicationContext, payment, payments.captureSession())
            }
        } catch (_: Exception) {
            // Best-effort: un fallo aquí no debe tumbar el listener (el sistema podría desactivarlo por un crash).
        }
    }

    companion object {
        /** Margen para recuperar un aviso que quedó en la barra mientras el servicio estaba muerto (6 h). */
        private const val RECOVERY_WINDOW_MS = 6 * 60 * 60_000L
        /** 0 = sin conexión conocida en este proceso; la app pide rebind solo cuando vale 0. */
        @Volatile var connectedAt: Long = 0L
    }

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
