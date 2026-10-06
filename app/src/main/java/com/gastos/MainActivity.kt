package com.gastos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.gastos.domain.model.*
import com.gastos.ui.movement.*
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gastos.feature.dashboard.DashboardScreen
import com.gastos.feature.invoices.InvoicesScreen
import com.gastos.feature.invoices.EditInvoiceScreen
import com.gastos.feature.incomes.IncomesScreen
import com.gastos.feature.incomes.EditIncomeScreen
import com.gastos.feature.settings.SettingsScreen
import com.gastos.feature.settings.SettingsViewModel
import com.gastos.feature.settings.PremiumScreen
import com.gastos.feature.backup.BackupScreen
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.repository.FloatingButtonIds
import com.gastos.repository.FloatingButtonPosition
import com.gastos.ui.theme.GastosEIngresosTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var importedDocument by mutableStateOf<android.net.Uri?>(null)
    private var importedBank by mutableStateOf<android.net.Uri?>(null)
    private fun receiveDocument(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_SEND && intent.type in setOf("text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel")) {
            @Suppress("DEPRECATION")
            val source: android.net.Uri? = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            if (source?.scheme == "content") importedBank = source
        }
        if (intent?.action == android.content.Intent.ACTION_SEND && (intent.type == "application/pdf" || intent.type?.startsWith("image/") == true)) {
            @Suppress("DEPRECATION")
            val source: android.net.Uri? = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            if (source?.scheme == "content") importedDocument = source
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveDocument(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        importedDocument?.let { outState.putString("pending_document", it.toString()) }
        importedBank?.let { outState.putString("pending_bank", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveDocument(intent)
        else importedDocument = savedInstanceState.getString("pending_document")?.let(android.net.Uri::parse)
        savedInstanceState?.getString("pending_bank")?.let { importedBank = android.net.Uri.parse(it) }
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner, settingsViewModel) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        settingsViewModel.onAppResumed()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            GastosEIngresosTheme(darkMode = uiState.settings.darkMode) {
                FinAIApp(
                    importedDocument = importedDocument,
                    importedBank = importedBank,
                    onBankConsumed = { importedBank = null; intent?.removeExtra(android.content.Intent.EXTRA_STREAM) },
                    onImportConsumed = { importedDocument = null; intent?.removeExtra(android.content.Intent.EXTRA_STREAM) },
                    defaultCurrency = uiState.settings.defaultCurrency,
                    floatingButtonPositions = uiState.floatingButtonPositions,
                    onFloatingButtonPositionChanged = settingsViewModel::updateFloatingButtonPosition,
                    settingsModel = settingsViewModel
                )
            }
        }
    }
}

sealed class Screen(@StringRes val titleRes: Int, val route: String, val selectedIcon: @Composable () -> Unit, val unselectedIcon: @Composable () -> Unit) {
    object Dashboard : Screen(
        titleRes = R.string.dashboard_title,
        route = "dashboard",
        selectedIcon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
        unselectedIcon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) }
    )
    object Invoices : Screen(
        titleRes = R.string.expenses_title,
        route = "invoices",
        selectedIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
        unselectedIcon = { Icon(Icons.Outlined.Description, contentDescription = null) }
    )
    object Incomes : Screen(
        titleRes = R.string.income_title,
        route = "incomes",
        selectedIcon = { Icon(Icons.Filled.Payments, contentDescription = null) },
        unselectedIcon = { Icon(Icons.Outlined.Payments, contentDescription = null) }
    )
}

// Rutas sin bottom bar
object Routes {
    const val SETTINGS = "settings"
    const val AUTOMATION = "automation"
    const val WALLET = "automation/wallet"
    const val PREMIUM = "premium"
    const val BACKUP = "backup"
    const val MOVEMENT = "movement/{source}/{uuid}"
    const val SETTINGS_PAGE = "settings/{section}"
    const val DATA_PAGE = "backup/{section}"
    const val PERSONALIZE = "personalize"
    const val EDIT_INVOICE = "edit_invoice/{invoiceId}"
    const val EDIT_INCOME = "edit_income/{incomeId}"
    const val CHATBOT = "chatbot"
    const val BANK = "bank"
}

