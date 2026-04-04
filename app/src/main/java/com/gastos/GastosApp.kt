package com.gastos

import android.app.Application
import com.gastos.feature.ai.AIEngine
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class GastosApp : Application() {

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val entry = EntryPointAccessors.fromApplication(this, FinAiSettingsEntryPoint::class.java)
        initScope.launch {
            val settings = entry.settingsRepository().settings.first()
            when (settings.aiEngine) {
                "gemma_local" -> entry.aiService().setEngine(AIEngine.GEMMA_LOCAL)
                else -> entry.aiService().setEngine(AIEngine.GEMINI_API, settings.geminiApiKey)
            }
        }
    }
}

