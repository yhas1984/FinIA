package com.gastos.feature.backup

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    fun getSignInClient(): GoogleSignInClient = googleSignInClient

    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, Scope(DRIVE_FILE_SCOPE))
    }

    fun getSignedInEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    suspend fun createBackup(): BackupResult {
        return try {
            if (!isSignedIn()) {
                return BackupResult(
                    success = false,
                    message = "Debes iniciar sesión con Google para hacer backup"
                )
            }

            // Crear backup del archivo de base de datos
            val dbFile = context.getDatabasePath("gastos_ingresos_db")
            if (!dbFile.exists()) {
                return BackupResult(
                    success = false,
                    message = "No hay datos para respaldar"
                )
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFileName = "finai_backup_$timestamp.db"

            // Copiar archivo de base de datos
            val backupFile = File(context.cacheDir, backupFileName)
            dbFile.copyTo(backupFile, overwrite = true)

            // TODO: Subir a Google Drive usando la API
            // Por ahora, solo copiamos localmente
            val backupDir = File(context.getExternalFilesDir(null), "backups")
            backupDir.mkdirs()
            val destinationFile = File(backupDir, backupFileName)
            backupFile.copyTo(destinationFile, overwrite = true)

            BackupResult(
                success = true,
                message = "Backup creado: $backupFileName"
            )

        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "Error al crear backup: ${e.message}"
            )
        }
    }

    suspend fun restoreBackup(): BackupResult {
        return try {
            // TODO: Implementar restauración desde Google Drive
            BackupResult(
                success = false,
                message = "Restauración desde Google Drive no implementada aún"
            )

        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "Error al restaurar: ${e.message}"
            )
        }
    }

    fun getLocalBackups(): List<File> {
        val backupDir = File(context.getExternalFilesDir(null), "backups")
        return if (backupDir.exists()) {
            backupDir.listFiles()?.filter { it.extension == "db" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun deleteBackup(file: File): Boolean {
        return file.delete()
    }
}
