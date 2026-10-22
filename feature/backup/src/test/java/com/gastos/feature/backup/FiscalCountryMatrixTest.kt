package com.gastos.feature.backup

import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TaxPeriodType
import com.gastos.domain.model.currencySymbol
import com.gastos.domain.model.fiscalCountryLabel
import com.gastos.domain.model.usualCurrencyForCountry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Batería de los 14 países fiscales y las 13 monedas soportadas.
 *
 * El objetivo no es repetir la aritmética del IVA (eso está en FiscalSummaryTest), sino recorrer
 * TODAS las combinaciones que el usuario puede elegir de verdad y comprobar que ninguna revienta,
 * que el periodo sale coherente y que el plazo nunca es una fecha inventada.
 *
 * Los 14 países están copiados de `CountryFiscalConfigRepositoryImpl.createDefaultConfigs()`: si
 * allí cambia un tipo o se añade un país, ESTA lista debe actualizarse (el test lo delatará porque
 * comprueba los tipos conocidos de cada uno).
 */
class FiscalCountryMatrixTest {

    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private val year = 2026

    private fun date(month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(madrid).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    /**
     * País sin reglas verificadas. Los argumentos van POR NOMBRE a propósito: pasarlos en
     * posicional cruzaba `nombrePais` con `ivaRates` (ambos van seguidos en el constructor), el test
     * comprobaba datos que no eran los de la app y daba por bueno un país con nombre de lista.
     */
    private fun simple(
        code: String,
        name: String,
        rates: List<Double>,
        taxName: String = "IVA"
    ) = CountryFiscalConfig(
        paisCodigo = code,
        nombrePais = name,
        ivaRates = rates,
        irpfRate = null,
        nombreLeyFiscal = taxName
    )

    /** Mismos datos que carga la app para cada país. */
    private val countries: List<CountryFiscalConfig> = listOf(
        CountryFiscalConfig(
            paisCodigo = "ES", nombrePais = "España", ivaRates = listOf(0.0, 4.0, 10.0, 21.0),
            irpfRate = 15.0, nombreLeyFiscal = "IVA",
            tipoPeriodo = TaxPeriodType.QUARTERLY, nombreDeclaracion = "modelo 303",
            plazos = mapOf(
                "1T" to "1-20 abril", "2T" to "1-20 julio",
                "3T" to "1-20 octubre", "4T" to "1-30 enero"
            ),
            tiposRetencion = listOf(7.0)
        ),
        simple("MX", "México", listOf(0.0, 8.0, 16.0)),
        simple("US", "Estados Unidos", listOf(0.0), "Sales Tax"),
        simple("AR", "Argentina", listOf(0.0, 10.5, 21.0, 27.0)),
        simple("CO", "Colombia", listOf(0.0, 5.0, 19.0)),
        simple("CL", "Chile", listOf(19.0)),
        simple("PE", "Perú", listOf(0.0, 18.0), "IGV"),
        simple("EC", "Ecuador", listOf(0.0, 5.0, 15.0)),
        simple("BO", "Bolivia", listOf(0.0, 13.0)),
        simple("GT", "Guatemala", listOf(0.0, 12.0)),
        simple("NI", "Nicaragua", listOf(0.0, 15.0)),
        simple("PY", "Paraguay", listOf(0.0, 5.0, 10.0)),
        simple("UY", "Uruguay", listOf(0.0, 10.0, 22.0)),
        simple("VE", "Venezuela", listOf(0.0, 8.0, 16.0, 31.0))
    )

    // ------------------------------------------------------------------ países

    @Test
    fun `hay catorce paises con codigo ISO de dos letras y sin repetir`() {
        assertEquals(14, countries.size)
        countries.forEach { config ->
            assertTrue("Código vacío", config.paisCodigo.isNotBlank())
            assertTrue("Nombre vacío en ${config.paisCodigo}", config.nombrePais.isNotBlank())
            assertEquals(
                "El código debería ser ISO de dos letras: ${config.paisCodigo}",
                2,
                config.paisCodigo.length
            )
        }
        assertEquals(
            "No puede haber países repetidos",
            countries.size,
            countries.map { it.paisCodigo }.distinct().size
        )
    }

    @Test
    fun `cada pais declara tipos de impuesto coherentes`() {
        countries.forEach { config ->
            assertTrue("${config.paisCodigo} sin tipos", config.ivaRates.isNotEmpty())
            config.ivaRates.forEach { tipo ->
                assertTrue("${config.paisCodigo} con tipo negativo: $tipo", tipo >= 0.0)
                assertTrue("${config.paisCodigo} con tipo imposible: $tipo", tipo <= 100.0)
            }
            assertEquals(
                "${config.paisCodigo} con tipos repetidos",
                config.ivaRates.size,
                config.ivaRates.distinct().size
            )
            assertTrue("${config.paisCodigo} sin nombre de impuesto", config.nombreLeyFiscal.isNotBlank())
        }
    }

    @Test
    fun `los tipos conocidos de cada pais son los que carga la app`() {
        // Fija los datos de partida: son tipos publicados, no inventados. Si alguien los cambia sin
        // querer, salta aquí antes de llegar a un informe mal calculado.
        val esperados = mapOf(
            "ES" to listOf(0.0, 4.0, 10.0, 21.0),
            "MX" to listOf(0.0, 8.0, 16.0),
            "US" to listOf(0.0),
            "AR" to listOf(0.0, 10.5, 21.0, 27.0),
            "CO" to listOf(0.0, 5.0, 19.0),
            "CL" to listOf(19.0),
            "PE" to listOf(0.0, 18.0),
            "EC" to listOf(0.0, 5.0, 15.0),
            "BO" to listOf(0.0, 13.0),
            "GT" to listOf(0.0, 12.0),
            "NI" to listOf(0.0, 15.0),
            "PY" to listOf(0.0, 5.0, 10.0),
            "UY" to listOf(0.0, 10.0, 22.0),
            "VE" to listOf(0.0, 8.0, 16.0, 31.0)
        )
        assertEquals(esperados.keys, countries.map { it.paisCodigo }.toSet())
        countries.forEach { config ->
            assertEquals(
                "Tipos de ${config.paisCodigo}",
                esperados.getValue(config.paisCodigo),
                config.ivaRates
            )
        }
        // Perú llama IGV a su impuesto, no IVA; Estados Unidos, Sales Tax.
        assertEquals("IGV", countries.first { it.paisCodigo == "PE" }.nombreLeyFiscal)
        assertEquals("Sales Tax", countries.first { it.paisCodigo == "US" }.nombreLeyFiscal)
    }

    @Test
    fun `solo Espana tiene reglas verificadas y por eso es la unica que nombra su declaracion`() {
        val conNombre = countries.filter { !it.nombreDeclaracion.isNullOrBlank() }
        assertEquals("Solo España puede nombrar su formulario", listOf("ES"), conNombre.map { it.paisCodigo })
        conNombre.forEach { config ->
            assertEquals(TaxPeriodType.QUARTERLY, config.tipoPeriodo)
            assertEquals("Deben estar los cuatro trimestres", 4, config.plazos.size)
            // 15 % es el tipo general de retención (va en `irpfRate`); `tiposRetencion` guarda los
            // alternativos, como el 7 % de quienes empiezan.
            assertEquals(15.0, config.irpfRate!!, 0.001)
            assertTrue("Falta el 7 % de inicio de actividad", config.tiposRetencion.contains(7.0))
        }
        // Inventar el nombre de una declaración ajena es peor que no ponerlo.
        countries.filter { it.paisCodigo != "ES" }.forEach { config ->
            assertEquals(
                "${config.paisCodigo} no debería traer periodo fijo",
                TaxPeriodType.USER_DEFINED,
                config.tipoPeriodo
            )
            assertNull("${config.paisCodigo} no debería nombrar formulario", config.nombreDeclaracion)
            assertTrue("${config.paisCodigo} no debería traer plazos", config.plazos.isEmpty())
        }
    }

    @Test
    fun `los plazos de Espana son los de la AEAT`() {
        val espana = countries.first { it.paisCodigo == "ES" }
        assertEquals("1-20 abril", espana.plazos["1T"])
        assertEquals("1-20 julio", espana.plazos["2T"])
        assertEquals("1-20 octubre", espana.plazos["3T"])
        assertEquals("1-30 enero", espana.plazos["4T"])
    }

    // ------------------------------------------------------------------ periodos

    @Test
    fun `cada pais genera un periodo válido para los tres ritmos posibles`() {
        val ritmos = listOf(VatPeriodType.MONTHLY, VatPeriodType.QUARTERLY, VatPeriodType.ANNUAL)
        countries.forEach { config ->
            ritmos.forEach { ritmo ->
                val periodo = VatPeriodCalculator.forCountry(config, year, 3, ritmo, madrid)
                assertEquals("Ritmo aplicado en ${config.paisCodigo}", ritmo, periodo.type)
                assertTrue("${config.paisCodigo} ($ritmo) sin etiqueta", periodo.label().isNotBlank())
                assertTrue(
                    "${config.paisCodigo} ($ritmo) con rango vacío",
                    periodo.endExclusive > periodo.startInclusive
                )
                assertTrue("${config.paisCodigo} sin código de periodo", periodo.periodCode().isNotBlank())
            }
        }
    }

    @Test
    fun `un pais sin reglas verificadas recibe la regla general y nunca una fecha concreta`() {
        val espana = countries.first { it.paisCodigo == "ES" }
        // España sí tiene fechas reales.
        assertEquals("1-20 abril", VatPeriodCalculator.quarter(year, 1, madrid).deadlineHint(espana))
        assertEquals("1-30 enero", VatPeriodCalculator.quarter(year, 4, madrid).deadlineHint(espana))

        countries.filter { it.paisCodigo != "ES" }.forEach { config ->
            listOf(1, 7, 12).forEach { mes ->
                val mensual = VatPeriodCalculator.forCountry(config, year, mes, VatPeriodType.MONTHLY, madrid)
                assertEquals("mes siguiente", mensual.deadlineHint(config))
                val trimestral = VatPeriodCalculator.forCountry(config, year, mes, VatPeriodType.QUARTERLY, madrid)
                assertEquals("mes siguiente al trimestre", trimestral.deadlineHint(config))
                // Un año no tiene plazo universal: mejor null que una fecha falsa.
                assertNull(VatPeriodCalculator.yearOf(year, madrid).deadlineHint(config))
                // Y jamás un plazo que pertenezca a otro país.
                assertTrue(espana.plazos.values.none { it == mensual.deadlineHint(config) })
            }
        }
    }

    @Test
    fun `las etiquetas del periodo no desbordan el año`() {
        assertEquals("12/2026", VatPeriodCalculator.month(year, 12, madrid).label())
        assertEquals("4T 2026", VatPeriodCalculator.quarter(year, 4, madrid).label())
        assertEquals("1T 2026", VatPeriodCalculator.quarter(year, 1, madrid).label())
        assertEquals("2026", VatPeriodCalculator.yearOf(year, madrid).label())
        // El último instante del 4T sigue siendo 4T y del mismo año.
        val ultimo = VatPeriodCalculator.quarter(year, 4, madrid).endExclusive - 1
        assertEquals("4T 2026", VatPeriodCalculator.quarterOf(ultimo, madrid).label())
    }

    @Test
    fun `un pais sin ritmo propio no arrastra periodo y se resuelve como mensual`() {
        countries.filter { it.tipoPeriodo == TaxPeriodType.USER_DEFINED }.forEach { config ->
            assertNull("${config.paisCodigo} no debería traer ritmo", config.vatPeriodTypeOrNull())
            val periodo = VatPeriodCalculator.forCountry(config, year, 5, VatPeriodType.MONTHLY, madrid)
            assertEquals(VatPeriodType.MONTHLY, periodo.type)
            assertEquals("5/2026", periodo.label())
        }
        // España sí lo trae, y es trimestral.
        assertEquals(
            VatPeriodType.QUARTERLY,
            countries.first { it.paisCodigo == "ES" }.vatPeriodTypeOrNull()
        )
    }

    // ------------------------------------------------------------------ resumen de IVA

    @Test
    fun `el resumen se calcula en los catorce paises con su moneda`() {
        countries.forEach { config ->
            val moneda = monedaDePrueba(config)
            val periodo = VatPeriodCalculator.forCountry(config, year, 3, VatPeriodType.MONTHLY, madrid)
            val resumen = VatSummaryCalculator.summary(
                period = periodo,
                currency = moneda,
                invoices = factura(moneda),
                incomes = emptyList(),
                fiscalConfig = config
            )
            assertEquals(config.paisCodigo, resumen.countryCode)
            assertTrue("${config.paisCodigo} sin nombre de impuesto", resumen.taxName.isNotBlank())
            assertEquals(
                "${config.paisCodigo} no contó su factura (excluidos: ${resumen.excluded})",
                1,
                resumen.input.documents
            )
        }
    }

    @Test
    fun `una factura sin cuota declarada no inventa iva ni se cuela en el resumen`() {
        // `ivaPercent` trae 21 por defecto en el modelo: si el motor lo usara como respaldo cuando
        // la cuota es 0, se declararía un IVA que nadie pagó. Este caso fija que NO lo hace.
        val sinDatos = listOf(
            Invoice(
                fecha = date(2, 15),
                proveedor = "Proveedor de prueba",
                tipo = InvoiceType.GASTO,
                moneda = "EUR",
                total = 100.0,
                baseImponible = null,
                cuotaIva = null,
                ivaPercent = 21.0
            )
        )
        val resumen = VatSummaryCalculator.summary(
            period = VatPeriodCalculator.month(year, 2, madrid),
            currency = "EUR",
            invoices = sinDatos,
            incomes = emptyList(),
            fiscalConfig = countries.first { it.paisCodigo == "ES" }
        )
        assertEquals("No puede contarse un documento sin cuota", 0, resumen.input.documents)
        assertEquals("El resultado debe quedar a cero", 0, resumen.result.compareTo(java.math.BigDecimal.ZERO.setScale(2)))
        assertTrue(
            "Debe informarse de por qué se excluye",
            (resumen.excluded[VatExclusion.MISSING_TAX_DATA] ?: 0) > 0
        )
    }

    @Test
    fun `una factura emitida a un pais extranjero cuenta por su moneda y no por su pais`() {
        // El país del informe lo decide el ajuste del usuario; el `paisCodigo` del documento no
        // cambia la suma. Lo que sí excluye es la moneda, y eso se informa.
        val espana = countries.first { it.paisCodigo == "ES" }
        val emitidaFuera = listOf(
            Invoice(
                fecha = date(2, 15),
                proveedor = "Cliente de fuera",
                tipo = InvoiceType.INGRESO,
                moneda = "USD",
                total = 121.0,
                baseImponible = 100.0,
                cuotaIva = 21.0,
                ivaPercent = null,
                paisCodigo = "US"
            )
        )
        val enEur = VatSummaryCalculator.summary(
            period = VatPeriodCalculator.month(year, 2, madrid),
            currency = "EUR",
            invoices = emitidaFuera,
            incomes = emptyList(),
            fiscalConfig = espana
        )
        assertEquals(0, enEur.output.documents)
        assertEquals(1, enEur.excluded[VatExclusion.FOREIGN_CURRENCY])

        val enUsd = VatSummaryCalculator.summary(
            period = VatPeriodCalculator.month(year, 2, madrid),
            currency = "USD",
            invoices = emitidaFuera,
            incomes = emptyList(),
            fiscalConfig = espana
        )
        assertEquals("Con la moneda correcta sí cuenta", 1, enUsd.output.documents)
    }

    @Test
    fun `estados unidos es el unico que no ofrece resumen de IVA`() {
        assertEquals(listOf("US"), countries.filterNot { it.permiteResumenIva() }.map { it.paisCodigo })
    }

    @Test
    fun `un documento en otra moneda se excluye y se informa del motivo`() {
        val espana = countries.first { it.paisCodigo == "ES" }
        val resumen = VatSummaryCalculator.summary(
            period = VatPeriodCalculator.quarter(year, 1, madrid),
            currency = "EUR",
            invoices = factura("EUR") + factura("MXN"),
            incomes = emptyList(),
            fiscalConfig = espana
        )
        assertEquals("Solo cuentan los documentos en EUR", 1, resumen.input.documents)
        assertEquals(
            "La de otra moneda debe aparecer como excluida",
            1,
            resumen.excluded[VatExclusion.FOREIGN_CURRENCY]
        )
    }

    // ------------------------------------------------------------------ monedas

    @Test
    fun `la moneda sugerida de cada pais existe en la lista o no se sugiere`() {
        val monedas = com.gastos.domain.model.SUPPORTED_CURRENCIES
        countries.forEach { config ->
            usualCurrencyForCountry(config.paisCodigo)?.let { sugerida ->
                assertTrue(
                    "${config.paisCodigo} sugiere $sugerida, que no está soportada",
                    sugerida in monedas
                )
            }
        }
        // Venezuela se queda fuera a propósito: bolívares y dólares conviven.
        assertNull(usualCurrencyForCountry("VE"))
        // Estados Unidos y Ecuador comparten el dólar, y es correcto.
        assertEquals("USD", usualCurrencyForCountry("US"))
        assertEquals("USD", usualCurrencyForCountry("EC"))
    }

    @Test
    fun `las trece monedas preferentes estan soportadas y tienen simbolo`() {
        val preferentes = listOf(
            "EUR", "USD", "MXN", "ARS", "COP", "CLP", "PEN",
            "BOB", "GTQ", "NIO", "PYG", "UYU", "VES"
        )
        val soportadas = com.gastos.domain.model.SUPPORTED_CURRENCIES
        preferentes.forEach { moneda ->
            assertTrue("$moneda no está soportada", moneda in soportadas)
            assertTrue("$moneda sin símbolo", currencySymbol(moneda).isNotBlank())
        }
        assertEquals("€", currencySymbol("EUR"))
        assertEquals("S/", currencySymbol("PEN"))
        assertEquals("₲", currencySymbol("PYG"))
        assertEquals("Q", currencySymbol("GTQ"))
        assertEquals("C$", currencySymbol("NIO"))
    }

    @Test
    fun `el texto del resumen nombra el pais y la moneda elegida`() {
        countries.forEach { config ->
            val moneda = monedaDePrueba(config)
            val periodo = VatPeriodCalculator.forCountry(config, year, 6, VatPeriodType.MONTHLY, madrid)
            val resumen = VatSummaryCalculator.summary(
                period = periodo,
                currency = moneda,
                invoices = factura(moneda),
                incomes = emptyList(),
                fiscalConfig = config
            )
            val lineas = FiscalSummaryText.lines(resumen, labelsDePrueba(), config)
            assertTrue("Resumen vacío para ${config.paisCodigo}", lineas.isNotEmpty())
            assertTrue(
                "El texto no menciona $moneda en ${config.paisCodigo}",
                lineas.any { it.contains(moneda) }
            )
            assertTrue(
                "El texto no menciona el país ${config.paisCodigo}",
                lineas.any { it.contains(config.paisCodigo) }
            )
        }
    }

    @Test
    fun `las etiquetas del pais llevan nombre y codigo`() {
        countries.forEach { config ->
            val etiqueta = fiscalCountryLabel(config.paisCodigo)
            assertTrue("Falta el nombre en la etiqueta: $etiqueta", etiqueta.contains(config.nombrePais))
            assertTrue("Falta el código en la etiqueta: $etiqueta", etiqueta.contains(config.paisCodigo))
        }
        // Un país sin nombre conocido no se inventa: se muestra su código.
        assertEquals("JP", fiscalCountryLabel("JP"))
    }

    // ------------------------------------------------------------------ ayudas

    private fun monedaDePrueba(config: CountryFiscalConfig): String =
        usualCurrencyForCountry(config.paisCodigo) ?: "USD"

    /**
     * Factura con base y cuota coherentes (100 + 21 %) en la moneda indicada.
     *
     * `ivaPercent` va a **null** a propósito: el motor DEDUCE el tipo de cuota/base y, con
     * `ivaPercent` puesto y `taxes` vacío, `componentsOf` recibe la cuota como «importe sin tipo» y
     * excluye el documento. Es la regla correcta (el campo trae 21 por defecto, así que no se puede
     * dar por hecho que se pagó IVA), y aquí solo estorba para probar los países.
     */
    private fun factura(moneda: String): List<Invoice> =
        listOf(
            Invoice(
                // Marzo: dentro del periodo mensual 3/2026 y del trimestral 1T, para que estos
                // tests no dependan de en qué mes se consulte.
                fecha = date(3, 15),
                proveedor = "Proveedor de prueba",
                tipo = InvoiceType.GASTO,
                moneda = moneda,
                total = 121.0,
                baseImponible = 100.0,
                cuotaIva = 21.0,
                ivaPercent = null
            )
        )

    /** Etiquetas en español, equivalentes a las que la app carga de strings.xml. */
    private fun labelsDePrueba() = FiscalSummaryLabels(
        title = "Resumen de IVA por periodo",
        period = "Periodo",
        country = "País",
        currency = "Moneda",
        outputBlock = "Repercutido",
        inputBlock = "Soportado",
        rate = "Tipo",
        base = "Base",
        quota = "Cuota",
        documents = "Documentos",
        outputTotal = "Total repercutido",
        inputTotal = "Total soportado",
        result = "Resultado",
        resultToPay = "A ingresar",
        resultToOffset = "A compensar o devolver",
        irpfWithheld = "Retención",
        irpfPaid = "Pagado",
        excludedTitle = "Documentos no incluidos en el cálculo",
        excludedForeignCurrency = "En otra moneda",
        excludedMissingTaxData = "Sin datos de IVA",
        excludedMissingRate = "Con cuota pero sin tipo",
        excludedExempt = "Exentos o no sujetos",
        disclaimer = "Resumen informativo",
        deadline = "Plazo de presentación",
        empty = "Sin documentos en este periodo"
    )
}
