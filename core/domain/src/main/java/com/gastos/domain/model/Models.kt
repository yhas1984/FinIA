package com.gastos.domain.model

enum class InvoiceType { GASTO, INGRESO }

/**
 * Monedas soportadas por FinAI (alineadas con la lista de Settings).
 * Usar esta constante en todos los formularios y pantallas para evitar
 * divergencias.
 */
val SUPPORTED_CURRENCIES: List<String> = listOf(
    "EUR", "USD", "MXN", "ARS", "COP", "CLP", "PEN",
    "BOB", "GTQ", "NIO", "PYG", "UYU", "VES"
)

data class Invoice(
    val id: Long = 0,
    val fecha: Long,
    val proveedor: String,
    val tipo: InvoiceType,
    val moneda: String = "EUR",
    /**
     * Importe total **IVA incluido** (lo que paga el cliente en el mostrador).
     * Si hay productos asociados, debería ser su suma. Para facturas IRPF
     * ya está NETO de la retención.
     */
    val total: Double,
    /**
     * % de IVA por defecto. Sólo se aplica cuando la factura NO tiene
     * productos asociados (entrada 100% manual). Con productos, cada
     * línea lleva su propio IVA%.
     *
     * Default 0.0 en lugar de 21.0: evitamos mezclar el "ticket medio de
     * Mercadona con productos al 4% / 10% / 21%" con un único valor global
     * que no representa nada real.
     */
    val ivaPercent: Double = 0.0,
    /** Porcentaje de retención IRPF (e.g. 15.0). 0 si no aplica. */
    val irpfPercent: Double = 0.0,
    /**
     * Base imponible = subtotal sin IVA. Procedencia:
     *   - Si hay productos: agregado de productos[i].baseImponible
     *   - Si no: total / (1 + ivaPercent/100)
     */
    val baseImponible: Double = 0.0,
    /** Cuota de IVA. Procedencia análoga a `baseImponible`. */
    val cuotaIva: Double = 0.0,
    /** Cuota de retención IRPF. Solo si `irpfPercent > 0`. */
    val cuotaIrpf: Double = 0.0,
    val paisCodigo: String = "ES",
    val nifEmisor: String? = null,
    val nifReceptor: String? = null,
    val imagenUri: String? = null,
    val ocrRawText: String? = null,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Indica si la factura tiene una sola tasa de IVA efectiva (entrada
     * manual) frente a varias (ticket OCR con productos al 4/10/21%).
     * En el segundo caso, el IVA% global no representa nada y los
     * cálculos deben venir de los productos.
     */

    /** Total con IVA. Alias legible de [total]. */
    val totalConIva: Double get() = total
}

data class Product(
    val id: Long = 0,
    val invoiceId: Long,
    val descripcion: String,
    /** Cantidad de unidades (e.g. 2 panes). */
    val cantidad: Double = 1.0,
    /**
     * Precio unitario **SIN IVA** (base imponible por unidad).
     *
     * Los tickets de tienda (Mercadona, Carrefour…) muestran precios
     * IVA-incluido. Al extraerlos por OCR / IA, **siempre** hay que
     * convertir: `precioSinIva = precioConIva / (1 + ivaPercent/100)`.
     * Guardar el precio SIN IVA garantiza:
     *   - subtotal = base imponible total (sumable con el resto)
     *   - ivaAmount = subtotal × ivaPercent / 100 (cuota correcta)
     *   - totalConIva = subtotal + ivaAmount (lo que el cliente paga)
     */
    val precioUnitario: Double,
    /** Base imponible total de la línea = cantidad × precioUnitario. */
    val subtotal: Double = cantidad * precioUnitario,
    /** % de IVA aplicado (e.g. 21.0, 10.0, 4.0). */
    val ivaPercent: Double = 21.0,
    /** Cuota de IVA = subtotal × ivaPercent / 100. */
    val ivaAmount: Double = subtotal * ivaPercent / 100.0,
    val categoriaId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Precio total de la línea con IVA incluido (lo que paga el cliente). */
    val totalConIva: Double get() = subtotal + ivaAmount

    /** Precio unitario con IVA incluido (lo que ve el cliente en el ticket). */
    val precioUnitarioConIva: Double get() = subtotal / cantidad + ivaAmount / cantidad
}

data class Income(
    val id: Long = 0,
    val fecha: Long,
    val concepto: String,
    /**
     * Importe neto recibido (después de retenciones IRPF y SS).
     * Si no hay retenciones, equivale al totalDevengado.
     */
    val monto: Double,
    /** Bruto antes de retenciones (nomina: suele ser el "total devengado"). */
    val totalDevengado: Double = 0.0,
    /** Neto después de retenciones = totalDevengado − IRPF − SS. Alias de [monto]. */
    val totalNeto: Double = 0.0,
    val moneda: String = "EUR",
    val fuente: String? = null,
    /** IVA repercutido (en facturas emitidas; 0 para nómina). */
    val ivaPercent: Double = 0.0,
    /** Retención IRPF aplicada (%). */
    val irpfPercent: Double = 0.0,
    val imagenUri: String? = null,
    val notas: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Base imponible de la factura emitida (sólo si ivaPercent > 0). */
    val baseImponible: Double
        get() = if (ivaPercent > 0) monto / (1 + ivaPercent / 100.0) else monto

    /** Cuota de IVA repercutido (sólo si ivaPercent > 0). */
    val cuotaIva: Double
        get() = if (ivaPercent > 0) baseImponible * ivaPercent / 100.0 else 0.0
}

