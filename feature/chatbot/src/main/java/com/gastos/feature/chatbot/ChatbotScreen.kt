package com.gastos.feature.chatbot

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.gastos.common.design.EssentialHeader
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

sealed class ChatMessage {
    abstract val timestamp: Long
    data class User(val text: String, override val timestamp: Long = java.lang.System.currentTimeMillis()) : ChatMessage()
    data class AI(val text: String, override val timestamp: Long = java.lang.System.currentTimeMillis()) : ChatMessage()
    data class System(val text: String, override val timestamp: Long = java.lang.System.currentTimeMillis()) : ChatMessage()
    data class Document(val id: Long, val text: String, override val timestamp: Long, val uuid: String? = null, val kind: String? = null) : ChatMessage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatbotScreen(
    importedUri: Uri? = null,
    onImportConsumed: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onOpenDocument: (com.gastos.domain.model.DocumentIdentity) -> Unit = {},
    onOpenReceipt: (String, String?) -> Unit = { _, _ -> },
    onManualEntry: (Boolean) -> Unit = {},
    onNavigateToBank: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    captureModel: DocumentCaptureViewModel = hiltViewModel(),
    viewModel: ChatbotViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captureState by captureModel.state.collectAsStateWithLifecycle()
    val hasCaptureContent: Boolean = captureState.busy || captureState.selected != null ||
        captureState.duplicates.isNotEmpty() ||
        captureState.message != null || captureState.saved != null
    val lastMessage: ChatMessage? = uiState.messages.lastOrNull()
    // Petición de reseña de Play: solo cuando ya hay conversación real, una vez por instalación.
    ReviewPromptEffect(aiMessageCount = uiState.messages.count { it is ChatMessage.AI && it.text.isNotBlank() })
    val showTyping: Boolean = uiState.isProcessing && (lastMessage !is ChatMessage.AI || lastMessage.text.isEmpty())
    val showRetry: Boolean = uiState.canRetryIncomplete && !uiState.isProcessing
    val listState = rememberLazyListState()
    var textInput by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val voiceAvailable = remember {
        android.speech.SpeechRecognizer.isRecognitionAvailable(context)
    }

    LaunchedEffect(importedUri, captureState.busy) { if (!captureState.busy) importedUri?.let { captureModel.processImage(it); onImportConsumed() } }
    // Image picker launcher (galería)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { captureModel.processImage(it) }
    }

    // Estado para la URI de la foto tomada con cámara
    var capturedImageUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val capturedImageUri = capturedImageUriText?.let(Uri::parse)
    var showScanMenu by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    // Reporte de contenido generado por IA (política de Google Play)
    var reportTarget by remember { mutableStateOf<String?>(null) }
    var pendingMessageToReport by remember { mutableStateOf<String?>(null) }

