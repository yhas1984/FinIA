package com.gastos.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.gastos.common.design.EssentialLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFCDB7F7), onPrimary = Color(0xFF2B1947),
    primaryContainer = Color(0xFF2D243E), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF8BD6B1), onSecondary = Color(0xFF003923),
    secondaryContainer = Color(0xFF2D243E), onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFF8BD6B1), tertiaryContainer = Color(0xFF193B2B),
    background = Color(0xFF10131A), onBackground = Color(0xFFEFEBF5),
    surface = Color(0xFF1D2026), onSurface = Color(0xFFEFEBF5),
    surfaceVariant = Color(0xFF29252F), onSurfaceVariant = Color(0xFFBCB4C8),
    surfaceContainerLowest = Color(0xFF10131A), surfaceContainerLow = Color(0xFF1D2026),
    surfaceContainer = Color(0xFF1D2026), surfaceContainerHigh = Color(0xFF29252F),
    surfaceContainerHighest = Color(0xFF29252F),
    outlineVariant = Color(0xFF36303F), error = Color(0xFFFFB4AB), errorContainer = Color(0xFF57241F)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0EAF9), onPrimaryContainer = Color(0xFF3B2469),
    secondary = Color(0xFF247154), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0EAF9), onSecondaryContainer = Color(0xFF3B2469),
    tertiary = Color(0xFF247154), tertiaryContainer = Color(0xFFE5F4EB),
    background = Color(0xFFFCFAFF), onBackground = Color(0xFF25212E),
    surface = Color.White, onSurface = Color(0xFF25212E),
    surfaceVariant = Color(0xFFF3F0F7), onSurfaceVariant = Color(0xFF736C7E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF3F0F7),
    surfaceContainerHighest = Color.White,
    outlineVariant = Color(0xFFECE6F1), error = Color(0xFFB3261E), errorContainer = Color(0xFFFCE9E7)
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
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            small = EssentialLayout.fieldShape,
            medium = EssentialLayout.cardShape,
            large = EssentialLayout.cardShape,
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
