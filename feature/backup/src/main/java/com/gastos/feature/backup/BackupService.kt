package com.gastos.feature.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gastos.local.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup local de la base de datos Room.
 *
 * Implementa dos responsabilidades:
 *   • Crear/eliminar copias locales de la BD SQLite después de integrar
 *     las escrituras pendientes del WAL mediante un checkpoint.
 * Drive NO está implementado: el botón "Crear Backup" sólo copia en
 * almacenamiento interno de la app hasta que se suba a la nube de verdad.
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    /**
     * Crea una copia de seguridad local de la base de datos, forzando un
     * checkpoint de WAL para garantizar que `db`, `-wal` y `-shm` están
     * consistentes antes de copiarlos.
     */
    suspend fun createBackup(): BackupResult {
        return try {
            val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                return BackupResult(
                    success = false,
                    message = "No hay datos para respaldar"
                )
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFileName = "finai_backup_$timestamp.db"
            val cacheFile = File(context.cacheDir, backupFileName)

            // 1) Forzar checkpoint de WAL/SHM para tener un snapshot consistente.
            checkpointWal(database.openHelper.writableDatabase)
            // 2) El checkpoint TRUNCATE ya integró el WAL; la copia del .db
            //    contiene el snapshot completo.
            copyAtomic(dbFile, cacheFile)

            // 3) Persistir la copia en el almacenamiento externo privado de la
            //    app, que es accesible para el usuario vía "Archivos".
            val backupDir = File(context.getExternalFilesDir(null), "backups")
            backupDir.mkdirs()
            val destinationFile = File(backupDir, backupFileName)
            cacheFile.copyTo(destinationFile, overwrite = true)
            cacheFile.delete()

            BackupResult(
                success = true,
                message = "Backup local creado: $backupFileName"
            )
        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "Error al crear backup: ${e.message}"
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

    fun deleteBackup(file: File): Boolean = file.delete()

    private fun checkpointWal(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    private fun copyAtomic(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

}
