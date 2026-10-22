package com.gastos.feature.backup

import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.TaxPeriodType
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone

/**
 * Resumen de IVA por periodo de liquidación.
 *
 * Reglas de cálculo, y de dónde salen:
 * - Tipos vigentes en España: general 21 %, reducidos 10 % y 4 %, y 0 % en determinadas operaciones
 *   (AEAT, «Tipos impositivos de IVA»).
 * - Periodo de liquidación: con carácter general trimestral (AEAT, «Período de declaración»).
 * - Plazos: trimestral del 1 al 20 del mes siguiente (abril, julio, octubre); el cuarto trimestre
 *   del 1 al 30 de enero (AEAT, «Plazo de presentación del modelo 303»).
 *
 * Este resumen NO es una autoliquidación y NO sustituye al modelo 303: no aplica regímenes
 * especiales (recargo de equivalencia, prórroga, inversión del sujeto pasivo, operaciones
 * intracomunitarias) ni calcula el resultado de la liquidación. Ordena los datos del usuario.
 */
enum class VatPeriodType { QUARTERLY, MONTHLY, ANNUAL }

data class VatPeriod(
    val type: VatPeriodType,
    val year: Int,
    val quarter: Int? = null,
    val month: Int? = null,
    val startInclusive: Long,
    val endExclusive: Long
) {
    /** Etiqueta legible: «1T 2026», «3/2026» o «2026». */
    fun label(): String = when (type) {
        VatPeriodType.QUARTERLY -> "${quarter ?: 0}T $year"
        VatPeriodType.MONTHLY -> "$month/$year"
        VatPeriodType.ANNUAL -> "$year"
    }

    /** Etiqueta en formato de máquina, para leer el plazo de la configuración del país. */
    fun periodCode(): String = when (type) {
        VatPeriodType.QUARTERLY -> "${quarter ?: 0}T"
        VatPeriodType.MONTHLY -> "${month ?: 0}/$year"
        VatPeriodType.ANNUAL -> "$year"
    }

    /**
     * Plazo de presentación, en texto, tomado de las reglas del país cuando existen.
     *
     * España los tiene verificados en la AEAT. Para un país sin reglas verificadas NO se inventa
     * una fecha: se devuelve la regla general que el usuario puede comprobar («mes siguiente»), y
     * el informe deja claro que es orientativo.
     */
    fun deadlineHint(fromConfig: CountryFiscalConfig? = null): String? =
        fromConfig?.plazos?.get(periodCode())?.takeIf { it.isNotBlank() }
            ?: when (type) {
                VatPeriodType.MONTHLY -> "mes siguiente"
                VatPeriodType.QUARTERLY -> "mes siguiente al trimestre"
                VatPeriodType.ANNUAL -> null
            }
}

/**
 * Traduce el periodo declarado por el país al tipo de periodo del informe.
 * USER_DEFINED significa «este país aún no tiene las reglas verificadas»: decide el usuario.
 */
fun CountryFiscalConfig?.vatPeriodTypeOrNull(): VatPeriodType? = when (this?.tipoPeriodo) {
    TaxPeriodType.MONTHLY -> VatPeriodType.MONTHLY
    TaxPeriodType.QUARTERLY -> VatPeriodType.QUARTERLY
    TaxPeriodType.ANNUAL -> VatPeriodType.ANNUAL
    else -> null
}

/** ¿El país admite un resumen de IVA? Estados Unidos, con Sales Tax, no. */
fun CountryFiscalConfig.permiteResumenIva(): Boolean =
    paisCodigo.uppercase() != "US"

object VatPeriodCalculator {

    fun quarter(year: Int, quarter: Int, zone: TimeZone = TimeZone.getDefault()): VatPeriod {
        require(quarter in 1..4) { "trimestre fuera de rango: $quarter" }
        val start = Calendar.getInstance(zone).apply {
            clear()
            set(year, (quarter - 1) * 3, 1, 0, 0, 0)
        }
        val end = Calendar.getInstance(zone).apply {
            clear()
            set(year, (quarter - 1) * 3 + 3, 1, 0, 0, 0)
        }
        return VatPeriod(
            type = VatPeriodType.QUARTERLY,
            year = year,
            quarter = quarter,
            startInclusive = start.timeInMillis,
            endExclusive = end.timeInMillis
        )
    }