@Composable
fun FinAIApp(
    importedDocument: android.net.Uri? = null,
    importedBank: android.net.Uri? = null,
    onBankConsumed: () -> Unit = {},
    onImportConsumed: () -> Unit = {},
    defaultCurrency: String = "EUR",
    floatingButtonPositions: Map<String, FloatingButtonPosition> = emptyMap(),
    onFloatingButtonPositionChanged: (String, FloatingButtonPosition) -> Unit = { _, _ -> },
    settingsModel: SettingsViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navigationModel: MovementNavigationViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    fun openMovement(reference: MovementReference) {
        navController.navigate("movement/${reference.source.name}/${android.net.Uri.encode(reference.uuid)}") { launchSingleTop = true }
    }
    fun openByUuid(uuid: String, kind: String?) {
        scope.launch {
            val reference = navigationModel.resolve(uuid, kind) ?: MovementReference(
                if (kind == "INCOME") MovementSource.INCOME else MovementSource.INVOICE, uuid)
            openMovement(reference)
        }
    }
    LaunchedEffect(importedDocument) { if (importedDocument != null) navController.navigate(Routes.CHATBOT) { launchSingleTop = true } }
    LaunchedEffect(importedBank) { if (importedBank != null) navController.navigate(Routes.BANK) { launchSingleTop = true } }
    val screens = listOf(Screen.Dashboard, Screen.Invoices, Screen.Incomes)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determinar si mostrar bottom bar
    val showBottomBar = currentDestination?.route in screens.map { it.route }

    val floatingButton = when (currentDestination?.route) {
        Screen.Dashboard.route -> FloatingButtonSpec(
            id = FloatingButtonIds.DASHBOARD_AI,
            icon = Icons.Filled.SmartToy,
            contentDescription = stringResource(R.string.open_ai_assistant),
            onClick = { navController.navigate(Routes.CHATBOT) }
        )
        Screen.Invoices.route -> FloatingButtonSpec(
            id = FloatingButtonIds.EXPENSES_ADD,
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.new_expense),
            onClick = { navController.navigate("edit_invoice/0") { launchSingleTop = true } }
        )
        Screen.Incomes.route -> FloatingButtonSpec(
            id = FloatingButtonIds.INCOMES_ADD,
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.new_income),
            onClick = { navController.navigate("edit_income/0") { launchSingleTop = true } }
        )
        else -> null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Each destination owns a Scaffold and applies system-bar insets.
        // Avoid adding the same insets once more at the navigation shell.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    screens.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = { if (selected) screen.selectedIcon() else screen.unselectedIcon() },
                            label = { Text(stringResource(screen.titleRes)) },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.padding(innerPadding)
            ) {
            // Pantallas principales (con bottom bar)
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    defaultCurrency = defaultCurrency,
                    onNavigateToChat = { navController.navigate(Routes.CHATBOT) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToBackup = {
                        navController.navigate(Routes.BACKUP) { launchSingleTop = true }
                    },
                    onOpenReference = ::openMovement
                )
            }
            composable(Screen.Invoices.route) {
                InvoicesScreen(
                    onNavigateToEdit = { id -> navController.navigate("edit_invoice/$id") { launchSingleTop = true } },
                    onOpenMovement = ::openMovement
                )
            }
            composable(Screen.Incomes.route) {
                IncomesScreen(
                    onNavigateToEdit = { id -> navController.navigate("edit_income/$id") { launchSingleTop = true } },
                    onOpenMovement = ::openMovement
                )
            }

            // Pantallas secundarias (sin bottom bar)
            composable(Routes.CHATBOT) {
                ChatbotScreen(
                    importedUri = importedDocument, onImportConsumed = onImportConsumed,
                    onOpenDocument = { record -> openByUuid(record.uuid, if (record.isIncome) "INCOME" else "EXPENSE") },
                    onOpenReceipt = ::openByUuid,
                    onManualEntry = { income -> navController.navigate(if (income) "edit_income/0" else "edit_invoice/0") },
                    onNavigateToBank = { navController.navigate(Routes.BANK) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.AUTOMATION) { com.gastos.automation.AutomationScreen(onBack = { navController.popBackStack() }, onOpenMovement = { openByUuid(it, "EXPENSE") }) }
            composable(Routes.BANK) {
                com.gastos.bank.BankImportScreen(onBack = { navController.popBackStack() },
                    onOpen = { source, uuid -> navController.navigate("movement/$source/${android.net.Uri.encode(uuid)}") },
                    importedUri = importedBank, onConsumed = onBankConsumed)
            }
            composable(Routes.WALLET) {
                com.gastos.automation.AutomationScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMovement = { openByUuid(it, "EXPENSE") },
                    initialSection = com.gastos.automation.AutomationSection.WALLET
                )
            }
            composable(Routes.MOVEMENT) {
                MovementDetailScreen(onBack = { navController.popBackStack() }, onEdit = { income, id ->
                    navController.navigate(if (income) "edit_income/$id" else "edit_invoice/$id")
                })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }, viewModel = settingsModel,
                    onNavigateToAutomation = { navController.navigate(Routes.AUTOMATION) },
                    onNavigateToWallet = { navController.navigate(Routes.WALLET) { launchSingleTop = true } },
                    onNavigateToPremium = { navController.navigate(Routes.PREMIUM) },
                    onNavigateToBackup = { navController.navigate(Routes.BACKUP) { launchSingleTop = true } },
                    onNavigateToSection = { section -> navController.navigate("settings/${section.name}") },
                    onNavigateToPersonalize = { navController.navigate(Routes.PERSONALIZE) }
                )
            }
            composable(Routes.SETTINGS_PAGE) { entry ->
                val section = entry.arguments?.getString("section")?.let { runCatching { com.gastos.feature.settings.SettingsPage.valueOf(it) }.getOrNull() }
                    ?: com.gastos.feature.settings.SettingsPage.OVERVIEW
                SettingsScreen(onNavigateBack = { navController.popBackStack() }, viewModel = settingsModel, page = section)
            }
            composable(Routes.PERSONALIZE) {
                DashboardScreen(defaultCurrency = defaultCurrency, customizationOnly = true, onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.PREMIUM) {
                PremiumScreen(onNavigateBack = { navController.popBackStack() }, viewModel = settingsModel)
            }
            navigation(startDestination = Routes.BACKUP, route = "data_graph") {
                composable(Routes.BACKUP) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry("data_graph") }
                    val backupModel: com.gastos.feature.backup.BackupViewModel = hiltViewModel(parent)
                    BackupScreen(onNavigateBack = { navController.popBackStack() }, viewModel = backupModel,
                        page = com.gastos.feature.backup.DataPage.OVERVIEW,
                        onNavigateToBank = { navController.navigate(Routes.BANK) },
                        onNavigateToSection = { navController.navigate("backup/${it.name}") },
                        onNavigateToPremium = { navController.navigate(Routes.PREMIUM) })
                }
                composable(Routes.DATA_PAGE) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry("data_graph") }
                    val backupModel: com.gastos.feature.backup.BackupViewModel = hiltViewModel(parent)
                    val page = entry.arguments?.getString("section")?.let { runCatching { com.gastos.feature.backup.DataPage.valueOf(it) }.getOrNull() }
                        ?: com.gastos.feature.backup.DataPage.OVERVIEW
                    BackupScreen(onNavigateBack = { navController.popBackStack() }, viewModel = backupModel, page = page,
                        onNavigateToPremium = { navController.navigate(Routes.PREMIUM) })
                }
            }
            composable("edit_invoice/{invoiceId}") { backStackEntry ->
                val invoiceId = backStackEntry.arguments?.getString("invoiceId")?.toLongOrNull() ?: 0L
                EditInvoiceScreen(
                    invoiceId = invoiceId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("edit_income/{incomeId}") { backStackEntry ->
                val incomeId = backStackEntry.arguments?.getString("incomeId")?.toLongOrNull() ?: 0L
                EditIncomeScreen(
                    incomeId = incomeId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            }

            floatingButton?.let { spec ->
                MovableFloatingActionButton(
                    id = spec.id,
                    position = floatingButtonPositions[spec.id] ?: FloatingButtonPosition(),
                    bottomContentPadding = innerPadding.calculateBottomPadding(),
                    icon = spec.icon,
                    contentDescription = spec.contentDescription,
                    onClick = spec.onClick,
                    onPositionChanged = { position ->
                        onFloatingButtonPositionChanged(spec.id, position)
                    }
                )
            }
        }
    }
}

private data class FloatingButtonSpec(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit
)
