package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
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
    var restorePassword by remember { mutableStateOf("") }
    var showDeleteCloudConfirmation by remember { mutableStateOf(false) }

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
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !uiState.isRestoring) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
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
            // Backup portable cifrado
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup cifrado",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exporta datos, fotos, historial y ajustes en un archivo .finai protegido con tu contraseña.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (!uiState.isBackupKeyConfigured) {
                        Button(onClick = { showPasswordSetup = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Password, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Configurar contraseña de recuperación")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cifrado extremo a extremo configurado")
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
                            Text("Exportar")
                        }
                        OutlinedButton(
                            onClick = { importBackupLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading && !uiState.isRestoring
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restaurar")
                        }
                    }
                    Text(
                        text = "Guarda la contraseña fuera de FinAI. Si la pierdes, el backup no se puede descifrar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            // Cuenta Google compartida por Drive y Sheets
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Cuenta Google", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.isSignedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Conectado")
                                Text(uiState.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cerrar sesión de Google")
                        }
                    } else {
                        Text(
                            "Conecta Google para el backup Premium, Sheets y las fotos de facturas.",
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
                            Text("Iniciar sesión con Google")
                        }
                    }
                }
            }

            // Backup automático Premium
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Backup automático en Drive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Copia cifrada diaria y cinco versiones recuperables tras reinstalar o cambiar de dispositivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    when {
                        !uiState.isPremium -> Button(onClick = onNavigateToPremium, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Desbloquear backup Premium")
                        }
                        !uiState.isSignedIn -> OutlinedButton(
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Conectar Google") }
                        !uiState.isBackupKeyConfigured -> Button(
                            onClick = { showPasswordSetup = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Configurar contraseña") }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Copia diaria")
                                    Text("Solo con red y batería suficiente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text("Crear ahora")
                                }
                                OutlinedButton(
                                    onClick = viewModel::loadCloudBackups,
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isCloudLoading
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Actualizar")
                                }
                            }
                            uiState.cloudBackupStatus.lastSuccessAt?.let { timestamp ->
                                Text(
                                    "Última copia: ${SimpleDateFormat("dd/MM/yyyy HH:mm", locale).format(Date(timestamp))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            uiState.cloudBackupStatus.lastError?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (uiState.cloudBackups.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                Text("Copias disponibles", style = MaterialTheme.typography.titleSmall)
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
                                    Text("Eliminar copias de Drive", color = MaterialTheme.colorScheme.error)
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
                        text = "Google Sheets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exporta y sincroniza tus datos a un Google Sheet organizado por hojas: Gastos, Ingresos, Productos y Resumen. Función Premium.",
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
                            Text("Desbloquear Premium para usar Sheets")
                        }
                    } else if (!uiState.isSignedIn) {
                        OutlinedButton(
                            onClick = { signInLauncher.launch(viewModel.getSignInIntent()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Conectar cuenta Google")
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
                                    Text("Sincronizar")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.syncAllToSheets() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !uiState.isExportingSheets
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Forzar")
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
                                Text(if (uiState.isExportingSheets) "Exportando..." else "Exportar a Google Sheets")
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
                                    "✓ Spreadsheet creado",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Abrir en Google Sheets")
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
                            text = if (result.success) "Backup completado" else "Error",
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
                        text = "Exportar y Compartir",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exporta tus datos y compártelos por email, Drive, WhatsApp, etc.",
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
                            Text("CSV")
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
                            Text("PDF")
                        }

                        Button(
                            onClick = { viewModel.shareBackup(context) },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isExporting
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compartir")
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
                            text = if (result.success) "Exportación completada" else "Error",
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
                            text = "Error",
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
            title = { Text("Contraseña de recuperación") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mínimo 8 caracteres. FinAI no podrá recuperarla si la olvidas.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        visualTransformation = rememberTimedPasswordVisualTransformation(password),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = passwordConfirmation,
                        onValueChange = { passwordConfirmation = it },
                        label = { Text("Confirmar contraseña") },
                        visualTransformation = rememberTimedPasswordVisualTransformation(passwordConfirmation),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.configureBackupPassword(password, passwordConfirmation)
                    password = ""
                    passwordConfirmation = ""
                    showPasswordSetup = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showPasswordSetup = false }) { Text("Cancelar") } }
        )
    }

    uiState.pendingRestore?.let { pending ->
        val running = uiState.restoreState as? BackupRestoreState.Running
        AlertDialog(
            onDismissRequest = { if (running == null) viewModel.dismissRestore() },
            title = { Text("Restaurar backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${pending.preview.invoiceCount} facturas · ${pending.preview.productCount} productos · " +
                            "${pending.preview.incomeCount} ingresos · ${pending.preview.imageCount} imágenes"
                    )
                    Text(
                        "La restauración reemplazará todos los datos actuales.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("Contraseña de recuperación") },
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
                    ) { Text("Reemplazar y restaurar") }
                }
            },
            dismissButton = {
                if (running == null || running.canCancel) {
                    TextButton(
                        onClick = if (running == null) viewModel::dismissRestore else viewModel::cancelRestore
                    ) {
                        Text(if (running == null) "Cancelar" else "Cancelar restauración")
                    }
                }
            },
        )
    }

    if (showDeleteCloudConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteCloudConfirmation = false },
            title = { Text("Eliminar copias de Drive") },
            text = { Text("Se eliminarán permanentemente todas las copias cifradas de FinAI guardadas en Google Drive.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCloudConfirmation = false
                    viewModel.deleteCloudBackups()
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCloudConfirmation = false }) { Text("Cancelar") }
            }
        )
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
            Text("Restaurar")
        }
    }
}