    fun month(year: Int, month: Int, zone: TimeZone = TimeZone.getDefault()): VatPeriod {
        require(month in 1..12) { "mes fuera de rango: $month" }
        val start = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
        }
        val end = Calendar.getInstance(zone).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }
        return VatPeriod(
            type = VatPeriodType.MONTHLY,
            year = year,
            month = month,
            startInclusive = start.timeInMillis,
            endExclusive = end.timeInMillis
        )
    }

    /** Trimestre al que pertenece una fecha. */
    fun quarterOf(timestamp: Long, zone: TimeZone = TimeZone.getDefault()): VatPeriod {
        val c = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
        val q = c.get(Calendar.MONTH) / 3 + 1
        return quarter(c.get(Calendar.YEAR), q, zone)
    }

    fun yearOf(year: Int, zone: TimeZone = TimeZone.getDefault()): VatPeriod {
        val start = Calendar.getInstance(zone).apply { clear(); set(year, Calendar.JANUARY, 1, 0, 0, 0) }
        val end = Calendar.getInstance(zone).apply { clear(); set(year + 1, Calendar.JANUARY, 1, 0, 0, 0) }
        return VatPeriod(
            type = VatPeriodType.ANNUAL,
            year = year,
            startInclusive = start.timeInMillis,
            endExclusive = end.timeInMillis
        )
    }

    /**
     * Periodo que corresponde a la configuracion del pais.
     *
     * USER_DEFINED (pais sin reglas verificadas) se resuelve con el periodo que elija el usuario;
     * si no elige, se usa el mensual, que es el mas comun fuera de Espana.
     */
    fun forCountry(
        config: CountryFiscalConfig?,
        year: Int,
        month: Int,
        requested: VatPeriodType,
        zone: TimeZone = TimeZone.getDefault()
    ): VatPeriod = when (requested) {
        VatPeriodType.QUARTERLY -> quarter(year, (month - 1) / 3 + 1, zone)
        VatPeriodType.ANNUAL -> yearOf(year, zone)
        else -> month(year, month, zone)
    }
}

/** Una línea del resumen: un tipo de IVA con su base y su cuota. */
data class VatRateLine(
    val rate: BigDecimal,
    val base: BigDecimal,
    val quota: BigDecimal,
    val documents: Int
)

/** Cero monetario con escala fija de 2 decimales, para que las comparaciones y el formato cuadren. */
internal val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

/** Bloque de IVA: repercutido o soportado. */
data class VatBlock(
    val lines: List<VatRateLine> = emptyList(),
    val base: BigDecimal = ZERO_MONEY,
    val quota: BigDecimal = ZERO_MONEY,
    val documents: Int = 0
)

/** Motivos por los que un documento no entra en el cálculo. Se informan, no se esconden. */
enum class VatExclusion { FOREIGN_CURRENCY, MISSING_TAX_DATA, MISSING_RATE, EXEMPT }

data class FiscalSummary(
    val period: VatPeriod,
    val countryCode: String,
    val taxName: String,
    val currency: String,
    val output: VatBlock,
    val input: VatBlock,
    val result: BigDecimal,
    val irpfWithheld: BigDecimal,
    val irpfPaid: BigDecimal,
    val excluded: Map<VatExclusion, Int> = emptyMap()
) {
    val isPayable: Boolean get() = result.signum() > 0
    val totalExcluded: Int get() = excluded.values.sum()
}

/**
 * Calcula el resumen desde los documentos. Sin dependencias de Android para poder probarlo.
 *
 * No inventa cifras: si un documento no trae desglose de IVA utilizable se cuenta como excluido
 * y se informa en el informe, en vez de deducir una base de un importe ambiguo.
 */
object VatSummaryCalculator {

