package com.gastos.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPremium: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(uiState.settings.geminiApiKey) }

    // El campo de instrucciones se sincroniza con el estado persistido en cada
    // carga (evita que quede vacío si las settings llegan asíncronamente).
    var instructionsInput by remember(uiState.settings.systemInstructions) {
        mutableStateOf(uiState.settings.systemInstructions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
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
                title = "Inteligencia Artificial",
                icon = Icons.Outlined.SmartToy
            ) {
                Text(
                    text = "FinAI usa Gemini 3.5 Flash a través de la API gratuita de Google AI Studio.",
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
                                uiState.isApiKeyValidating -> "Validando API key..."
                                uiState.settings.geminiApiKey.isEmpty() -> "API key no configurada"
                                else -> "API key configurada (Gemini 3.5 Flash)"
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
                        if (uiState.settings.geminiApiKey.isEmpty()) "Configurar API Key"
                        else "Cambiar API Key"
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Instrucciones del asistente
                Text(
                    text = "Instrucciones del asistente",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Personaliza cómo se comporta FinAI. Por ejemplo: el tono, en qué moneda responder, qué evitar, etc.",
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
                    placeholder = {
                        Text("Ej. Responde de forma breve. Usa siempre MXN como moneda.")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        instructionsInput = uiState.settings.systemInstructions
                    }) {
                        Text("Descartar")
                    }
                    Button(
                        onClick = { viewModel.updateSystemInstructions(instructionsInput) },
                        enabled = instructionsInput != uiState.settings.systemInstructions
                    ) {
                        Text("Guardar instrucciones")
                    }
                }
            }

            // Sección Apariencia
            SettingsSection(
                title = "Apariencia",
                icon = Icons.Outlined.Palette
            ) {
                Text(
                    text = "Tema",
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
                        label = "Sistema",
                        selected = uiState.settings.darkMode == "system",
                        onClick = { viewModel.updateDarkMode("system") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOption(
                        icon = Icons.Default.LightMode,
                        label = "Claro",
                        selected = uiState.settings.darkMode == "light",
                        onClick = { viewModel.updateDarkMode("light") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOption(
                        icon = Icons.Default.DarkMode,
                        label = "Oscuro",
                        selected = uiState.settings.darkMode == "dark",
                        onClick = { viewModel.updateDarkMode("dark") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Sección Regional
            SettingsSection(
                title = "Regional",
                icon = Icons.Outlined.Public
            ) {
                SettingsDropdown(
                    label = "Moneda",
                    value = uiState.settings.defaultCurrency,
                    options = com.gastos.domain.model.SUPPORTED_CURRENCIES,
                    onValueChange = { viewModel.updateDefaultCurrency(it) }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                val countries = mapOf(
                    "ES" to "España",
                    "MX" to "México",
                    "US" to "Estados Unidos",
                    "AR" to "Argentina",
                    "CO" to "Colombia",
                    "CL" to "Chile",
                    "PE" to "Perú"
                )
                val selectedCountry = countries[uiState.settings.defaultCountry] ?: "España"
                
                SettingsDropdown(
                    label = "País",
                    value = selectedCountry,
                    options = countries.values.toList(),
                    valueMap = countries.entries.associate { it.value to it.key },
                    onValueChange = { name ->
                        countries.entries.find { it.value == name }?.let {
                            viewModel.updateDefaultCountry(it.key)
                        }
                    }
                )
            }

            // Sección Premium
            SettingsSection(
                title = "Premium",
                icon = if (uiState.isPremium) Icons.Filled.Star else Icons.Outlined.StarBorder,
                headerContent = {
                    if (uiState.isPremium) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Pro") },
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
                        text = "Funciones premium activadas ✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Desbloquea exportación a Google Sheets, backup automático en Drive y chat IA avanzado.",
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
                        Text("Ver Premium")
                    }
                }
            }

            // Sección Datos
            SettingsSection(
                title = "Datos",
                icon = Icons.Outlined.Storage
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Backup automático",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Guardar en Google Drive semanalmente",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.settings.autoBackup,
                        onCheckedChange = { viewModel.updateAutoBackup(it) }
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Exportar y compartir tus datos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onNavigateToBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ir a Backup")
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
                    text = "FinAI",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = "Versión 1.0.0",
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
                viewModel.resetApiKeyValidation()
            },
            title = { Text("Gemini API Key") },
            text = {
                Column {
                    Text(
                        text = "Cómo obtener tu API key gratuita:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1. Abre Google AI Studio (botón de abajo)\n" +
                                "2. Inicia sesión con tu cuenta Google\n" +
                                "3. Pulsa \"Create API key\"\n" +
                                "4. Copia la clave y pégala aquí",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://aistudio.google.com/apikey")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Abrir Google AI Studio")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💡 Gemini 3.5 Flash tiene un plan gratuito suficiente para uso personal (aprox. 10 solicitudes/min y 1500/día). Consulta tus límites exactos en AI Studio.",
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
                        label = { Text("API Key") },
                        singleLine = true,
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
                                    "API key válida",
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
                    Text(if (uiState.isApiKeyValidating) "Validando..." else "Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showApiKeyDialog = false
                    viewModel.resetApiKeyValidation()
                }) {
                    Text("Cancelar")
                }
            }
        )

        // Cerrar automáticamente cuando la validación es exitosa
        LaunchedEffect(uiState.apiKeyValidation) {
            if (uiState.apiKeyValidation is ApiKeyValidation.Valid) {
                showApiKeyDialog = false
                viewModel.resetApiKeyValidation()
            }
        }
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
            .clickable(onClick = onClick)
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
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
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
                    text = { Text(option) },
                    onClick = {
                        onValueChange(valueMap[option] ?: option)
                        expanded = false
                    }
                )
            }
        }
    }
}
