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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(uiState.settings.geminiApiKey) }

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
                // Motor de IA
                Text(
                    text = "Motor de IA",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.settings.aiEngine == "gemma_local",
                        onClick = { viewModel.updateAiEngine("gemma_local") },
                        label = { Text("Gemma 4") },
                        leadingIcon = {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = uiState.settings.aiEngine == "gemini_api",
                        onClick = { viewModel.updateAiEngine("gemini_api") },
                        label = { Text("Gemini API") },
                        leadingIcon = {
                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Descripción del motor seleccionado
                Text(
                    text = when (uiState.settings.aiEngine) {
                        "gemma_local" -> "Procesamiento local sin internet. Privacidad total. Usa Gemma 4 E4B vía AICore."
                        "gemini_api" -> "Procesamiento en la nube. Más preciso. Necesita API key gratuita."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Estado de Gemma 4
                AnimatedVisibility(visible = uiState.settings.aiEngine == "gemma_local") {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (uiState.gemmaModel.isAvailable) 
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (uiState.gemmaModel.isAvailable) Icons.Default.CheckCircle
                                            else if (uiState.gemmaModel.isDownloading) Icons.Default.Download
                                            else Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = if (uiState.gemmaModel.isAvailable) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = when {
                                                    uiState.gemmaModel.isAvailable -> "Gemma 4 listo"
                                                    uiState.gemmaModel.isDownloading -> "Descargando..."
                                                    else -> "Gemma 4 no disponible"
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (uiState.gemmaModel.modelSize.isNotBlank()) {
                                                Text(
                                                    text = uiState.gemmaModel.modelSize,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                // Barra de progreso de descarga
                                if (uiState.gemmaModel.isDownloading) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { uiState.gemmaModel.downloadProgress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${(uiState.gemmaModel.downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Mensaje de error
                                uiState.gemmaModel.error?.let { error ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                // Botones de acción
                                Spacer(modifier = Modifier.height(12.dp))
                                if (!uiState.gemmaModel.isAvailable && !uiState.gemmaModel.isDownloading) {
                                    Button(
                                        onClick = { viewModel.checkGemmaStatus() },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = true
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Verificar disponibilidad")
                                    }
                                }
                            }
                        }
                    }
                }

                // Configuración de Gemini API
                AnimatedVisibility(visible = uiState.settings.aiEngine == "gemini_api") {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { 
                                apiKeyInput = uiState.settings.geminiApiKey
                                showApiKeyDialog = true 
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (uiState.settings.geminiApiKey.isEmpty()) "Configurar API Key"
                                else "API Key configurada"
                            )
                        }
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
                    options = listOf("EUR", "USD", "MXN", "ARS", "COP", "CLP", "PEN"),
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

            // Sección Licencia Pro
            SettingsSection(
                title = "Licencia",
                icon = if (uiState.settings.isPro) Icons.Filled.Star else Icons.Outlined.StarBorder,
                headerContent = {
                    if (uiState.settings.isPro) {
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
                if (uiState.settings.isPro) {
                    Text(
                        text = "Funciones premium activadas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.deactivateLicense() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Desactivar licencia")
                    }
                } else {
                    Text(
                        text = "Introduce tu código de licencia",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.licenseInput,
                        onValueChange = { viewModel.updateLicenseInput(it.uppercase()) },
                        label = { Text("Código de licencia") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.licenseError != null,
                        supportingText = {
                            uiState.licenseError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.activateLicense() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.licenseInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Activar Pro")
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
                    onClick = { /* Navigate to backup */ },
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
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API Key") },
            text = {
                Column {
                    Text(
                        text = "Obtén tu API key gratuita en https://aistudio.google.com/apikey",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateGeminiApiKey(apiKeyInput)
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
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