    fun summary(
        period: VatPeriod,
        currency: String,
        invoices: List<Invoice>,
        incomes: List<Income>,
        fiscalConfig: CountryFiscalConfig? = null
    ): FiscalSummary {
        val excluded = linkedMapOf<VatExclusion, Int>()

        fun note(reason: VatExclusion) {
            excluded[reason] = (excluded[reason] ?: 0) + 1
        }

        val outputDocs = mutableListOf<DocComponents>()
        val inputDocs = mutableListOf<DocComponents>()

        // Ingresos: primero los registrados como Income, luego las facturas emitidas.
        incomes.filter { inPeriod(it.fecha, period) }.forEach { income ->
            if (income.moneda.uppercase() != currency) { note(VatExclusion.FOREIGN_CURRENCY); return@forEach }
            val components = componentsOf(income.taxes, income.ivaPercent)
            if (components.taxable.isEmpty()) { note(exclusionFor(components)); return@forEach }
            outputDocs += components
        }

        invoices.filter { inPeriod(it.fecha, period) }.forEach { invoice ->
            if (invoice.moneda.uppercase() != currency) { note(VatExclusion.FOREIGN_CURRENCY); return@forEach }
            val fromTaxes = componentsOf(invoice.taxes, null)
            val components = if (fromTaxes.taxable.isNotEmpty()) fromTaxes
                             else manualComponents(invoice.baseImponible, invoice.cuotaIva, invoice.ivaPercent)
            if (components.taxable.isEmpty()) { note(exclusionFor(components)); return@forEach }
            if (invoice.tipo == InvoiceType.INGRESO) outputDocs += components else inputDocs += components
        }

        val output = blockOf(outputDocs)
        val input = blockOf(inputDocs)
        return FiscalSummary(
            period = period,
            countryCode = fiscalConfig?.paisCodigo.orEmpty(),
            taxName = fiscalConfig?.nombreLeyFiscal?.takeIf { it.isNotBlank() } ?: "IVA",
            currency = currency,
            output = output,
            input = input,
            result = output.quota.subtract(input.quota).setScale(2, RoundingMode.HALF_UP),
            irpfWithheld = outputDocs.fold(ZERO_MONEY) { acc, doc -> acc.add(doc.withholding) }.setScale(2, RoundingMode.HALF_UP),
            irpfPaid = inputDocs.fold(ZERO_MONEY) { acc, doc -> acc.add(doc.withholding) }.setScale(2, RoundingMode.HALF_UP),
            excluded = excluded
        )
    }

    private fun inPeriod(timestamp: Long, period: VatPeriod): Boolean =
        timestamp >= period.startInclusive && timestamp < period.endExclusive

    private fun exclusionFor(components: DocComponents): VatExclusion = when {
        components.hasAmountWithoutRate -> VatExclusion.MISSING_RATE
        components.hasExempt -> VatExclusion.EXEMPT
        else -> VatExclusion.MISSING_TAX_DATA
    }

    private fun blockOf(docs: List<DocComponents>): VatBlock {
        if (docs.isEmpty()) return VatBlock()
        // El contador de cada línea cuenta DOCUMENTOS que llevan ese tipo, no componentes:
        // una factura con 21 % y 4 % aparece en las dos líneas, pero no se cuenta dos veces dentro
        // de la misma línea.
        val byRate = linkedMapOf<BigDecimal, MutableList<Pair<BigDecimal, BigDecimal>>>()
        val docsPerRate = linkedMapOf<BigDecimal, MutableSet<Int>>()
        docs.forEachIndexed { index, doc ->
            doc.taxable.forEach { component ->
                val rate = component.rate
                byRate.getOrPut(rate) { mutableListOf() } += component.base to component.quota
                docsPerRate.getOrPut(rate) { mutableSetOf() } += index
            }
        }
        val lines = byRate.entries
            .sortedByDescending { it.key }
            .map { (rate, values) ->
                VatRateLine(
                    rate = rate,
                    base = values.fold(ZERO_MONEY) { acc, pair -> acc.add(pair.first) }.setScale(2, RoundingMode.HALF_UP),
                    quota = values.fold(ZERO_MONEY) { acc, pair -> acc.add(pair.second) }.setScale(2, RoundingMode.HALF_UP),
                    documents = docsPerRate[rate]?.size ?: 0
                )
            }
        return VatBlock(
            lines = lines,
            base = lines.fold(ZERO_MONEY) { acc, line -> acc.add(line.base) }.setScale(2, RoundingMode.HALF_UP),
            quota = lines.fold(ZERO_MONEY) { acc, line -> acc.add(line.quota) }.setScale(2, RoundingMode.HALF_UP),
            documents = docs.size
        )
    }

