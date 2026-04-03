package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun exportToCsv(
        invoices: List<com.gastos.domain.model.Invoice>,
        incomes: List<com.gastos.domain.model.Income>,
        products: List<com.gastos.domain.model.Product>
    ): Result<File> {
        return try {
            val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val csvFile = File(exportDir, "finai_export_$timestamp.csv")

            FileWriter(csvFile).use { writer ->
                // Header
                writer.append("Tipo,ID,Fecha,Concepto,Monto,Moneda,IVA%,IRPF%,Notas\n")

                // Invoices
                invoices.forEach { invoice ->
                    writer.append(
                        buildString {
                            append(if (invoice.tipo == com.gastos.domain.model.InvoiceType.GASTO) "Gasto" else "Ingreso")
                            append(",")
                            append(invoice.id)
                            append(",")
                            append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(invoice.fecha)))
                            append(",")
                            append("\"${invoice.proveedor.replace("\"", "\"\"")}\"")
                            append(",")
                            append(invoice.total)
                            append(",")
                            append(invoice.moneda)
                            append(",")
                            append(invoice.ivaPercent)
                            append(",")
                            append(invoice.irpfPercent)
                            append(",")
                            append("\"${(invoice.notas ?: "").replace("\"", "\"\"")}\"")
                            append("\n")
                        }
                    )
                }

                // Products
                products.forEach { product ->
                    writer.append(
                        buildString {
                            append("Producto")
                            append(",")
                            append(product.id)
                            append(",")
                            append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(product.createdAt)))
                            append(",")
                            append("\"${product.descripcion.replace("\"", "\"\"")}\"")
                            append(",")
                            append(product.subtotal)
                            append(",")
                            append("EUR")
                            append(",")
                            append(product.ivaPercent)
                            append(",")
                            append("0")
                            append(",")
                            append("\"\"")
                            append("\n")
                        }
                    )
                }

                // Incomes
                incomes.forEach { income ->
                    writer.append(
                        buildString {
                            append("Ingreso")
                            append(",")
                            append(income.id)
                            append(",")
                            append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(income.fecha)))
                            append(",")
                            append("\"${income.concepto.replace("\"", "\"\"")}\"")
                            append(",")
                            append(income.monto)
                            append(",")
                            append(income.moneda)
                            append(",")
                            append(income.ivaPercent)
                            append(",")
                            append(income.irpfPercent)
                            append(",")
                            append("\"${(income.notas ?: "").replace("\"", "\"\"")}\"")
                            append("\n")
                        }
                    )
                }
            }

            Result.success(csvFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FinAI Export - ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}")
            putExtra(Intent.EXTRA_TEXT, "Exportación de datos de FinAI")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
