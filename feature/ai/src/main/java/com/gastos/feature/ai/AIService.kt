package com.gastos.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.CountryFiscalConfigRepository
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.content
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AIResult(
    val success: Boolean,
    val message: String,
    val invoice: Invoice? = null,
    val income: Income? = null,
    val products: List<Product> = emptyList(),
    val queryResult: String? = null
)

@Singleton
class AIService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fiscalConfigRepository: CountryFiscalConfigRepository
) {
    private var generativeModel: GenerativeModel? = null
    private var chatSession: Chat? = null
    private var currentApiKey: String = ""
    private var systemInstructions: String = ""

    /** País fiscal usado al escanear documentos (por defecto España).
     *  La UI puede cambiarlo con [setFiscalCountry]. Esto aplica las
     *  reglas de extracción fiscales del país (sólo España tiene reglas
     *  AEAT específicas hoy; resto del mundo usa un prompt genérico). */
    @Volatile
    private var currentFiscalCountry: String = "ES"
    @Volatile
    private var cachedFiscalConfig: CountryFiscalConfig? = null
    @Volatile
    private var cachedFiscalCountryForConfig: String? = null

    /** Cambia el país fiscal de referencia para el escaneo de documentos.
     *  Recarga la [CountryFiscalConfig] correspondiente en caché. */
    suspend fun setFiscalCountry(countryCode: String) {
        val code = countryCode.uppercase()
        if (code == currentFiscalCountry && cachedFiscalConfig != null) return
        currentFiscalCountry = code
        cachedFiscalConfig = fiscalConfigRepository.getConfigByCountry(code)
        cachedFiscalCountryForConfig = code
    }

    /** Devuelve la config fiscal del país actual, cargándola en caché si es
     *  necesario. Llamado desde suspend (processInvoiceFromImage). */
    private suspend fun currentFiscalConfig(): CountryFiscalConfig? {
        val code = currentFiscalCountry
        if (cachedFiscalCountryForConfig != code) {
            cachedFiscalConfig = fiscalConfigRepository.getConfigByCountry(code)
            cachedFiscalCountryForConfig = code
        }
        return cachedFiscalConfig
    }

    /** Número máximo de turnos (usuario+modelo) que se conservan en la memoria. */
    private val chatHistory = mutableListOf<com.google.ai.client.generativeai.type.Content>()
    private var maxHistoryTurns = FREE_MAX_HISTORY_TURNS

    /**
     * Ajusta los límites de IA según el estado Premium.
     * - Premium: memoria conversacional de hasta [PREMIUM_MAX_HISTORY_TURNS] turnos.
     * - Gratuito: memoria limitada a [FREE_MAX_HISTORY_TURNS] turnos.
     */
    fun setPremiumLimits(isPremium: Boolean) {
        val newMax = if (isPremium) PREMIUM_MAX_HISTORY_TURNS else FREE_MAX_HISTORY_TURNS
        if (newMax != maxHistoryTurns) {
            maxHistoryTurns = newMax
            trimHistory()
            rebuildSession()
        }
    }

    /**
     * Configura el modelo de Gemini 3.5 Flash con la API key del usuario y las
     * instrucciones del sistema (prompt base + personalizadas). Reinicia la
     * sesión de chat para aplicar las nuevas instrucciones.
     */
    fun configureGemini(apiKey: String, systemInstructions: String) {
        currentApiKey = apiKey
        this.systemInstructions = systemInstructions

        if (apiKey.isBlank()) {
            generativeModel = null
            resetChat()
            return
        }

        val sysContent = content { text(buildSystemPrompt(systemInstructions)) }
        generativeModel = GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = apiKey,
            systemInstruction = sysContent
        )
        // Conserva la historia existente salvo que las instrucciones cambien
        // sustancialmente; aquí simplemente reconstruimos la sesión con la
        // historia truncada acumulada.
        rebuildSession()
    }

    /** Reinicia la memoria conversacional (historial) y la sesión de chat. */
    fun resetChat() {
        chatHistory.clear()
        rebuildSession()
    }

    private fun rebuildSession() {
        chatSession = generativeModel?.startChat(history = chatHistory.toList())
    }

    /**
     * Trunca el historial cuando supera el máximo de turnos, conservando los
     * mensajes más recientes. Se llama tras cada intercambio.
     */
    private fun trimHistory() {
        while (chatHistory.size > maxHistoryTurns * 2) {
            chatHistory.removeAt(0)
        }
    }

    /** Indica si hay una API key válida configurada. */
    fun isConfigured(): Boolean = currentApiKey.isNotBlank() && generativeModel != null

    /**
     * Valida una API key haciendo una petición mínima. Se usa al guardarla desde
     * Ajustes para dar feedback inmediato al usuario.
     */
    suspend fun validateApiKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("API key vacía"))
        try {
            val testModel = GenerativeModel(MODEL_NAME, apiKey)
            testModel.generateContent("ping")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Procesamiento de comandos (chat / voz)
    // ------------------------------------------------------------------

    /**
     * Procesa un comando usando la sesión de chat con memoria conversacional.
     * Devuelve un AIResult ya parseado.
     */
    suspend fun processCommand(command: String): AIResult {
        val chat = chatSession ?: return notConfiguredResult()
        return try {
            val response = chat.sendMessage(command)
            val responseText = response.text ?: ""
            recordTurn(command, responseText)
            parseCommandResponse(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error en processCommand", e)
            AIResult(success = false, message = friendlyError(e))
        }
    }

    /**
     * Versión en streaming de processCommand. Emite los fragmentos de texto a
     * medida que el modelo los genera, para que la UI los muestre en vivo.
     * El llamador debe parsear el texto final con [parseCommandResponse].
     */
    fun processCommandStreaming(command: String): Flow<String> {
        val chat = chatSession
        val userMsg = command
        return flow {
            if (chat == null) {
                throw IllegalStateException(NO_API_KEY_MESSAGE)
            }
            val collected = StringBuilder()
            chat.sendMessageStream(userMsg).collect { resp: GenerateContentResponse ->
                resp.text?.takeIf { it.isNotEmpty() }?.let {
                    collected.append(it)
                    emit(it)
                }
            }
            // Registramos el turno completo tras terminar el stream.
            recordTurn(userMsg, collected.toString())
        }.catch { e ->
            Log.e(TAG, "Error en streaming", e)
            throw e
        }
    }

    /** Registra un turno (usuario + respuesta) en la memoria conversacional. */
    private fun recordTurn(user: String, model: String) {
        if (user.isNotBlank()) {
            chatHistory.add(content(role = "user") { text(user) })
        }
        if (model.isNotBlank()) {
            chatHistory.add(content(role = "model") { text(model) })
        }
        trimHistory()
    }

    /** Convierte el texto crudo del modelo (recogido del stream) en un AIResult. */
    fun parseStreamingResult(responseText: String): AIResult = parseCommandResponse(responseText)

    // ------------------------------------------------------------------
    // OCR de facturas (multimodal)
    // ------------------------------------------------------------------

    suspend fun processInvoiceFromImage(imageUri: Uri): AIResult {
        val model = generativeModel ?: return notConfiguredResult()
        return try {
            val bitmap = uriToBitmap(imageUri)
                ?: return AIResult(success = false, message = "Error al cargar la imagen")

            val fiscalCfg = currentFiscalConfig()
            val prompt = ocrPrompt(currentFiscalCountry, fiscalCfg)

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )
            val raw = response.text ?: ""
            Log.d(TAG, "OCR raw response: $raw")
            parseInvoiceResponse(raw, imageUri.toString(), currentFiscalCountry)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando imagen", e)
            AIResult(success = false, message = friendlyError(e))
        }
    }

    // ------------------------------------------------------------------
    // Consultas de datos
    // ------------------------------------------------------------------

    suspend fun queryData(query: String): AIResult {
        val model = generativeModel ?: return notConfiguredResult()
        return try {
            val response = model.generateContent(queryExtractionPrompt(query))
            AIResult(success = true, message = "Consulta procesada", queryResult = response.text ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Error en queryData", e)
            AIResult(success = false, message = friendlyError(e))
        }
    }

    // ------------------------------------------------------------------
    // Helpers de prompts y errores
    // ------------------------------------------------------------------

    private fun notConfiguredResult(): AIResult = AIResult(
        success = false,
        message = NO_API_KEY_MESSAGE
    )

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("API key", ignoreCase = true) ||
                    msg.contains("api_key", ignoreCase = true) ||
                    msg.contains("permission", ignoreCase = true) ->
                "Tu API key de Gemini no es válida. Revísala en $SETTINGS_PATH."
            msg.contains("quota", ignoreCase = true) ||
                    msg.contains("rate limit", ignoreCase = true) ||
                    msg.contains("429", ignoreCase = true) ->
                "Se ha alcanzado el límite de uso de la API gratuita de Gemini. Inténtalo de nuevo más tarde."
            else -> "Error al contactar con Gemini: ${e.message}"
        }
    }

    private fun buildSystemPrompt(userInstructions: String): String {
        val today = java.time.LocalDate.now().toString()
        val extra = userInstructions.trim()
        val extraBlock = if (extra.isNotEmpty()) {
            "\n\nInstrucciones adicionales del usuario (sigue también estas reglas):\n$extra"
        } else ""
        return """
            Eres FinAI, un asistente financiero personal inteligente, cercano y conversacional.
            Respondes siempre en español, con tono amable y profesional. Hoy es $today.

            Tu trabajo es analizar el mensaje del usuario y decidir qué acción realizar.
            Devuelves SIEMPRE Y SOLO un objeto JSON válido, sin markdown ni texto adicional.

            Reglas de acción:
            1. CONSULTA FINANCIERA: si pregunta cuánto gastó, sus ingresos, balance, totales,
               productos comprados, etc.:
               {"action":"query","query_type":"gastos|ingresos|balance|productos","periodo":"hoy|semana|mes|año","categoria":null,"item":null}

            2. REGISTRAR GASTO: si dice que gastó, compró o pagó algo:
               {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0.0,"total":0.0,"moneda":"EUR","fecha":"$today","categoria":"texto"}

            3. REGISTRAR INGRESO: si menciona nómina, salario, cobro o ingreso recibido:
               {"action":"add_income","concepto":"texto","total_devengado":0.0,"total_neto":0.0,"monto":0.0,"moneda":"EUR","fecha":"$today","fuente":"texto"}

            4. CONVERSACIÓN GENERAL: saludos, agradecimientos, consejos financieros, dudas
               sobre conceptos (IVA, IRPF, ahorro, inversión), o cualquier otra cosa.
               EN ESTE CASO NO DEVUELVAS JSON: responde directamente con texto natural,
               conversacional y personalizado, evitando frases genéricas. Sin prefijos.
            $extraBlock
        """.trimIndent()
    }

    private fun queryExtractionPrompt(query: String): String = """
        Extrae los parámetros de esta consulta financiera y devuelve SOLO el JSON:
        {"query_type":"gastos|ingresos|balance|categoria|productos","periodo":"hoy|semana|mes|año","categoria":"texto o null","item":"texto o null"}

        Consulta: "$query"
    """.trimIndent()

    // ------------------------------------------------------------------
    // Parseo de respuestas
    // ------------------------------------------------------------------

    private fun parseInvoiceResponse(
        responseText: String,
        imageUri: String,
        fiscalCountry: String
    ): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)
            val tipoDoc = json.optString("tipo_documento", "").lowercase()
            val rawLower = responseText.lowercase()

            // ---------- NÓMINA ----------
            // documento salarial: empresa paga a empleado. Se guarda como
            // Income (NO Invoice) — totalDevengado=bruto, monto/totalNeto
            // =líquido recibido, fuente=nombre empresa.
            // Red de seguridad: si el modelo no devolvió "nomina" pero el
            // texto contiene keywords típicos de nómina, igual lo trata
            // como nómina.
            val esNomina = tipoDoc == "nomina" ||
                NOMINA_KEYWORDS.any { rawLower.contains(it) }
            if (esNomina) {
                val empresa = json.optString("empresa", json.optString("proveedor", ""))
                    .ifBlank { "Nómina" }
                val moneda = json.optString("moneda", "EUR")
                val devengado = json.optDouble("devengado", json.optDouble("total_devengado", 0.0))
                val liquido = json.optDouble("liquido", json.optDouble("neto", json.optDouble("total_neto", 0.0)))
                val total = json.optDouble("total", 0.0)
                val monto = when {
                    liquido > 0 -> liquido
                    devengado > 0 -> devengado
                    else -> total   // fallback: si la IA sólo rellenó "total"
                }
                if (monto <= 0) {
                    return AIResult(success = false, message = "No se pudo leer el importe de la nómina")
                }
                val irpf = json.optDouble("retencion_irpf", 0.0)
                val fecha = parseDate(json.optString("fecha", ""))
                val concepto = "Nómina - $empresa"

                val income = Income(
                    fecha = fecha,
                    concepto = concepto,
                    monto = monto,
                    totalDevengado = if (devengado > 0) devengado else monto,
                    totalNeto = if (liquido > 0) liquido else monto,
                    moneda = moneda,
                    fuente = empresa,
                    ivaPercent = 0.0,
                    irpfPercent = irpf,
                    imagenUri = imageUri,
                    notas = null
                )
                return AIResult(
                    success = true,
                    message = "Nómina procesada: $empresa — líquido ${if (liquido > 0) liquido else monto} $moneda${ if (irpf > 0) " (IRPF ${irpf}%)" else "" }",
                    income = income
                )
            }

            // ---------- FACTURA / TICKET / RECIBO ----------
            // El nombre del emisor puede llegar en cualquiera de estos
            // campos según cómo la IA haya interpretado el documento.
            // Cubrimos los nombres más comunes (ES/EN) para evitar
            // falsos 'Desconocido' cuando la IA rellena, p. ej.,
            // `merchant` (inglés), `comercio`, o `establecimiento`.
            val proveedor = listOf(
                "proveedor", "empresa", "razon_social", "razon social",
                "nombre", "name", "merchant", "comercio",
                "establecimiento", "vendedor", "supplier",
                "razon", "sociedad", "compañia", "compania"
            )
                .asSequence()
                .map { json.optString(it, "").trim() }
                .firstOrNull { it.isNotBlank() }
                ?.let { it }
                ?: "Desconocido"
            val fechaStr = json.optString("fecha", "")
            val total = json.optDouble("total", 0.0)
            val moneda = json.optString("moneda", "EUR")
            val ivaPercent = json.optDouble("tipo_iva", json.optDouble("iva_percent", 0.0))
            val irpfPercent = json.optDouble("retencion_irpf", 0.0)
            val nifEmisor = json.optString("nif_emisor").ifBlank { null }

            // país detectado (ISO 3166-1 alpha-2) si la IA lo indica.
            val detectedPais = json.optString("pais", "").ifBlank { fiscalCountry }

            val esIngresoFactura = tipoDoc.contains("emitida") ||
                // "factura emitida" (ventas) es un ingreso, no un gasto.
                json.optString("tipo", "").lowercase() == "ingreso"

            val fecha = parseDate(fechaStr)
            val invoice = Invoice(
                fecha = fecha,
                proveedor = proveedor,
                tipo = if (esIngresoFactura) InvoiceType.INGRESO else InvoiceType.GASTO,
                moneda = moneda,
                total = total,
                ivaPercent = ivaPercent,
                irpfPercent = irpfPercent,
                paisCodigo = detectedPais,
                nifEmisor = nifEmisor,
                nifReceptor = json.optString("nif_receptor").ifBlank { null },
                imagenUri = imageUri,
                ocrRawText = responseText
            )

            val productsArray = json.optJSONArray("productos")
            val products = mutableListOf<Product>()
            if (productsArray != null) {
                for (i in 0 until productsArray.length()) {
                    val productJson = productsArray.getJSONObject(i)
                    products.add(
                        Product(
                            invoiceId = 0,
                            descripcion = productJson.optString("descripcion", ""),
                            cantidad = productJson.optDouble("cantidad", 1.0),
                            precioUnitario = productJson.optDouble("precio_unitario", 0.0),
                            subtotal = productJson.optDouble("subtotal", 0.0),
                            ivaPercent = productJson.optDouble("iva_percent", ivaPercent)
                        )
                    )
                }
            }

            val tipoLabel = if (esIngresoFactura) "Ingreso" else "Gasto"
            AIResult(success = true, message = "$tipoLabel procesado correctamente", invoice = invoice, products = products)
        } catch (e: Exception) {
            AIResult(success = false, message = "Error al parsear documento: ${e.message}")
        }
    }

    /**
     * Construye el prompt de OCR adaptado al país fiscal.
     *
     *  - España (ES): reglas AEAT estrictas — detecta tipo de documento,
     *    motivos de la Orden HAC/773/2019 (NIF desglosado en país ISO +
     *    base imponible + tipo IVA + cuota + retención IRPF + total),
     *    nómina con devengado/neto/IRPF/Seguridad Social.
     *  - Otro país: prompt genérico, sólo pide los básicos sin imposición
     *    de reglas ES (cada país es diferente).
     * Los [config] (ivaRates/etc.) se inyectan como hints para el modelo.
     */
    private fun ocrPrompt(country: String, config: CountryFiscalConfig?): String {
        val es = country.equals("ES", ignoreCase = true)
        val ivaHint = config?.ivaRates?.takeIf { it.isNotEmpty() }?.let {
            "Posibles tipos de ${config.nombreLeyFiscal}: ${it.joinToString(", ")}%."
        } ?: ""
        val irpfHint = config?.irpfRate?.let { "IRPF típico ~${it}%." } ?: ""

        if (es) {
            return """
                Eres un experto en contabilidad española (AEAT, Orden HAC/773/2019).
                Analiza la imagen del documento y devuelve SOLO un JSON válido (sin
                markdown, sin comentarios). Todos los importes como NÚMEROS.

                CRÍTICO — PASO 1: clasifica la imagen en EXACTAMENTE uno de:
                  "nomina"          → recibo salarial recibido por un empleado. Palabras
                                     clave típicas: nómina, salario, sueldo, devengado,
                                     líquido a percibir, percepciones, deducciones,
                                     retención IRPF, base de cotización, Seguridad Social.
                  "factura_recibida"→ factura/ticket de compra/gasto (tú eres receptor).
                  "factura_emitida" → factura de venta (tú eres el emisor).
                  "ticket"          → recibo simplificado de compra (sin NIF).
                  "recibo"          → otro recibo de pago.

                REGLA OBLIGATORIA: en una NÓMINA NO devuelvas "proveedor" ni "total";
                usa "empresa" + "devengado"/"liquido". Confundir una nómina con una
                factura produce un gasto erróneo con 0€ que rompe la contabilidad.

                PASO 2 — extrae los campos del tipo correspondiente.

                COMUNES:
                  "pais":"ES", "moneda":"EUR", "fecha":"YYYY-MM-DD"

                PARA "nomina" (campos EXACTOS, en bruto sin IVA):
                  "empresa":"...", "empleado":"...",
                  "devengado":1567.54,   // total devengado (bruto)
                  "liquido":1212.30,     // líquido a percibir (neto)
                  "retencion_irpf":15.0, // % IRPF aplicado
                  "base_cotizacion":1313.46,
                  "seguridad_social":105.90,
                  "nif_emisor":"...NIF empresa..."

                PARA factura_recibida/factura_emitida/ticket/recibo (AEAT):
                  "proveedor":"...", "nif_emisor":"...", "nif_receptor":"...",
                  "numero_factura":"...",
                  "base_imponible":149.42,
                  "tipo_iva":21.0,        // 0/4/10/21
                  "cuota_iva":31.38,
                  "retencion_irpf":0.0,   // sólo si aplica (profesionales)
                  "total":180.80,
                  "productos":[{"descripcion":"...","cantidad":1.0,"precio_unitario":0.0,
                                "subtotal":0.0,"iva_percent":21.0}]

                EJEMPLO de salida para una NÓMINA:
                {"tipo_documento":"nomina","pais":"ES","moneda":"EUR","fecha":"2026-06-30",
                 "empresa":"ACME S.L.","empleado":"Juan Pérez",
                 "devengado":1567.54,"liquido":1212.30,"retencion_irpf":15.0,
                 "base_cotizacion":1313.46,"seguridad_social":105.90,
                 "nif_emisor":"B12345678"}

                EJEMPLO de salida para una FACTURA RECIBIDA:
                {"tipo_documento":"factura_recibida","pais":"ES","moneda":"EUR",
                 "fecha":"2026-06-15","proveedor":"Papelería López S.L.",
                 "nif_emisor":"B87654321","nif_receptor":"12345678Z",
                 "numero_factura":"F-2026-0421","base_imponible":149.42,
                 "tipo_iva":21.0,"cuota_iva":31.38,"retencion_irpf":0.0,
                 "total":180.80,"productos":[{"descripcion":"Toner","cantidad":2.0,
                 "precio_unitario":74.71,"subtotal":149.42,"iva_percent":21.0}]}

                REGLAS ES:
                  - NIF: rellena "nif_emisor" y/o "nif_receptor" con el NÚMERO tal cual
                    (sin prefijo de país); el de España lleva letra final (DNI-NIF). No inventes.
                  - IVA: elige entre 0/4/10/21. Si no es claro, 21.
                  - Base + cuota IVA + retención IRPF deben cuadrar con total.
                  $ivaHint
                  $irpfHint

                Si un dato no es legible, usa null. Devuelve SIEMPRE "tipo_documento".
            """.trimIndent()
        }

        // ---- Otro país: prompt genérico (no imponer reglas ES) ----
        return """
            Analiza el documento (factura/recibo/nómina) y devuelve SOLO un JSON.
            Todos los importes como NÚMEROS. Fechas "YYYY-MM-DD".

            "tipo_documento":"factura_recibida|factura_emitida|nomina|ticket|recibo",
            "pais":"$country",
            "moneda":"...","fecha":"YYYY-MM-DD","proveedor":"...",
            "nif_emisor":"...","nif_receptor":"...",
            "total":0.0,"iva_percent":0.0,"retencion_irpf":0.0,
            "productos":[{"descripcion":"...","cantidad":1.0,"precio_unitario":0.0,"subtotal":0.0,"iva_percent":0.0}]

            Para nómina añade:
            "empresa":"...","devengado":0.0,"liquido":0.0,"retencion_irpf":0.0
            $ivaHint
            $irpfHint
            Devuelve solo el JSON, sin markdown.
        """.trimIndent()
    }

    private fun extractJsonFromResponse(responseText: String): JSONObject {
        val jsonMatch = Regex("""\{[\s\S]*\}""").find(responseText)
        val jsonString = jsonMatch?.value ?: responseText
        return JSONObject(jsonString)
    }

    private fun parseCommandResponse(responseText: String): AIResult {
        val trimmed = responseText.trim()
        // Si la respuesta no es un JSON, el modelo habló en modo conversacional (chat).
        if (!trimmed.startsWith("{")) {
            return AIResult(success = true, message = trimmed)
        }
        return try {
            val json = extractJsonFromResponse(responseText)
            val action = json.optString("action", "chat")

            when (action) {
                "add_expense" -> {
                    val descripcion = json.optString("descripcion", json.optString("concepto", ""))
                    val cantidad = json.optDouble("cantidad", 1.0)
                    val precioUnitario = json.optDouble("precio_unitario", 0.0)
                    val total = json.optDouble("total", json.optDouble("monto", cantidad * precioUnitario))
                    val moneda = json.optString("moneda", "EUR")
                    val fechaStr = json.optString("fecha", "")
                    val fecha = parseDate(fechaStr)

                    val invoice = Invoice(
                        fecha = fecha,
                        proveedor = descripcion,
                        tipo = InvoiceType.GASTO,
                        moneda = moneda,
                        total = total
                    )

                    val product = Product(
                        invoiceId = 0,
                        descripcion = descripcion,
                        cantidad = cantidad,
                        precioUnitario = precioUnitario,
                        subtotal = total
                    )

                    AIResult(success = true, message = "Gasto agregado: $descripcion - $total $moneda", invoice = invoice, products = listOf(product))
                }
                "add_income" -> {
                    val concepto = json.optString("concepto", json.optString("descripcion", ""))
                    val totalDevengado = json.optDouble("total_devengado", 0.0)
                    val totalNeto = json.optDouble("total_neto", 0.0)
                    val monto = json.optDouble("monto", if (totalNeto > 0) totalNeto else totalDevengado)
                    val moneda = json.optString("moneda", "EUR")
                    val fechaStr = json.optString("fecha", "")
                    val fuente = json.optString("fuente")
                    val fecha = parseDate(fechaStr)

                    val income = Income(
                        fecha = fecha,
                        concepto = concepto,
                        monto = monto,
                        totalDevengado = if (totalDevengado > 0) totalDevengado else monto,
                        totalNeto = if (totalNeto > 0) totalNeto else monto,
                        moneda = moneda,
                        fuente = fuente
                    )

                    val displayMonto = if (totalDevengado > 0 && totalNeto > 0) {
                        "Devengado: $totalDevengado $moneda / Neto: $totalNeto $moneda"
                    } else {
                        "$monto $moneda"
                    }

                    AIResult(success = true, message = "Ingreso agregado: $concepto - $displayMonto", income = income)
                }
                "query" -> {
                    AIResult(success = true, message = "Consulta procesada", queryResult = json.toString())
                }
                "chat" -> {
                    // El modelo respondió con JSON de chat; tratamos "response" como texto.
                    val chatResponse = json.optString("response", "")
                    AIResult(success = true, message = chatResponse)
                }
                else -> {
                    // Acción desconocida: caemos en respuesta conversacional con el texto bruto.
                    AIResult(success = true, message = trimmed)
                }
            }
        } catch (e: Exception) {
            AIResult(success = false, message = "No se pudo interpretar la respuesta: ${e.message}")
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AIService"
        private const val MODEL_NAME = "gemini-3.5-flash"
        const val SETTINGS_PATH = "Configuración > IA"
        const val NO_API_KEY_MESSAGE =
            "Aún no has configurado tu API key de Gemini. Ve a $SETTINGS_PATH para añadir la tuya (es gratis en Google AI Studio)."

        /** Límites del plan gratuito. Premium los eleva vía [setPremiumLimits]. */
        const val FREE_MAX_HISTORY_TURNS = 3
        const val PREMIUM_MAX_HISTORY_TURNS = 10

        /**
         * Keywords ESPECÍFICAS de nómina (recibo salarial) que no aparecen
         * típicamente en facturas de compra/venta. Se usan como red de
         * seguridad en [parseInvoiceResponse] cuando el modelo no devuelve
         * `tipo_documento: "nomina"`.
         *
         * NO incluir "retención", "irpf", "empresa:" — esas palabras
         * aparecen en facturas normales (con retención IRPF de
         * profesionales, encabezado "EMPRESA: …"). Con ellas, cualquier
         * factura con IRPF sería falsamente clasificada como nómina →
         * ingreso, rompiendo la contabilidad.
         */
        private val NOMINA_KEYWORDS = listOf(
            "nómina", "nomina", "salario", "sueldo",
            "devengado", "líquido a percibir", "liquido a percibir",
            "percepciones", "deducciones",
            "base de cotización", "cotización",
            "total devengado", "total a percibir",
            "seguridad social"
        )
    }
}
