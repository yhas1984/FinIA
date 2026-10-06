package com.gastos.automation

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import com.gastos.R
import com.gastos.domain.model.DocumentKind
import com.gastos.storage.MonthlyLimitStore
import kotlin.math.roundToInt

enum class AutomationSection { CATEGORIES, RULES, LIMITS, WALLET }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    onBack: () -> Unit,
    model: AutomationViewModel = hiltViewModel(),
    initialSection: AutomationSection? = null,
    onOpenMovement: (String) -> Unit = {}
) {
    val categories by model.categories.collectAsStateWithLifecycle()
    val rules by model.rules.collectAsStateWithLifecycle()
    val payments by model.payments.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val saveState by model.saveState.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    val walletDetailsState = rememberSaveableStateHolder()
    var expanded by rememberSaveable { mutableIntStateOf(initialSection?.ordinal ?: -1) }
    var categoryKind by rememberSaveable { mutableStateOf(DocumentKind.EXPENSE) }
    var ruleKind by rememberSaveable { mutableStateOf(DocumentKind.EXPENSE) }
    var expandedCategory by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(MonthlyLimitStore.currentMonth()) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var wallet by remember { mutableStateOf(model.walletEnabled) }
    var access by remember { mutableStateOf(false) }
    var walletTop by remember { mutableIntStateOf(-1) }
    var directEntryPending by rememberSaveable { mutableStateOf(initialSection == AutomationSection.WALLET) }
    DisposableEffect(lifecycle, context) {
        fun refresh() {
            access = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            wallet = model.walletEnabled
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    LaunchedEffect(walletTop, directEntryPending) {
        if (directEntryPending && walletTop >= 0) {
            scroll.scrollTo(walletTop)
            directEntryPending = false
        }
    }
    Scaffold(
        topBar = { com.gastos.common.design.EssentialHeader(stringResource(R.string.automation_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            AutomationSection.entries.forEach { section ->
                val open = expanded == section.ordinal
                Column(Modifier.fillMaxWidth().then(if (section == AutomationSection.WALLET) Modifier.onGloballyPositioned {
                    walletTop = it.positionInParent().y.roundToInt()
                } else Modifier)) {
                    OrganizationHeader(section, open) { expanded = if (open) -1 else section.ordinal }
                    if (open) {
                        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (section) {
                                AutomationSection.CATEGORIES -> CategoriesSection(categories, categoryKind, { categoryKind = it },
                                    expandedCategory, { expandedCategory = it }, showArchived, { showArchived = it }, busy,
                                    onCreate = { parent -> model.openCategory(categoryKind, parent) },
                                    onRename = { model.openCategory(it.kind, category = it) }, onArchive = model::archive)
                                AutomationSection.RULES -> RulesSection(categories, rules, ruleKind, { ruleKind = it }, busy,
                                    onCreate = { model.openRule(ruleKind) }, onEdit = { model.openRule(it.kind, it) },
                                    onToggle = model::toggleRule, onDelete = model::removeRule)
                                AutomationSection.LIMITS -> LimitsSection(model, month, { month = it }, busy,
                                    onCreate = { model.openLimit(month, locale) }, onEdit = { model.openLimit(month, locale, it) },
                                    onDelete = model::removeLimit)
                                AutomationSection.WALLET -> {
                                    WalletCaptureControls(wallet, access,
                                        onEnabledChange = { enabled ->
                                            if (enabled) consent = true else if (model.wallet(false)) wallet = false
                                        }, onRequestAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                                    walletDetailsState.SaveableStateProvider("wallet-details") {
                                        WalletDetails(payments, busy, wallet && access, model::undoPayment,
                                            model::retryPayment, model::ignorePayment, onOpenMovement, model.walletCaptureSession)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    editor?.let { draft -> OrganizationEditorSheet(draft, categories, saveState, model.hasChanges,
        onChange = model::updateDraft, onClose = model::closeEditor, onSave = { model.saveEditor(locale) }) }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text(stringResource(R.string.wallet_capture_title)) },
        text = { Text(stringResource(R.string.wallet_consent)) },
        confirmButton = { TextButton(onClick = {
            if (!model.wallet(true)) return@TextButton
            wallet = true; consent = false
            if (!access) context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text(stringResource(if (access) R.string.wallet_activate else R.string.wallet_grant)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.automation_dismiss)) } })
}