    // Launcher para tomar una foto con la cámara
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && capturedImageUri != null) {
            captureModel.processImage(capturedImageUri)
        }
    }

    // Launcher del escáner de documentos de ML Kit: devuelve la página ya recortada y
    // enderezada por Google, así el OCR recibe una imagen limpia en vez de una foto torcida.
    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pagina = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.firstOrNull()
            if (pagina != null) {
                captureModel.processImage(pagina.imageUri)
            }
        }
    }

    // Launcher para solicitar permiso de cámara
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera(context) { uri ->
                capturedImageUriText = uri.toString()
                cameraLauncher.launch(uri)
            }
        }
    }

    // Permission launcher for voice
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startVoiceInput()
        }
    }

    // Cámara del sistema: plan B si el escáner de ML Kit no está disponible (sin Play Services)
    val abrirCamaraDelSistema: () -> Unit = {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
                launchCamera(context) { uri ->
                    capturedImageUriText = uri.toString()
                    cameraLauncher.launch(uri)
                }
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Escáner de documentos (ML Kit): bordes detectados, recorte y enderezado antes del OCR
    val abrirEscaner: () -> Unit = {
        val actividad = context.activityOrNull()
        if (actividad == null) {
            // Sin Activity no se puede resolver el IntentSender del escáner
            abrirCamaraDelSistema()
        } else {
            val opciones = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opciones)
                .getStartScanIntent(actividad)
                .addOnSuccessListener { sender ->
                    documentScannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { abrirCamaraDelSistema() }
        }
    }

    LaunchedEffect(uiState.messages.size, showTyping, showRetry, hasCaptureContent, captureState.busy,
        captureState.selected?.uuid, captureState.message, captureState.issues, captureState.saved) {
        val itemCount: Int = uiState.messages.size + (if (showRetry) 1 else 0) +
            (if (showTyping) 1 else 0) + (if (hasCaptureContent) 1 else 0)
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            EssentialHeader(stringResource(R.string.chatbot_app_title), onNavigateBack) {
                IconButton(onClick = viewModel::refreshExchangeRates) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh_exchange_rates)) }
                IconButton(onClick = { showClearDialog = true }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.chatbot_cd_clear_chat)) }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.testTag("chat_composer").navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Quick actions wrap when accessibility text needs more room.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                if (uiState.isListening) {
                                    viewModel.stopVoiceInput()
                                } else {
                                    when (PackageManager.PERMISSION_GRANTED) {
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
                                            viewModel.startVoiceInput()
                                        }
                                        else -> {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = voiceAvailable && !uiState.isProcessing,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            if (uiState.isListening) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (uiState.isListening) stringResource(R.string.chatbot_stop_voice) else stringResource(R.string.chatbot_voice), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = { showScanMenu = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = !uiState.isProcessing && !captureState.busy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.chatbot_scan), style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(
                            expanded = showScanMenu,
                            onDismissRequest = { showScanMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chatbot_menu_scan_document)) },
                                onClick = {
                                    showScanMenu = false
                                    abrirEscaner()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                onClick = {
                                    showScanMenu = false
                                    imagePickerLauncher.launch(arrayOf("image/*", "application/pdf"))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(com.gastos.common.R.string.bank_statements_title)) }, onClick = { showScanMenu = false; onNavigateToBank() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.manual_expense)) }, onClick = { showScanMenu = false; onManualEntry(false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.manual_income)) }, onClick = { showScanMenu = false; onManualEntry(true) })
                        }
                    }

                    // Text input row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.chatbot_input_placeholder)) },
                            minLines = 1,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSend = {
                                    if (textInput.isNotBlank() && !uiState.isProcessing) {
                                        viewModel.sendMessage(textInput)
                                        textInput = ""
                                    }
                                }
                            ),
                            shape = com.gastos.common.design.EssentialLayout.fieldShape,
                            colors = com.gastos.common.design.essentialFieldColors(),
                            enabled = !uiState.isProcessing
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (textInput.isNotBlank() && !uiState.isProcessing) {
                                    viewModel.sendMessage(textInput)
                                    textInput = ""
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            enabled = textInput.isNotBlank() && !uiState.isProcessing
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.chatbot_cd_send),
                                tint = if (textInput.isNotBlank() && !uiState.isProcessing)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LaunchedEffect(Unit) { viewModel.refreshApiKeyState() }
            if (uiState.needsApiKey) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.chatbot_api_key_card_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chatbot_api_key_card_body),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = onNavigateToSettings) {
                            Text(stringResource(R.string.chatbot_api_key_card_action))
                        }
                    }
                }
            }
            if (uiState.messages.isEmpty() && !hasCaptureContent) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.chatbot_welcome_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.chatbot_welcome_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Quick suggestions
                    listOf(
                        stringResource(R.string.chatbot_suggestion_expenses_month),
                        stringResource(R.string.chatbot_suggestion_salary),
                        stringResource(R.string.chatbot_suggestion_supermarket),
                        stringResource(R.string.chatbot_suggestion_balance)
                    ).forEach { suggestion ->
                        SuggestionChip(
                            onClick = { if (!uiState.isProcessing) viewModel.sendMessage(suggestion) },
                            enabled = !uiState.isProcessing,
                            label = { Text(suggestion) },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("chat_messages"),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.messages) { message ->
                        when (message) {
                            is ChatMessage.User -> UserMessageBubble(message.text)
                            is ChatMessage.AI -> AIMessageBubble(
                                text = message.text,
                                onReport = { reportTarget = message.text }
                            )
                            is ChatMessage.System -> SystemMessageBubble(message.text)
                            is ChatMessage.Document -> DocumentMessageBubble(message.text, message.uuid?.let { uuid -> { onOpenReceipt(uuid, message.kind) } })
                        }
                    }

                    if (showRetry) {
                        item { OutlinedButton(onClick = viewModel::retryIncompleteResponse) { Text(stringResource(R.string.chatbot_retry_incomplete)) } }
                    }
                    // Indicador de "escribiendo..." solo si aún no hay un mensaje
                    // AI en streaming rellenándose (placeholder vacío o ausente).
                    if (showTyping) {
                        item {
                            AIMessageBubble(stringResource(R.string.chatbot_typing_indicator))
                        }
                    }
                    uiState.correction?.let { pending -> item {
                        Column {
                            Text(stringResource(R.string.choose_movement))
                            pending.choices.forEach { choice -> TextButton(onClick = { viewModel.chooseCorrection(choice.identity.uuid) }, enabled = !uiState.isProcessing) {
                                Text("${choice.identity.issuer} · ${choice.identity.date} · ${choice.identity.amount} ${choice.identity.currency}")
                            } }
                            TextButton(onClick = viewModel::dismissCorrection) { Text(stringResource(R.string.capture_close)) }
                        }
                    } }
                    if (uiState.suggestedRule != null) item { TextButton(onClick = viewModel::saveSuggestedRule, enabled = !uiState.isProcessing) { Text(stringResource(R.string.use_category_for_future)) } }
                    if (uiState.undoMutationId != null) item { TextButton(onClick = viewModel::undoCorrection, enabled = !uiState.isProcessing) { Text(stringResource(R.string.undo_correction)) } }
                    if (hasCaptureContent) {
                        item(key = "capture_event") {
                            Column(Modifier.fillMaxWidth().testTag("chat_capture_event")) {
                                DocumentCapturePanel(captureState, captureModel, onOpenDocument)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.chatbot_clear_dialog_title)) },
            text = { Text(stringResource(R.string.chatbot_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; viewModel.clearChat() }) { Text(stringResource(R.string.chatbot_clear_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.chatbot_clear_dialog_cancel)) }
            }
        )
    }

    // Reporte de contenido generado por IA (requisito de la política de Google Play).
    reportTarget?.let { textoReportado ->
        ReportAiContentDialog(
            aiText = textoReportado,
            onDismiss = { reportTarget = null },
            onSend = { motivo, etiqueta ->
                AiContentReporter.sendReport(
                    context = context,
                    aiText = textoReportado,
                    reason = motivo,
                    reasonLabel = etiqueta,
                    appVersion = AiContentReporter.appVersion(context)
                )
                reportTarget = null
            }
        )
    }
}

