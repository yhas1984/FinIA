package com.gastos.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gastos.feature.settings.SettingsRepository

private val DarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = Color.Black,
    primaryContainer = ElectricVioletContainer,
    onPrimaryContainer = Color.White,
    secondary = NeonEmerald,
    onSecondary = Color.Black,
    secondaryContainer = NeonEmeraldContainer,
    onSecondaryContainer = Color.White,
    background = ObsidianBackground,
    onBackground = OnSurfaceText,
    surface = ObsidianBackground,
    onSurface = OnSurfaceText,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceText,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    outlineVariant = OutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEF8),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF388E3C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF1B5E20),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    outlineVariant = Color(0xFFCAC4D0)
)

@Composable
fun GastosEIngresosTheme(
    darkMode: String = "system",
    content: @Composable () -> Unit
) {
    val useDark = when (darkMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