    internal data class Component(val rate: BigDecimal, val base: BigDecimal, val quota: BigDecimal)

    internal data class DocComponents(
        val taxable: List<Component>,
        val withholding: BigDecimal,
        val hasAmountWithoutRate: Boolean,
        val hasExempt: Boolean = false
    )

    /**
     * Extrae los componentes utilizables. Un componente cuenta si es un cargo (no retención) y
     * no está declarado exento ni fuera del ámbito. Los tipos a 0 % sí cuentan como línea propia.
     */
    internal fun componentsOf(
        taxes: List<DocumentTax>,
        fallbackRate: Double?,
        fallbackBase: Double? = null,
        fallbackQuota: Double? = null
    ): DocComponents {
        var hasAmountWithoutRate = false
        var hasExempt = false
        val taxable = mutableListOf<Component>()
        var withholding = BigDecimal.ZERO

        taxes.forEach { tax ->
            val treatment = tax.treatment
            val exempt = treatment == TaxTreatment.EXEMPT || treatment == TaxTreatment.OUT_OF_SCOPE
            if (exempt) hasExempt = true
            if (tax.effect == TaxEffect.WITHHOLDING && !exempt) {
                tax.amount?.let { withholding = withholding.add(money(it)) }
                return@forEach
            }
            if (tax.effect != TaxEffect.CHARGE || exempt) return@forEach
            val rate = tax.rate
            val amount = tax.amount
            if (rate == null) {
                if (amount != null && amount != 0.0) hasAmountWithoutRate = true
                return@forEach
            }
            val quota = amount ?: 0.0
            val base = tax.base ?: if (rate > 0.0) quota * 100.0 / rate else 0.0
            taxable += Component(moneyOrRate(rate), money(base), money(quota))
        }

        if (taxable.isEmpty() && fallbackRate != null && fallbackQuota != null) {
            val quota = fallbackQuota
            val base = fallbackBase ?: if (fallbackRate > 0.0) quota * 100.0 / fallbackRate else 0.0
            taxable += Component(moneyOrRate(fallbackRate), money(base), money(quota))
        } else if (taxable.isEmpty() && fallbackQuota != null && fallbackRate == null) {
            hasAmountWithoutRate = true
        }

        return DocComponents(taxable, withholding, hasAmountWithoutRate, hasExempt)
    }

    /**
     * Desglose declarado a mano en la factura (base y cuota escritas por el usuario).
     *
     * Si hay base y cuota, el tipo se DEDUCE de ellas: el campo `ivaPercent` de la factura trae 21
     * por defecto, así que fiarse de él cuando la cuota está a 0 atribuiría un IVA que nadie pagó.
     * Gana la medición: cuota / base.
     */
    internal fun manualComponents(baseImponible: Double?, cuotaIva: Double?, ivaPercent: Double?): DocComponents {
        if (cuotaIva == null) {
            val hasOnlyBase = baseImponible != null && baseImponible != 0.0
            return DocComponents(emptyList(), BigDecimal.ZERO, hasAmountWithoutRate = hasOnlyBase)
        }
        if (cuotaIva == 0.0 && (baseImponible == null || baseImponible == 0.0)) {
            // Sin base y sin cuota no hay nada que declarar; con cuota 0 y base 0 tampoco.
            return DocComponents(emptyList(), BigDecimal.ZERO, hasAmountWithoutRate = false, hasExempt = true)
        }
        val base = baseImponible ?: 0.0
        val rate = if (base != 0.0) cuotaIva * 100.0 / base else (ivaPercent ?: 0.0)
        val component = Component(moneyOrRate(rate), money(base), money(cuotaIva))
        return DocComponents(listOf(component), BigDecimal.ZERO, hasAmountWithoutRate = false)
    }

    internal fun money(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)

    internal fun moneyOrRate(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
}
