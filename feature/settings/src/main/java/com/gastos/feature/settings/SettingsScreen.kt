package com.gastos.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPremium: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(uiState.settings.geminiApiKey) }
    var showDeleteApiKeyConfirmation by remember { mutableStateOf(false) }
    var apiKeyDialogError by remember { mutableStateOf<String?>(null) }

    // El campo de instrucciones se sincroniza con el estado persistido en cada
    // carga (evita que quede vacío si las settings llegan asíncronamente).
    var instructionsInput by remember(uiState.settings.systemInstructions) {
        mutableStateOf(uiState.settings.systemInstructions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
        ) {
            // Sección IA
            SettingsSection(
                title = stringResource(R.string.settings_ai_section),
                icon = Icons.Outlined.SmartToy
            ) {
                Text(
                    text = stringResource(R.string.settings_ai_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Estado de la API key
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.settings.geminiApiKey.isNotEmpty())
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when {
                                uiState.isApiKeyValidating -> Icons.Default.CloudSync
                                uiState.settings.geminiApiKey.isNotEmpty() -> Icons.Default.CheckCircle
                                else -> Icons.Default.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (uiState.settings.geminiApiKey.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                uiState.isApiKeyValidating -> stringResource(R.string.settings_api_key_validating)
                                uiState.settings.geminiApiKey.isEmpty() -> stringResource(R.string.settings_api_key_not_configured)
                                else -> stringResource(R.string.settings_api_key_configured)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        apiKeyInput = uiState.settings.geminiApiKey
                        viewModel.resetApiKeyValidation()
                        showApiKeyDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (uiState.settings.geminiApiKey.isEmpty()) stringResource(R.string.settings_configure_api_key)
                        else stringResource(R.string.settings_change_api_key)
                    )
                }

                if (uiState.settings.geminiApiKey.isNotEmpty()) {
                    TextButton(
                        onClick = { showDeleteApiKeyConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_delete_api_key))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Instrucciones del asistente
                Text(
                    text = stringResource(R.string.settings_assistant_instructions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_assistant_instructions_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructionsInput,
                    onValueChange = { instructionsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                        placeholder = { Text(stringResource(R.string.settings_assistant_instructions_placeholder)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        instructionsInput = uiState.settings.systemInstructions
                    }) {
                        Text(stringResource(R.string.discard))
                    }
                    Button(
                        onClick = { viewModel.updateSystemInstructions(instructionsInput) },
                        enabled = instructionsInput != uiState.settings.systemInstructions
                    ) {
                        Text(stringResource(R.string.save_instructions))
                    }
                }
            }

            // Sección Apariencia
            SettingsSection(
                title = stringResource(R.string.settings_appearance_section),
                icon = Icons.Outlined.Palette
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeOption(
                        icon = Icons.Default.PhoneAndroid,
                        label = stringResource(R.string.system),
                        selected = uiState.settings.darkMode == "system",
                        onClick = { viewModel.updateDarkMode("system") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOption(
                        icon = Icons.Default.LightMode,
                        label = stringResource(R.string.light),
                        selected = uiState.settings.darkMode == "light",
                        onClick = { viewModel.updateDarkMode("light") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOption(
                        icon = Icons.Default.DarkMode,
                        label = stringResource(R.string.dark),
                        selected = uiState.settings.darkMode == "dark",
                        onClick = { viewModel.updateDarkMode("dark") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Sección Regional
            SettingsSection(
                title = stringResource(R.string.settings_regional_section),
                icon = Icons.Outlined.Public
            ) {
                Text(
                    text = stringResource(R.string.settings_exchange_rates_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SettingsDropdown(
                    label = stringResource(R.string.currency),
                    value = uiState.settings.defaultCurrency,
                    options = com.gastos.domain.model.SUPPORTED_CURRENCIES,
                    onValueChange = { viewModel.updateDefaultCurrency(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsDropdown(
                    label = stringResource(R.string.default_tax_country),
                    value = uiState.settings.defaultCountry,
                    options = com.gastos.domain.model.SUPPORTED_FISCAL_COUNTRIES,
                    optionLabel = { code: String -> com.gastos.domain.model.fiscalCountryLabel(code) },
                    onValueChange = { viewModel.updateDefaultCountry(it) }
                )
                Text(
                    text = stringResource(R.string.settings_country_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tarjeta de tipos de cambio
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CurrencyExchange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_exchange_rates_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    uiState.isRefreshingRates -> stringResource(R.string.settings_exchange_rates_loading)
                                    uiState.ratesAsOf != null -> {
                                        val d = java.text.SimpleDateFormat(
                                            "dd/MM/yyyy HH:mm", locale
                                        ).format(java.util.Date(uiState.ratesAsOf!!))
                                        stringResource(R.string.settings_exchange_rates_status, uiState.ratesCount, d)
                                    }
                                    else -> stringResource(R.string.settings_exchange_rates_empty)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.refreshRates() },
                            enabled = !uiState.isRefreshingRates
                        ) {
                            if (uiState.isRefreshingRates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.update))
                        }
                    }
                }
            }

            // Sección Premium
            SettingsSection(
                title = stringResource(R.string.settings_premium_title),
                icon = if (uiState.isPremium) Icons.Filled.Star else Icons.Outlined.StarBorder,
                headerContent = {
                    if (uiState.isPremium) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.settings_premium_chip)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            ) {
                if (uiState.isPremium) {
                    Text(
                        text = stringResource(R.string.settings_premium_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_premium_pitch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToPremium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.see_premium))
                    }
                }
            }

            SettingsSection(
                title = stringResource(R.string.settings_floating_buttons_title),
                icon = Icons.Outlined.OpenWith
            ) {
                Text(
                    text = stringResource(R.string.floating_buttons_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::resetFloatingButtonPositions,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.floatingButtonPositions.isNotEmpty()
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_positions))
                }
            }

            // Sección Datos
            SettingsSection(
                title = stringResource(R.string.settings_data_title),
                icon = Icons.Outlined.Storage
            ) {
                Text(
                    text = stringResource(R.string.data_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onNavigateToBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.go_backup))
                }
            }

            // Sección Debug (solo visible en builds debug)
            if (uiState.isDebug) {
                SettingsSection(
                    title = stringResource(R.string.settings_debug_title),
                    icon = Icons.Outlined.BugReport
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.premium_debug),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.premium_debug_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.isPremium,
                            onCheckedChange = { viewModel.debugSetPremium(it) }
                        )
                    }
                }
            }

            // Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                // Versión real leída del paquete instalado (no hardcodeada).
                val appVersion = remember {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: ""
                }
                Text(
                    text = stringResource(R.string.version, appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Diálogo API Key
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = {
                showApiKeyDialog = false
                apiKeyDialogError = null
                viewModel.resetApiKeyValidation()
            },
            title = { Text(stringResource(R.string.settings_api_key_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_api_key_help_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_api_key_help_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            apiKeyDialogError = openTrustedUrl(
                                context = context,
                                rawUrl = "https://aistudio.google.com/apikey",
                                allowedHosts = setOf("aistudio.google.com")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_open_google_ai_studio))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    apiKeyDialogError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.settings_gemini_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            viewModel.resetApiKeyValidation()
                        },
                        label = { Text(stringResource(R.string.settings_api_key_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Feedback de validación
                    when (val v = uiState.apiKeyValidation) {
                        ApiKeyValidation.None -> {}
                        ApiKeyValidation.Valid -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.settings_api_key_valid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is ApiKeyValidation.Invalid -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    v.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isApiKeyValidating,
                    onClick = { viewModel.updateGeminiApiKey(apiKeyInput) }
                ) {
                    Text(if (uiState.isApiKeyValidating) stringResource(R.string.settings_api_key_validating_short) else stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showApiKeyDialog = false
                    apiKeyDialogError = null
                    viewModel.resetApiKeyValidation()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )

        // Cerrar automáticamente cuando la validación es exitosa
        LaunchedEffect(uiState.apiKeyValidation) {
            if (uiState.apiKeyValidation is ApiKeyValidation.Valid) {
                showApiKeyDialog = false
                apiKeyDialogError = null
                viewModel.resetApiKeyValidation()
            }
        }
    }

    if (showDeleteApiKeyConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteApiKeyConfirmation = false },
            title = { Text(stringResource(R.string.settings_delete_api_key_title)) },
            text = { Text(stringResource(R.string.settings_delete_api_key_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteApiKeyConfirmation = false
                    viewModel.clearGeminiApiKey()
                }) { Text(stringResource(R.string.settings_delete_api_key_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteApiKeyConfirmation = false }) { Text(stringResource(R.string.cancel)) }
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
    val host = uri.host?.lowercase(java.util.Locale.ROOT)
    if (uri.scheme != "https" || host !in allowedHosts) {
        return context.getString(R.string.settings_untrusted_link_error)
    }
    val intent = Intent(Intent.ACTION_VIEW, uri)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        context.getString(R.string.settings_open_link_error)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    headerContent: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            headerContent()
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ThemeOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    valueMap: Map<String, String> = emptyMap(),
    optionLabel: (String) -> String = { it },
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(value),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onValueChange(valueMap[option] ?: option)
                        expanded = false
                    }
                )
            }
        }
    }
}
