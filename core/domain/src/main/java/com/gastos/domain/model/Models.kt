package com.gastos.domain.model

enum class InvoiceType { GASTO, INGRESO }

data class Invoice(
    val id: Long = 0,
    val fecha: Long,
    val proveedor: String,
    val tipo: InvoiceType,
    val moneda: String = "EUR",
    val total: Double,
    /** Base imponible (facturas de gasto). */
    val baseImponible: Double = 0.0,
    /** Cuota de IVA en importe (no el porcentaje). */
    val ivaImporte: Double = 0.0,
    val ivaPercent: Double = 21.0,
    val irpfPercent: Double = 0.0,
    val paisCodigo: String = "ES",
    val nifEmisor: String? = null,
    val nifReceptor: String? = null,
    val imagenUri: String? = null,
    val ocrRawText: String? = null,
    val notas: String? = null,
    val categoria: String? = null,
    /** Solo INGRESO: total devengado / bruto de la nómina. */
    val ingresoDevengado: Double = 0.0,
    /** Solo INGRESO: suma de deducciones (SS + IRPF + otras). */
    val ingresoDeducciones: Double = 0.0,
    /** Solo INGRESO: tipo descriptivo (ej. nómina, complemento). */
    val ingresoTipo: String? = null,
    /** Solo INGRESO: concepto visible (ej. «Nómina marzo 2025»). Si null, se usa proveedor. */
    val conceptoIngreso: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class Product(
    val id: Long = 0,
    val invoiceId: Long,
    val descripcion: String,
    val cantidad: Double = 1.0,
    val precioUnitario: Double,
    val subtotal: Double = cantidad * precioUnitario,
    val ivaPercent: Double = 21.0,
    val ivaAmount: Double = subtotal * ivaPercent / 100.0,
    val categoriaId: Long? = null,
    val categoria: String? = null,
    /** Comercio / proveedor de la línea (exportación). */
    val comercio: String? = null,
    /** Fecha de la compra en ms (misma que la factura si no se indica). */
    val fechaCompra: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class Income(
    val id: Long = 0,
    val fecha: Long,
    val concepto: String,
    val monto: Double,
    val totalDevengado: Double = 0.0,
    val totalDeducciones: Double = 0.0,
    val totalNeto: Double = 0.0,
    val moneda: String = "EUR",
    /** Tipo de ingreso (ej. nómina, venta, otros). */
    val tipoIngreso: String? = null,
    val fuente: String? = null,
    val ivaPercent: Double = 0.0,
    val irpfPercent: Double = 0.0,
    val imagenUri: String? = null,
    val notas: String? = null,
    val categoria: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class Category(
    val id: Long = 0,
    val nombre: String,
    val icono: String = "category",
    val color: Long = 0xFF4CAF50,
    val esDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class ExchangeRate(
    val id: Long = 0,
    val monedaOrigen: String,
    val monedaDestino: String,
    val tasa: Double,
    val fecha: Long = System.currentTimeMillis()
)

data class CountryFiscalConfig(
    val paisCodigo: String,
    val nombrePais: String,
    val ivaRates: List<Double> = listOf(21.0),
    val irpfRate: Double? = null,
    val nifFormat: String = "",
    val nombreLeyFiscal: String = "IVA"
)