package com.gastos.feature.backup

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPremium: () -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var showPasswordSetup by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var passwordValidationError by remember { mutableStateOf<String?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var showDeleteCloudConfirmation by remember { mutableStateOf(false) }
    var externalLinkError by remember { mutableStateOf<String?>(null) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri ->
        uri?.let { viewModel.exportEncryptedBackup(context, it) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !uiState.isRestoring) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
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
            // Backup portable cifrado
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_encrypted_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_encrypted_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
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
                    Text(
                        text = stringResource(R.string.keep_password_safe_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

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
                        TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
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

            // Backup automático Premium
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.drive_backup_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.drive_backup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
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
                                    Text(stringResource(R.string.create_now_action))
                                }
                                OutlinedButton(
                                    onClick = viewModel::loadCloudBackups,
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isCloudLoading
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.update_action))
                                }
                            }
                            uiState.cloudBackupStatus.lastSuccessAt?.let { timestamp ->
                                Text(
                                    stringResource(R.string.last_backup_prefix, SimpleDateFormat("dd/MM/yyyy HH:mm", locale).format(Date(timestamp))),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            uiState.cloudBackupStatus.lastError?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
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

            // Exportación a Google Sheets
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.google_sheets_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.google_sheets_description_localized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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
                        // Si ya hay sheet vinculado: botón sincronizar + re-exportar
                        if (uiState.hasSheetLink) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.exportToSheets() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isExportingSheets
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.sync_action))
                                }
                                OutlinedButton(
                                    onClick = { viewModel.syncAllToSheets() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isExportingSheets
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.force_action))
                                }
                            }
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

                    // URL resultante
                    uiState.sheetsUrl?.let { url ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.spreadsheet_synced_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        externalLinkError = openTrustedUrl(
                                            context = context,
                                            rawUrl = url,
                                            allowedHosts = setOf("docs.google.com")
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.open_in_google_sheets_action))
                                }
                            }
                        }
                    }
                }
            }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", locale).format(Date())
                                exportCsvLauncher.launch("finai_export_$timestamp.csv")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isExporting
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Description, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.csv))
                        }

                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", locale).format(Date())
                                exportPdfLauncher.launch("finai_informe_$timestamp.pdf")
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isExporting
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.pdf))
                        }

                        Button(
                            onClick = { viewModel.shareBackup(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isExporting
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.share_action))
                        }
                    }
                }
            }

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

            // Error
            uiState.error?.let { error ->
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
                    if (running != null) {
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
                if (running == null) {
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
