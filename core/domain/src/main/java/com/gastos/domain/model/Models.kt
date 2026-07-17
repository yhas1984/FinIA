package com.gastos.domain.model

enum class InvoiceType { GASTO, INGRESO }

data class Invoice(
    val id: Long = 0,
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
) {
    /** Convierte una factura tipo INGRESO a un Income para persistirlo en la tabla correcta. */
    fun toIncome(): Income = Income(
        fecha = fecha,
        concepto = proveedor,
        monto = total,
        totalDevengado = if (totalDevengadoSafe > 0) totalDevengadoSafe else total,
        totalNeto = if (totalNetoSafe > 0) totalNetoSafe else total,
        moneda = moneda,
        fuente = nifEmisor,
        ivaPercent = ivaPercent,
        irpfPercent = irpfPercent,
        imagenUri = imagenUri,
        notas = notas
    )

    private val totalDevengadoSafe: Double get() = 0.0
    private val totalNetoSafe: Double get() = 0.0
}

data class Product(
    val id: Long = 0,
    val invoiceId: Long,
    val descripcion: String,
    val cantidad: Double = 1.0,
    val precioUnitario: Double,
    val subtotal: Double = cantidad * precioUnitario,
    val ivaPercent: Double = 21.0,
    val ivaAmount: Double = subtotal * ivaPercent / 100.0,
    val createdAt: Long = System.currentTimeMillis()
)

data class Income(
    val id: Long = 0,
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

data class CountryFiscalConfig(
    val paisCodigo: String,
    val nombrePais: String,
    val ivaRates: List<Double> = listOf(21.0),
    val irpfRate: Double? = null,
    val nifFormat: String = "",
    val nombreLeyFiscal: String = "IVA"
)