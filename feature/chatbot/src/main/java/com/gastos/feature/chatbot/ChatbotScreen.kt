package com.gastos.feature.chatbot

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
    captureModel: DocumentCaptureViewModel = hiltViewModel(),
    viewModel: ChatbotViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captureState by captureModel.state.collectAsStateWithLifecycle()
    val hasCaptureContent: Boolean = captureState.busy || captureState.selected != null ||
        captureState.duplicates.isNotEmpty() ||
        captureState.message != null || captureState.saved != null
    val lastMessage: ChatMessage? = uiState.messages.lastOrNull()
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

    // Launcher para tomar una foto con la cámara
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && capturedImageUri != null) {
            captureModel.processImage(capturedImageUri!!)
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
                                text = { Text(stringResource(R.string.chatbot_menu_take_photo)) },
                                onClick = {
                                    showScanMenu = false
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
                            is ChatMessage.AI -> AIMessageBubble(message.text)
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
private fun AIMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
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
