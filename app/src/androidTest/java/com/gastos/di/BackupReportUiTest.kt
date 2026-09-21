package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.feature.backup.BackupScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BackupReportUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun spanishReportActionsAreGroupedAndVisible() = check("es")
    @Test fun englishReportActionsAreGroupedAndVisible() = check("en")
    @Suppress("DEPRECATION")
    private fun check(language: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = Configuration(context.resources.configuration)
        val oldLocale = Locale.getDefault()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val locale = Locale(language)
                    Locale.setDefault(locale)
                    activity.resources.updateConfiguration(Configuration(activity.resources.configuration).apply { setLocale(locale) }, activity.resources.displayMetrics)
                    activity.setContent { MaterialTheme { BackupScreen(onNavigateBack = {}) } }
                }
                val export = if (language == "es") "Exportar informe" else "Export report"
                compose.onNodeWithText(export).performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithText("CSV").assertIsDisplayed()
                compose.onNodeWithText("PDF").assertIsDisplayed()
                compose.onNodeWithText(if (language == "es") "Guardar" else "Save").assertIsDisplayed()
                compose.onNodeWithText(if (language == "es") "Compartir" else "Share").assertIsDisplayed()
                compose.onAllNodes(isDialog()).assertCountEquals(1)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                // Window animations are outside Compose's test clock on Android 8.
                android.os.SystemClock.sleep(500)
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.filesDir,"report-dialog-$language.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally {
            Locale.setDefault(oldLocale)
            context.resources.updateConfiguration(old,context.resources.displayMetrics)
        }
    }
}
