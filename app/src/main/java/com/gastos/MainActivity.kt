package com.gastos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gastos.feature.dashboard.DashboardScreen
import com.gastos.feature.invoices.InvoicesScreen
import com.gastos.feature.invoices.EditInvoiceScreen
import com.gastos.feature.products.ProductsScreen
import com.gastos.feature.incomes.IncomesScreen
import com.gastos.feature.incomes.EditIncomeScreen
import com.gastos.feature.ocr.ScanInvoiceScreen
import com.gastos.feature.voice.VoiceCommandScreen
import com.gastos.feature.settings.SettingsScreen
import com.gastos.feature.settings.SettingsViewModel
import com.gastos.feature.settings.PremiumScreen
import com.gastos.feature.backup.BackupScreen
import com.gastos.feature.fiscal.FiscalConfigScreen
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.ui.theme.GastosEIngresosTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val uiState by settingsViewModel.uiState.collectAsState()

            GastosEIngresosTheme(darkMode = uiState.settings.darkMode) {
                FinAIApp()
            }
        }
    }
}

sealed class Screen(val route: String, val title: String, val selectedIcon: @Composable () -> Unit, val unselectedIcon: @Composable () -> Unit) {
    object Dashboard : Screen(
        route = "dashboard",
        title = "Dashboard",
        selectedIcon = { Icon(Icons.Filled.Dashboard, contentDescription = "Dashboard") },
        unselectedIcon = { Icon(Icons.Outlined.Dashboard, contentDescription = "Dashboard") }
    )
    object Invoices : Screen(
        route = "invoices",
        title = "Facturas",
        selectedIcon = { Icon(Icons.Filled.Description, contentDescription = "Facturas") },
        unselectedIcon = { Icon(Icons.Outlined.Description, contentDescription = "Facturas") }
    )
    object Products : Screen(
        route = "products",
        title = "Productos",
        selectedIcon = { Icon(Icons.Filled.Inventory, contentDescription = "Productos") },
        unselectedIcon = { Icon(Icons.Outlined.Inventory, contentDescription = "Productos") }
    )
    object Incomes : Screen(
        route = "incomes",
        title = "Ingresos",
        selectedIcon = { Icon(Icons.Filled.Payments, contentDescription = "Ingresos") },
        unselectedIcon = { Icon(Icons.Outlined.Payments, contentDescription = "Ingresos") }
    )
}

// Rutas sin bottom bar
object Routes {
    const val SCAN_INVOICE = "scan_invoice"
    const val VOICE_COMMAND = "voice_command"
    const val SETTINGS = "settings"
    const val PREMIUM = "premium"
    const val BACKUP = "backup"
    const val FISCAL_CONFIG = "fiscal_config"
    const val EDIT_INVOICE = "edit_invoice/{invoiceId}"
    const val EDIT_INCOME = "edit_income/{incomeId}"
    const val CHATBOT = "chatbot"
}

@Composable
fun FinAIApp() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Dashboard, Screen.Invoices, Screen.Products, Screen.Incomes)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determinar si mostrar bottom bar
    val showBottomBar = currentDestination?.route in screens.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    screens.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = { if (selected) screen.selectedIcon() else screen.unselectedIcon() },
                            label = { Text(screen.title) },
                            selected = selected,
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
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Pantallas principales (con bottom bar)
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToChat = { navController.navigate(Routes.CHATBOT) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToBackup = { navController.navigate(Routes.BACKUP) }
                )
            }
            composable(Screen.Invoices.route) {
                InvoicesScreen(
                    onNavigateToEdit = { invoiceId ->
                        navController.navigate("edit_invoice/$invoiceId")
                    }
                )
            }
            composable(Screen.Products.route) {
                ProductsScreen()
            }
            composable(Screen.Incomes.route) {
                IncomesScreen(
                    onNavigateToEdit = { incomeId ->
                        navController.navigate("edit_income/$incomeId")
                    }
                )
            }

            // Pantallas secundarias (sin bottom bar)
            composable(Routes.SCAN_INVOICE) {
                ScanInvoiceScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.VOICE_COMMAND) {
                VoiceCommandScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.CHATBOT) {
                ChatbotScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPremium = { navController.navigate(Routes.PREMIUM) },
                    onNavigateToBackup = { navController.navigate(Routes.BACKUP) }
                )
            }
            composable(Routes.PREMIUM) {
                PremiumScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.BACKUP) {
                BackupScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Routes.FISCAL_CONFIG) {
                FiscalConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
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
    }
}
