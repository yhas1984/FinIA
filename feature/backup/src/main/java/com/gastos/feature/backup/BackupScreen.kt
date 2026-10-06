package com.gastos.feature.backup

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.gastos.common.design.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

enum class DataPage { OVERVIEW, SHEETS, DRIVE, COPIES, REPORTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPremium: () -> Unit = {},
    page: DataPage = DataPage.OVERVIEW,
    onNavigateToSection: (DataPage) -> Unit = {},
    onNavigateToBank: () -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = uiState.isRestoring) { /* Restore cancellation uses its explicit progress action. */ }
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    val reportCategories by viewModel.reportCategories.collectAsStateWithLifecycle()
    var reportFilter by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver(
        save = { listOf(it.startInclusive ?: -1L, it.endExclusive ?: -1L, it.kind?.name.orEmpty(), it.category.orEmpty(), it.subcategory.orEmpty()) },
        restore = { com.gastos.domain.model.ReportFilter((it[0] as Long).takeIf { v -> v != -1L }, (it[1] as Long).takeIf { v -> v != -1L },
            (it[2] as String).takeIf(String::isNotEmpty)?.let(com.gastos.domain.model.DocumentKind::valueOf), (it[3] as String).takeIf(String::isNotEmpty), (it[4] as String).takeIf(String::isNotEmpty)) }
    )) { mutableStateOf(viewModel.reportFilter) }
    var reportFilterValid by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(showReportDialog) { if (showReportDialog) viewModel.loadReportCategories() }
    var reportDetail by rememberSaveable { mutableStateOf(ReportDetail.SUMMARY) }
    var reportFormat by rememberSaveable { mutableStateOf(ReportFormat.CSV) }
    var showSheetsAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmSheetsAction by rememberSaveable { mutableStateOf<String?>(null) }
    var exportMode by rememberSaveable { mutableStateOf(BackupMode.DATA_ONLY) }
    var showPasswordSetup by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var passwordValidationError by remember { mutableStateOf<String?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var showDeleteCloudConfirmation by rememberSaveable { mutableStateOf(false) }
    var externalLinkError by remember { mutableStateOf<String?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri ->
        uri?.let { viewModel.exportEncryptedBackup(context, it, exportMode) }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectManualBackup(context, it) }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            viewModel.exportToCsv(context, it)
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let {
            viewModel.exportToPdf(context, it)
        }
    }

    // Launcher para Google Sign-In con permisos de Sheets.
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result.data)
    }

    if (showReportDialog) {
        AlertDialog(onDismissRequest = { showReportDialog = false }, title = { Text(stringResource(R.string.export_report)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    ReportFilters(reportCategories, reportFilter) { filter, valid -> reportFilter = filter; reportFilterValid = valid }
                    TextButton(onClick = { viewModel.reportFilter = reportFilter; viewModel.reportDetail = reportDetail; showReportDialog = false; viewModel.previewReport(context, reportFormat) }, enabled = reportFilterValid) {
                        Text(stringResource(com.gastos.common.R.string.essential_preview))
                    }
                    if (reportFormat == ReportFormat.PDF) ReportDetail.entries.forEach { detail ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportDetail == detail, onClick = { reportDetail = detail })
                            Text(stringResource(if (detail == ReportDetail.SUMMARY) R.string.report_detail_summary else R.string.report_detail_full))
                        }
                    }
                    ReportFormat.entries.forEach { format ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportFormat == format, onClick = { reportFormat = format })
                            Text(format.name)
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = {
                    viewModel.reportFilter = reportFilter
                    viewModel.reportDetail = reportDetail
                    showReportDialog = false
                    val name = "finai_report_${System.currentTimeMillis()}.${reportFormat.extension}"
                    if (reportFormat == ReportFormat.CSV) exportCsvLauncher.launch(name) else exportPdfLauncher.launch(name)
                }, enabled = reportFilterValid) { Text(stringResource(R.string.save_report)) }
            }, dismissButton = {
                TextButton(onClick = { viewModel.reportFilter = reportFilter; viewModel.reportDetail = reportDetail; showReportDialog = false; viewModel.shareReport(context, reportFormat) }, enabled = reportFilterValid) { Text(stringResource(R.string.share_report)) }
            })
    }
    uiState.sheetsRecoverySnapshots?.let { snapshots ->
        if (uiState.sheetsRecoveryPreview == null) AlertDialog(onDismissRequest = viewModel::dismissSheetsRecovery,
            title = { Text(stringResource(R.string.sheets_recovery_title)) },
            text = { Column {
                if (snapshots.isEmpty()) Text(stringResource(R.string.sheets_recovery_empty))
                snapshots.forEach { snapshot -> TextButton(onClick = { viewModel.previewSheetsRecovery(snapshot) }, enabled = !uiState.sheetsRecoveryLoading) {
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.createdAt)))
                } }
                if (uiState.sheetsRecoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                uiState.sheetsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = viewModel::dismissSheetsRecovery, enabled = !uiState.sheetsRecoveryLoading) { Text(stringResource(R.string.cancel_action)) } })
    }
    uiState.sheetsRecoveryPreview?.let { preview ->
        AlertDialog(onDismissRequest = viewModel::dismissSheetsRecovery,
            title = { Text(stringResource(R.string.sheets_recovery_title)) },
            text = { Column {
                Text(stringResource(R.string.sheets_recovery_scope, preview.restoredRows, preview.removedRows))
                Text(stringResource(R.string.sheets_recovery_pause))
                if (uiState.sheetsRecoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                uiState.sheetsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = viewModel::restoreSheetsRecovery, enabled = !uiState.sheetsRecoveryLoading) { Text(stringResource(R.string.sheets_recovery_apply)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissSheetsRecovery, enabled = !uiState.sheetsRecoveryLoading) { Text(stringResource(R.string.cancel_action)) } })
    }
    confirmSheetsAction?.let { action ->
        AlertDialog(onDismissRequest = { confirmSheetsAction = null },
            title = { Text(stringResource(if (action == "rebuild") R.string.sheets_rebuild else R.string.sheets_takeover)) },
            text = { Text(stringResource(if (action == "rebuild") R.string.sheets_rebuild_explanation else R.string.sheets_takeover_explanation)) },
            confirmButton = { TextButton(onClick = {
                confirmSheetsAction = null
                if (action == "rebuild") viewModel.rebuildSheets() else viewModel.takeOverSheets()
            }) { Text(stringResource(R.string.confirm_sheet_action)) } },
            dismissButton = { TextButton(onClick = { confirmSheetsAction = null }) { Text(stringResource(R.string.cancel_report)) } })
    }

    Scaffold(
        topBar = { EssentialHeader(stringResource(when (page) {
            DataPage.OVERVIEW -> com.gastos.common.R.string.essential_data
            DataPage.SHEETS -> com.gastos.common.R.string.essential_sheets
            DataPage.DRIVE -> com.gastos.common.R.string.essential_drive
            DataPage.COPIES -> com.gastos.common.R.string.essential_copies
            DataPage.REPORTS -> com.gastos.common.R.string.essential_reports
        }), { if (!uiState.isRestoring) onNavigateBack() }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            externalLinkError?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (page == DataPage.OVERVIEW) {
                EssentialRow(stringResource(com.gastos.common.R.string.bank_statements_title), stringResource(com.gastos.common.R.string.bank_statements_hint), Icons.Default.AccountBalance, onNavigateToBank)
                Surface(shape = MaterialTheme.shapes.large) { Column {
                    EssentialRow(stringResource(com.gastos.common.R.string.essential_sheets), stringResource(com.gastos.common.R.string.essential_sheets_hint), Icons.Default.TableChart, { onNavigateToSection(DataPage.SHEETS) })
                    HorizontalDivider()
                    EssentialRow(stringResource(com.gastos.common.R.string.essential_drive), stringResource(com.gastos.common.R.string.essential_drive_hint), Icons.Default.CloudQueue, { onNavigateToSection(DataPage.DRIVE) })
                    HorizontalDivider()
                    EssentialRow(stringResource(com.gastos.common.R.string.essential_copies), stringResource(com.gastos.common.R.string.essential_copies_hint), Icons.Default.Lock, { onNavigateToSection(DataPage.COPIES) })
                    HorizontalDivider()
                    EssentialRow(stringResource(com.gastos.common.R.string.essential_reports), stringResource(com.gastos.common.R.string.essential_reports_hint), Icons.Default.Description, { onNavigateToSection(DataPage.REPORTS) })
                } }
            }
            if (page == DataPage.OVERVIEW || page == DataPage.DRIVE ||
                (uiState.isSignedIn && (page == DataPage.SHEETS || page == DataPage.COPIES))) {
            // Cuenta Google compartida por Drive y Sheets
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.google_account_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.isSignedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.google_connected))
                                Text(uiState.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (page == DataPage.OVERVIEW || page == DataPage.DRIVE) TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.google_sign_out))
                        }
                    } else {
                        Text(
                            stringResource(R.string.google_connect_backup_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.google_sign_in))
                        }
                    }
                }
            }

            }

            if (page == DataPage.DRIVE) ImageSyncStatusPanel()
            if (page == DataPage.COPIES && uiState.isLoading && !uiState.isRestoring) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (page == DataPage.COPIES) {
            // Backup portable cifrado
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_encrypted_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!uiState.isBackupKeyConfigured) {
                        Button(onClick = { showPasswordSetup = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Password, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.configure_recovery_password_action))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.encryption_configured))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = exportMode == BackupMode.DATA_ONLY, onClick = { exportMode = BackupMode.DATA_ONLY })
                        Text(stringResource(R.string.backup_mode_data))
                        RadioButton(selected = exportMode == BackupMode.COMPLETE, onClick = { exportMode = BackupMode.COMPLETE })
                        Text(stringResource(R.string.backup_mode_complete))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", locale).format(Date())
                                exportBackupLauncher.launch("finai_backup_$timestamp.$BACKUP_FILE_EXTENSION")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isBackupKeyConfigured && !uiState.isLoading && !uiState.isRestoring
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.export))
                        }
                        OutlinedButton(
                            onClick = { importBackupLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading && !uiState.isRestoring
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.restore))
                        }
                    }
                    EssentialSection(stringResource(com.gastos.common.R.string.essential_how_it_works)) {
                        Text(stringResource(R.string.backup_encrypted_description), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.backup_mode_help), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = stringResource(R.string.keep_password_safe_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            }

            if (page == DataPage.COPIES) {
            // Backup automático Premium
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.drive_backup_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    uiState.cloudBackupStatus.lastSuccessAt?.let { timestamp ->
                        Text(stringResource(R.string.last_backup_prefix, SimpleDateFormat("dd/MM/yyyy HH:mm", locale).format(Date(timestamp))),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    EssentialSection(stringResource(com.gastos.common.R.string.essential_how_it_works)) {
                        Text(stringResource(R.string.drive_backup_description), style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        !uiState.isPremium -> Button(onClick = onNavigateToPremium, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.unlock_premium_backup_action))
                        }
                        !uiState.isSignedIn -> OutlinedButton(
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.connect_google_action)) }
                        !uiState.isBackupKeyConfigured -> Button(
                            onClick = { showPasswordSetup = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.configure_recovery_password_action)) }
                        else -> {
                            if (uiState.cloudBackupStatus.needsConsentConfirmation) {
                                Text(stringResource(R.string.cloud_confirm_legacy_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.daily_backup_status))
                                    Text(stringResource(R.string.daily_backup_status_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = uiState.cloudBackupStatus.enabled,
                                    onCheckedChange = viewModel::setAutomaticCloudBackup
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = viewModel::createCloudBackupNow,
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isCloudLoading
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.create_backup))
                                }

                            }
                            uiState.cloudBackupStatus.lastError?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = viewModel::loadCloudBackups, enabled = !uiState.isCloudLoading) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(stringResource(R.string.refresh_backup_list))
                            }
                            uiState.cloudListError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (uiState.cloudBackups.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                Text(stringResource(R.string.available_backups_section), style = MaterialTheme.typography.titleSmall)
                                uiState.cloudBackups.forEach { backup ->
                                    CloudBackupRow(
                                        backup = backup,
                                        locale = locale,
                                        enabled = !uiState.isCloudLoading && !uiState.isRestoring,
                                        onRestore = { viewModel.requestCloudRestore(backup) }
                                    )
                                }
                                TextButton(
                                    onClick = { showDeleteCloudConfirmation = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !uiState.isCloudLoading
                                ) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.delete_drive_backups_action), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            }

            if (page == DataPage.SHEETS) {
            // Exportación a Google Sheets
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(if (uiState.hasSheetLink) R.string.sheets_linked_workbook else R.string.sheets_setup_workbook),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (uiState.hasSheetLink) {
                        Text(stringResource(R.string.sheets_queue_state, uiState.sheetsPending, uiState.sheetsFailed), style = MaterialTheme.typography.bodySmall)
                        if (uiState.sheetsSynced && uiState.sheetsPending == 0 && uiState.sheetsFailed == 0 && uiState.sheetsError == null && uiState.sheetsSyncError == null)
                            Text(stringResource(R.string.sheets_all_synced), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (!uiState.isPremium) {
                        Button(
                            onClick = onNavigateToPremium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.unlock_premium_for_sheets_action))
                        }
                    } else if (!uiState.isSignedIn) {
                        OutlinedButton(
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.connect_google_account_action))
                        }
                    } else {
                        if (uiState.hasSheetLink) {
                            Button(onClick = { viewModel.exportToSheets() }, enabled = !uiState.isExportingSheets,
                                modifier = Modifier.fillMaxWidth()) {
                                if (uiState.isExportingSheets) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary)
                                else Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (uiState.isExportingSheets) R.string.sheets_syncing_changes else R.string.sync_changes))
                            }
                            if (uiState.sheetsRecoveryPaused) Text(stringResource(R.string.sheets_recovery_pause), style = MaterialTheme.typography.bodySmall)

                        } else {
                            Button(
                                onClick = { viewModel.exportToSheets() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isExportingSheets
                            ) {
                                if (uiState.isExportingSheets) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (uiState.isExportingSheets) stringResource(R.string.exporting) else stringResource(R.string.export_to_google_sheets))
                            }
                        }
                    }

                    if (uiState.appearanceApplying) Text(stringResource(R.string.sheets_applying_appearance), style = MaterialTheme.typography.bodySmall)
                    uiState.appearanceError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.retrySheetsAppearance() }, enabled = !uiState.appearanceApplying && !uiState.isExportingSheets) { Text(stringResource(R.string.sheets_retry_appearance)) }
                    }
                    (uiState.sheetsError ?: uiState.sheetsSyncError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    // Persistent workbook access is independent of the last sync result.
                    uiState.sheetsUrl?.let { url ->
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { externalLinkError = openTrustedUrl(context = context, rawUrl = url, allowedHosts = setOf("docs.google.com")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.open_in_google_sheets_action))
                        }
                    }
                    EssentialSection(stringResource(com.gastos.common.R.string.essential_how_it_works)) {
                        Text(stringResource(R.string.google_sheets_description_localized), style = MaterialTheme.typography.bodySmall)
                    }
                    if (uiState.isSignedIn && uiState.isPremium && uiState.hasSheetLink) {
                            TextButton(onClick = { showSheetsAdvanced = !showSheetsAdvanced }) { Text(stringResource(R.string.sheets_advanced)) }
                            if (showSheetsAdvanced) {
                                TextButton(onClick = viewModel::loadSheetsRecovery, enabled = !uiState.isExportingSheets && !uiState.sheetsRecoveryLoading) { Text(stringResource(R.string.sheets_recovery_title)) }
                                TextButton(onClick = { viewModel.retrySheetsAppearance(reset = true) }, enabled = !uiState.isExportingSheets && !uiState.appearanceApplying) { Text(stringResource(R.string.sheets_reset_appearance)) }
                                TextButton(onClick = { confirmSheetsAction = "rebuild" }, enabled = !uiState.isExportingSheets) { Text(stringResource(R.string.sheets_rebuild)) }
                                TextButton(onClick = { confirmSheetsAction = "takeover" }, enabled = !uiState.isExportingSheets) { Text(stringResource(R.string.sheets_takeover)) }
                            }
                    }

                }
            }

            }

            if (page == DataPage.COPIES) {
            // Resultado del backup
            uiState.backupResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (result.success) stringResource(R.string.backup_completed_message) else stringResource(R.string.error),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = result.message)
                    }
                }
            }

            }

            if (page == DataPage.REPORTS) {
            // Exportar datos
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.export_share_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.export_share_description_localized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { showReportDialog = true }, enabled = !uiState.isExporting, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_report))
                    }
                    if (uiState.isExporting) {
                        LinearProgressIndicator(progress = { uiState.reportProgress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = viewModel::cancelReport) { Text(stringResource(R.string.cancel_report)) }
                    }
                }
            }

            }

            if (page == DataPage.REPORTS) {
            // Resultado de exportación
            uiState.exportResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (result.success) stringResource(R.string.export_completed_message) else stringResource(R.string.error),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = result.message)
                    }
                }
            }

            }

            // Error
            uiState.error?.takeIf { page == DataPage.COPIES || page == DataPage.OVERVIEW }?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.error),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }

    if (showPasswordSetup) {
        AlertDialog(
            onDismissRequest = { showPasswordSetup = false },
            title = { Text(stringResource(R.string.recovery_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.recovery_password_help_text))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password_label)) },
                        visualTransformation = rememberTimedPasswordVisualTransformation(password),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = passwordConfirmation,
                        onValueChange = { passwordConfirmation = it },
                        label = { Text(stringResource(R.string.confirm_password_label)) },
                        visualTransformation = rememberTimedPasswordVisualTransformation(passwordConfirmation),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    passwordValidationError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                val tooShortMessage = stringResource(R.string.backup_password_too_short)
                val mismatchMessage = stringResource(R.string.backup_password_mismatch)
                TextButton(onClick = {
                    passwordValidationError = when {
                        password.length < 8 -> tooShortMessage
                        password != passwordConfirmation -> mismatchMessage
                        else -> null
                    }
                    if (passwordValidationError != null) return@TextButton
                    viewModel.configureBackupPassword(password, passwordConfirmation)
                    password = ""
                    passwordConfirmation = ""
                    passwordValidationError = null
                    showPasswordSetup = false
                }) { Text(stringResource(R.string.save_action)) }
            },
            dismissButton = { TextButton(onClick = { passwordValidationError = null; showPasswordSetup = false }) { Text(stringResource(R.string.cancel_action)) } }
        )
    }

    uiState.pendingRestore?.let { pending ->
        val running = uiState.restoreState as? BackupRestoreState.Running
        AlertDialog(
            onDismissRequest = { if (running == null) viewModel.dismissRestore() },
            title = { Text(stringResource(R.string.restore_backup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.restore_summary_localized, pending.preview.invoiceCount, pending.preview.productCount, pending.preview.incomeCount, pending.preview.imageCount)
                    )
                    Text(
                        stringResource(R.string.restore_replaces_data_warning),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(stringResource(R.string.recovery_password_title)) },
                        visualTransformation = rememberTimedPasswordVisualTransformation(restorePassword),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = running == null,
                        singleLine = true
                    )
                    uiState.restoreSheetsImpact?.let { impact ->
                        Text(stringResource(R.string.restore_sheets_scope, impact.expenses, impact.incomes))
                        Text("https://docs.google.com/spreadsheets/d/${impact.workbookId}/edit", style = MaterialTheme.typography.bodySmall)
                    }
                    if (running != null && uiState.restoreSheetsImpact == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(running.stage, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                if (uiState.restoreSheetsImpact != null) {
                    TextButton(onClick = viewModel::confirmRestoreSheets) { Text(stringResource(R.string.restore_sheets_confirm)) }
                } else if (running == null) {
                    TextButton(
                        onClick = {
                            viewModel.restorePendingBackup(context, restorePassword)
                            restorePassword = ""
                        },
                        enabled = restorePassword.length >= 8 && !uiState.isLoading
                    ) { Text(stringResource(R.string.replace_and_restore_action)) }
                }
            },
            dismissButton = {
                if (running == null || running.canCancel) {
                    TextButton(
                        onClick = if (running == null) viewModel::dismissRestore else viewModel::cancelRestore
                    ) {
                        Text(if (running == null) stringResource(R.string.cancel_action) else stringResource(R.string.cancel_restore_action))
                    }
                }
            },
        )
    }

    if (showDeleteCloudConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteCloudConfirmation = false },
            title = { Text(stringResource(R.string.delete_drive_backups_title)) },
            text = { Text(stringResource(R.string.delete_drive_backups_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCloudConfirmation = false
                    viewModel.deleteCloudBackups()
                }) { Text(stringResource(R.string.delete_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCloudConfirmation = false }) { Text(stringResource(R.string.cancel_action)) }
            }
        )
    }
}

private fun openTrustedUrl(
    context: Context,
    rawUrl: String,
    allowedHosts: Set<String>
): String? {
    val uri = Uri.parse(rawUrl)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (uri.scheme != "https" || host !in allowedHosts) {
        return context.getString(R.string.invalid_link_message)
    }
    val intent = Intent(Intent.ACTION_VIEW, uri)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        context.getString(R.string.could_not_open_link_message)
    }
}

@Composable
private fun CloudBackupRow(
    backup: CloudBackupInfo,
    locale: Locale,
    enabled: Boolean,
    onRestore: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", locale)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateFormat.format(Date(backup.createdAt)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${backup.preview.invoiceCount} facturas · ${backup.preview.incomeCount} ingresos · ${backup.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onRestore, enabled = enabled) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.restore))
        }
    }
}
