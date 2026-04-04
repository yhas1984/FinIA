package com.gastos

import com.gastos.feature.ai.AIService
import com.gastos.feature.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FinAiSettingsEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun aiService(): AIService
}
