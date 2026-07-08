package com.gastos.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Helper para aplicar colores de barras en API 23+ sin usar las APIs deprecated.
// statusBarColor/navigationBarColor están deprecated en API 35; en API 23+ basta
// con controlar la apariencia de los iconos (claro/oscuro) vía WindowInsetsController.
private fun applySystemBarColors(
    window: android.view.Window,
    view: android.view.View,
    color: Color,
    useDark: Boolean
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        @Suppress("DEPRECATION")
        window.statusBarColor = color.toArgb()
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        @Suppress("DEPRECATION")
        window.navigationBarColor = color.toArgb()
    }
    val insetsController = WindowCompat.getInsetsController(window, view)
    insetsController.isAppearanceLightStatusBars = !useDark
    insetsController.isAppearanceLightNavigationBars = !useDark
}

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
            applySystemBarColors(window, view, colorScheme.surface, useDark)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
