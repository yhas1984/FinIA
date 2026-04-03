package com.gastos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gastos.domain.model.InvoiceType

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fecha: Long,
    val proveedor: String,
    val tipo: InvoiceType,
    val moneda: String = "EUR",
    val total: Double,
    val ivaPercent: Double = 21.0,
    val irpfPercent: Double = 0.0,
    val paisCodigo: String = "ES",
    val nifEmisor: String? = null,
    val nifReceptor: String? = null,
    val imagenUri: String? = null,
    val ocrRawText: String? = null,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val descripcion: String,
    val cantidad: Double = 1.0,
    val precioUnitario: Double,
    val subtotal: Double = cantidad * precioUnitario,
    val ivaPercent: Double = 21.0,
    val ivaAmount: Double = subtotal * ivaPercent / 100.0,
    val categoriaId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "incomes")
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fecha: Long,
    val concepto: String,
    val monto: Double,
    val totalDevengado: Double = 0.0,
    val totalNeto: Double = 0.0,
    val moneda: String = "EUR",
    val fuente: String? = null,
    val ivaPercent: Double = 0.0,
    val irpfPercent: Double = 0.0,
    val imagenUri: String? = null,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val icono: String = "category",
    val color: Long = 0xFF4CAF50,
    val esDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "exchange_rates")
data class ExchangeRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val monedaOrigen: String,
    val monedaDestino: String,
    val tasa: Double,
    val fecha: Long = System.currentTimeMillis()
)

@Entity(tableName = "country_fiscal_config")
data class CountryFiscalConfigEntity(
    @PrimaryKey val paisCodigo: String,
    val nombrePais: String,
    val ivaRates: String = "[21]",
    val irpfRate: Double? = null,
    val nifFormat: String = "",
    val nombreLeyFiscal: String = "IVA"
)