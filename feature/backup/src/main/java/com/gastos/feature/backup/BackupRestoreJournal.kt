package com.gastos.feature.backup

import android.content.Context
import com.gastos.repository.RestorableSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal enum class RestoreJournalPhase {
    STAGED,
    IMAGES_SWAPPED,
    SETTINGS_RESTORED,
    DB_RESTORED
}

@Serializable
internal data class RestoreJournalEntry(
    val phase: String,
    val previousSettings: RestoreJournalSettings,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
internal data class RestoreJournalSettings(
    val systemInstructions: String,
    val defaultCurrency: String,
    val defaultCountry: String,
    val darkMode: String
) {
    fun toDomain(): RestorableSettings = RestorableSettings(
        systemInstructions = systemInstructions,
        defaultCurrency = defaultCurrency,
        defaultCountry = defaultCountry,
        darkMode = darkMode
    )

    companion object {
        fun from(settings: RestorableSettings): RestoreJournalSettings = RestoreJournalSettings(
            systemInstructions = settings.systemInstructions,
            defaultCurrency = settings.defaultCurrency,
            defaultCountry = settings.defaultCountry,
            darkMode = settings.darkMode
        )
    }
}

@Singleton
class BackupRestoreJournal @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file: File = File(context.filesDir, FILE_NAME)
    private val json: Json = Json { ignoreUnknownKeys = true }

    internal fun read(): RestoreJournalEntry? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<RestoreJournalEntry>(file.readText()) }.getOrNull()
    }

    internal fun write(phase: RestoreJournalPhase, previousSettings: RestorableSettings) {
        val entry = RestoreJournalEntry(
            phase = phase.name,
            previousSettings = RestoreJournalSettings.from(previousSettings)
        )
        file.writeText(json.encodeToString(entry))
    }

    internal fun clear() {
        if (file.exists()) file.delete()
    }

    private companion object {
        const val FILE_NAME = "backup_restore_journal.json"
    }
}