@Composable
private fun DocumentMessageBubble(text: String, onOpen: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("chat_document_receipt"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text.substringBefore('\n'), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(text.substringAfter('\n', "").lineSequence().take(if (onOpen == null) Int.MAX_VALUE else 3).joinToString("\n"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                onOpen?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.open_transaction)) } }
            }
        }
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(12.dp)
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AIMessageBubble(text: String, onReport: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            // Canal de reporte exigido por la política de contenido generado por IA de Google Play.
            if (onReport != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onReport,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp).testTag("report_ai_content")
                    ) {
                        Icon(
                            Icons.Default.Flag,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.chatbot_report_ai),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Diálogo de reporte de contenido generado por IA.
 *
 * Dos pasos: elegir el motivo y pasar al gestor de correo con el reporte redactado.
 * El texto de la respuesta viaja en el cuerpo del correo; la app no envía nada por sí sola.
 */
@Composable
private fun ReportAiContentDialog(
    aiText: String,
    onDismiss: () -> Unit,
    onSend: (AiContentReporter.Reason, String) -> Unit
) {
    val motivos = listOf(
        AiContentReporter.Reason.OFFENSIVE to stringResource(R.string.chatbot_report_reason_offensive),
        AiContentReporter.Reason.WRONG to stringResource(R.string.chatbot_report_reason_wrong),
        AiContentReporter.Reason.UNSAFE to stringResource(R.string.chatbot_report_reason_unsafe),
        AiContentReporter.Reason.OTHER to stringResource(R.string.chatbot_report_reason_other)
    )
    var seleccionado by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatbot_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.chatbot_report_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                motivos.forEachIndexed { index, (_, etiqueta) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { seleccionado = index }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = seleccionado == index,
                            onClick = { seleccionado = index }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(etiqueta, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    seleccionado?.let { onSend(motivos[it].first, motivos[it].second) }
                },
                enabled = seleccionado != null
            ) { Text(stringResource(R.string.chatbot_report_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_close)) }
        }
    )
}


/** Desenvuelve el Context hasta la Activity que lo contiene (el escáner la exige). */
private fun android.content.Context.activityOrNull(): android.app.Activity? {
    var actual: android.content.Context? = this
    while (actual is android.content.ContextWrapper) {
        if (actual is android.app.Activity) return actual
        actual = actual.baseContext
    }
    return null
}

/**
 * Crea un archivo temporal para la foto y devuelve su URI vía FileProvider.
 * El llamador debe usar la URI en `TakePicture`.
 */
private fun launchCamera(
    context: android.content.Context,
    onUriReady: (Uri) -> Unit
) {
    val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
    val photoFile = File.createTempFile("invoice_", ".jpg", cameraDir)
    val photoUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
    onUriReady(photoUri)
}
