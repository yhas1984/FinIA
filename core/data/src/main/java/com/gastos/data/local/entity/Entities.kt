package com.gastos.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gastos.domain.model.InvoiceType

@Entity(tableName = "invoices", indices = [Index(value = ["documentUuid"], unique = true), Index("documentKey"), Index("sourceSha256")])
data class InvoiceEntity(
    @ColumnInfo(defaultValue = "'[]'") val taxesJson: String = "[]",
    val evidenceJson: String? = null,
    val documentKey: String? = null,
    val sourceSha256: String? = null,
    @ColumnInfo(defaultValue = "''") val documentUuid: String = java.util.UUID.randomUUID().toString(),
    val driveAccountId: String? = null,
    val driveContentHash: String? = null,
    val driveSyncError: String? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fecha: Long,
    val proveedor: String,
    val tipo: InvoiceType,
    val categoria: String? = null,
    val subcategoria: String? = null,
    val moneda: String = "EUR",
    val total: Double,
    val numeroFactura: String? = null,
    val baseImponible: Double? = null,
    val cuotaIva: Double? = null,
    val ivaPercent: Double? = 21.0,
    val irpfPercent: Double = 0.0,
    val paisCodigo: String = "ES",
    val nifEmisor: String? = null,
    val nifReceptor: String? = null,
    val imagenUri: String? = null,
    val driveFileId: String? = null,
    val driveWebViewLink: String? = null,
    @ColumnInfo(defaultValue = "0") val driveUploadPending: Boolean = false,
    val ocrRawText: String? = null,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("invoiceId")]
)
data class ProductEntity(
    @ColumnInfo(defaultValue = "'[]'") val taxesJson: String = "[]",
    @ColumnInfo(defaultValue = "1") val pricesIncludeTax: Boolean = true,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val descripcion: String,
    val cantidad: Double = 1.0,
    val precioUnitario: Double,
    val subtotal: Double = cantidad * precioUnitario,
    val ivaPercent: Double? = 21.0,
    val ivaAmount: Double? = ivaPercent?.let { subtotal * it / (100.0 + it) },
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "incomes", indices = [Index(value = ["documentUuid"], unique = true), Index("documentKey"), Index("sourceSha256")])
data class IncomeEntity(
    @ColumnInfo(defaultValue = "'[]'") val taxesJson: String = "[]",
    val evidenceJson: String? = null,
    val documentKey: String? = null,
    val sourceSha256: String? = null,
    @ColumnInfo(defaultValue = "''") val documentUuid: String = java.util.UUID.randomUUID().toString(),
    val driveAccountId: String? = null,
    val driveContentHash: String? = null,
    val driveSyncError: String? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fecha: Long,
    val concepto: String,
    val monto: Double,
    val totalDevengado: Double = 0.0,
    val totalNeto: Double = 0.0,
    val moneda: String = "EUR",
    val fuente: String? = null,
    val categoria: String? = null,
    val subcategoria: String? = null,
    val ivaPercent: Double? = 0.0,
    val irpfPercent: Double = 0.0,
    val imagenUri: String? = null,
    val driveFileId: String? = null,
    val driveWebViewLink: String? = null,
    @ColumnInfo(defaultValue = "0") val driveUploadPending: Boolean = false,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "country_fiscal_config")
data class CountryFiscalConfigEntity(
    @PrimaryKey val paisCodigo: String,
    val nombrePais: String,
    val ivaRates: String = "21",
    val irpfRate: Double? = null,
    val nifFormat: String = "",
    val nombreLeyFiscal: String = "IVA"
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val visibleText: String,
    val contextText: String? = null,
    @ColumnInfo(defaultValue = "1") val includeInContext: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val operationUuid: String? = null,
    val documentUuid: String? = null,
    val documentKind: String? = null
)

@Entity(tableName = "restore_markers")
data class RestoreMarkerEntity(
    @PrimaryKey val id: Int = 1,
    val restoreId: String,
    val committedAt: Long = System.currentTimeMillis()
)
