package com.gastos.automation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gastos.R
import com.gastos.domain.model.PaymentEvent
import com.gastos.domain.model.WalletCaptureStatus
import com.gastos.domain.model.WalletPaymentPolicy
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun WalletCaptureControls(
    enabled: Boolean, hasNotificationAccess: Boolean,
    onEnabledChange: (Boolean) -> Unit, onRequestAccess: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onEnabledChange)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wallet_enable), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(stringResource(when {
            !enabled -> R.string.wallet_disabled
            !hasNotificationAccess -> R.string.wallet_access_needed
            else -> R.string.wallet_active
        }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enabled && !hasNotificationAccess) Text(stringResource(R.string.wallet_access_needed),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hasNotificationAccess) OutlinedButton(onClick = onRequestAccess) { Text(stringResource(R.string.wallet_notification_access)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletDetails(
    payments: List<WalletPaymentItem>, busy: Boolean, captureAllowed: Boolean,
    onUndo: (String, PaymentEvent) -> Unit, onRetry: (String) -> Unit,
    onIgnore: (String) -> Unit, onOpen: (String) -> Unit, activeSession: String
) {
    var how by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleCount by rememberSaveable { mutableIntStateOf(20) }
    Disclosure(R.string.wallet_how, how, { how = !how }) {
        Text(stringResource(R.string.wallet_description), style = MaterialTheme.typography.bodyMedium)
    }
    Disclosure(R.string.wallet_privacy, privacy, { privacy = !privacy }) {
        Text(stringResource(R.string.wallet_consent), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.wallet_detected), style = MaterialTheme.typography.titleSmall)
    if (payments.isEmpty()) Text(stringResource(R.string.wallet_no_payments), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    payments.take(visibleCount).forEach { payment -> key(payment.id) {
        Column(Modifier.fillMaxWidth().clickable { selectedId = payment.id }.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(payment.merchant ?: stringResource(R.string.wallet_legacy_payment), style = MaterialTheme.typography.titleSmall)
            Text(walletAmount(payment), style = MaterialTheme.typography.bodyLarge)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(payment.postedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(walletStatus(payment, captureAllowed && (payment.capture == null || payment.capture.session == activeSession)), style = MaterialTheme.typography.bodySmall,
                color = if (payment.capture?.status == WalletCaptureStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    } }
    if (payments.size > visibleCount) TextButton(onClick = { visibleCount += 20 }) { Text(stringResource(R.string.wallet_show_more)) }
    payments.firstOrNull { it.id == selectedId }?.let { payment ->
        ModalBottomSheet(onDismissRequest = { if (!busy) selectedId = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(payment.merchant ?: stringResource(R.string.wallet_legacy_payment), style = MaterialTheme.typography.titleLarge)
                Text(walletAmount(payment), style = MaterialTheme.typography.headlineSmall)
                Text(DateFormat.getDateTimeInstance().format(Date(payment.postedAt)), style = MaterialTheme.typography.bodyMedium)
                Text(walletStatus(payment, captureAllowed && (payment.capture == null || payment.capture.session == activeSession)))
                Text(stringResource(R.string.wallet_payment_detail), style = MaterialTheme.typography.bodySmall)
                payment.event?.let { event ->
                    if (payment.movementExists) OutlinedButton(onClick = { selectedId = null; onOpen(event.documentUuid) }, enabled = !busy) {
                        Text(stringResource(R.string.wallet_open_expense))
                    }
                    if (payment.canUndo) TextButton(onClick = { onUndo(payment.id, event) }, enabled = !busy) { Text(stringResource(R.string.wallet_undo)) }
                    else if (!event.undone && payment.movementExists) Text(stringResource(R.string.wallet_undo_unavailable), style = MaterialTheme.typography.bodySmall)
                }
                payment.capture?.let { capture ->
                    if (!captureAllowed) Text(stringResource(R.string.wallet_retry_disabled), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onRetry(payment.id) }, enabled = !busy && captureAllowed) { Text(stringResource(R.string.wallet_retry)) }
                    if (capture.status != WalletCaptureStatus.IGNORED) TextButton(onClick = { onIgnore(payment.id) }, enabled = !busy) { Text(stringResource(R.string.wallet_ignore)) }
                }
            }
        }
    }
}

@Composable
private fun walletAmount(payment: WalletPaymentItem): String {
    val amount = payment.amount ?: return stringResource(R.string.wallet_amount_unknown)
    val currency = payment.currency ?: return stringResource(R.string.wallet_amount_unknown)
    return NumberFormat.getCurrencyInstance(LocalLocale.current.platformLocale).apply {
        this.currency = java.util.Currency.getInstance(currency)
    }.format(amount)
}

@Composable
private fun walletStatus(payment: WalletPaymentItem, captureAllowed: Boolean): String = stringResource(when {
    payment.capture?.status == WalletCaptureStatus.IGNORED -> R.string.wallet_ignored
    payment.capture != null && !captureAllowed -> R.string.wallet_paused
    payment.capture != null && payment.capture.attempts >= WalletPaymentPolicy.MAX_ATTEMPTS -> R.string.wallet_failed_manual
    payment.capture?.status == WalletCaptureStatus.FAILED -> R.string.wallet_failed_retrying
    payment.capture != null -> R.string.wallet_waiting
    payment.event?.undone == true -> R.string.wallet_undone
    !payment.movementExists -> R.string.wallet_movement_removed
    else -> R.string.wallet_registered
})
