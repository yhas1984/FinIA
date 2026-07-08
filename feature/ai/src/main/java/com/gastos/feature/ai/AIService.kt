package com.gastos.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
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
    @ApplicationContext private val context: Context
) {
    private var generativeModel: GenerativeModel? = null
    private var chatSession: Chat? = null
    private var currentApiKey: String = ""
    private var systemInstructions: String = ""

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

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(INVOICE_PROMPT)
                }
            )
            parseInvoiceResponse(response.text ?: "", imageUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando imagen", e)
            AIResult(success = false, message = friendlyError(e))
        }
    }

    /** Procesa un PDF de factura usando PdfRenderer para convertirlo a imagen. */
    suspend fun processInvoiceFromPdf(pdfUri: Uri): AIResult {
        val model = generativeModel ?: return notConfiguredResult()
        return try {
            val bitmap = pdfToBitmap(pdfUri)
            if (bitmap == null) {
                return AIResult(success = false, message = "No se pudo leer el PDF. Asegúrate de que es un archivo válido.")
            }
            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(INVOICE_PROMPT)
                }
            )
            val text = response.text ?: ""
            if (text.isBlank()) {
                return AIResult(success = false, message = "La IA no pudo extraer datos del PDF.")
            }
            parseInvoiceResponse(text, pdfUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando PDF", e)
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

    private fun parseInvoiceResponse(responseText: String, imageUri: String): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)

            val proveedor = json.optString("proveedor", json.optString("concepto", "Desconocido"))
            val fechaStr = json.optString("fecha", "")
            // Usar parseMoneyFromJson para manejar números con coma, puntos de miles, o strings.
            var total = parseMoneyFromJson(json, "total")
            val moneda = json.optString("moneda", "EUR")
            val ivaPercent = parseMoneyFromJson(json, "iva_percent").let { if (it > 0) it else 21.0 }
            val irpfPercent = parseMoneyFromJson(json, "irpf_percent")
            val nifEmisor = json.optString("nif_emisor")
            val tipoStr = json.optString("tipo", "").lowercase()

            // Para nóminas: el modelo puede devolver total_devengado y total_neto
            // en vez de total. Usamos el neto como total si existe.
            val totalDevengado = parseMoneyFromJson(json, "total_devengado")
            val totalNeto = parseMoneyFromJson(json, "total_neto")
            if (total == 0.0 && totalNeto > 0) total = totalNeto
            if (total == 0.0 && totalDevengado > 0) total = totalDevengado

            // Datos fiscales España: base imponible + cuota IVA + cuota IRPF.
            var baseImponible = parseMoneyFromJson(json, "base_imponible")
            var cuotaIva = parseMoneyFromJson(json, "cuota_iva")
            var cuotaIrpf = parseMoneyFromJson(json, "cuota_irpf")
            if (total > 0 && baseImponible == 0.0) {
                baseImponible = if (ivaPercent > 0) total / (1 + ivaPercent / 100.0) else total
            }
            if (cuotaIva == 0.0 && baseImponible > 0 && ivaPercent > 0) {
                cuotaIva = baseImponible * ivaPercent / 100.0
            }
            if (cuotaIrpf == 0.0 && baseImponible > 0 && irpfPercent > 0) {
                cuotaIrpf = baseImponible * irpfPercent / 100.0
            }

            val rawLower = responseText.lowercase()
            val incomeKeywords = listOf("nómina", "nomina", "salario", "sueldo", "ingreso", "paga", "devengado", "neto", "bruto", "irpf", "seguridad social", "deducciones", "retenciones")
            val isIncome = incomeKeywords.any { rawLower.contains(it) } || tipoStr.contains("ingreso") || tipoStr.contains("income") || tipoStr.contains("nomina")

            val fecha = parseDate(fechaStr)

            val productsArray = json.optJSONArray("productos")
            val products = mutableListOf<Product>()

            if (productsArray != null) {
                for (i in 0 until productsArray.length()) {
                    val productJson = productsArray.getJSONObject(i)
                    val descripcion = productJson.optString("descripcion", "")
                    val cantidad = parseMoneyFromJson(productJson, "cantidad").let { if (it > 0) it else 1.0 }
                    val ivaP = parseMoneyFromJson(productJson, "iva_percent").let { if (it > 0) it else ivaPercent }

                    // precio_unitario viene del ticket (IVA-incluido).
                    val precioConIvaUnit = parseMoneyFromJson(productJson, "precio_unitario")
                    val factor = 1.0 + ivaP / 100.0
                    val precioSinIvaUnit = if (factor > 0 && precioConIvaUnit > 0) precioConIvaUnit / factor else 0.0
                    val subtotalSinIva = cantidad * precioSinIvaUnit
                    val ivaAmount = subtotalSinIva * ivaP / 100.0

                    products.add(
                        Product(
                            invoiceId = 0,
                            descripcion = descripcion,
                            cantidad = cantidad,
                            precioUnitario = precioSinIvaUnit,
                            subtotal = subtotalSinIva,
                            ivaPercent = ivaP,
                            ivaAmount = ivaAmount
                        )
                    )
                }
            }

            // Si hay productos agregamos base/cuota reales; si no, usamos lo del modelo.
            val aggregateBase = if (products.isNotEmpty()) products.sumOf { it.subtotal } else baseImponible
            val aggregateCuotaIva = if (products.isNotEmpty()) products.sumOf { it.ivaAmount } else cuotaIva

            val invoice = Invoice(
                fecha = fecha,
                proveedor = proveedor,
                tipo = if (isIncome) InvoiceType.INGRESO else InvoiceType.GASTO,
                moneda = moneda,
                total = total,
                ivaPercent = if (products.isNotEmpty()) 0.0 else ivaPercent,
                irpfPercent = irpfPercent,
                baseImponible = aggregateBase,
                cuotaIva = aggregateCuotaIva,
                cuotaIrpf = cuotaIrpf,
                nifEmisor = nifEmisor,
                imagenUri = imageUri,
                ocrRawText = responseText
            )

            val tipoLabel = if (isIncome) "Ingreso" else "Factura"
            AIResult(success = true, message = "$tipoLabel procesada correctamente", invoice = invoice, products = products)
        } catch (e: Exception) {
            AIResult(success = false, message = "Error al parsear la factura: ${e.message}")
        }
    }

    /**
     * Parsea un valor monetario de un JSONObject de forma robusta.
     * Maneja: números nativos JSON (double), strings con coma decimal,
     * strings con puntos de miles ("2.409,90"), y strings con espacios.
     * Ej: "2.409,90" → 2409.90 ; "21,5" → 21.5 ; "24.090.909" → 24090909.
     */
    private fun parseMoneyFromJson(json: JSONObject, key: String): Double {
        return try {
            if (!json.has(key) || json.isNull(key)) return 0.0
            val raw = json.opt(key)?.toString()?.trim() ?: return 0.0
            if (raw.isEmpty()) return 0.0
            // Quitar símbolos de moneda y espacios.
            val cleaned = raw
                .replace("€", "")
                .replace("$", "")
                .replace("EUR", "")
                .replace("\\s".toRegex(), "")
            // Si tiene punto y coma (formato es-ES: "2.409,90"):
            // el punto es separador de miles, la coma es decimal.
            if (cleaned.contains('.') && cleaned.contains(',')) {
                return cleaned.replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0
            }
            // Si solo tiene coma (formato es-ES simple: "21,5"):
            if (cleaned.contains(',')) {
                return cleaned.replace(',', '.').toDoubleOrNull() ?: 0.0
            }
            // Si solo tiene punto, o es un número entero:
            cleaned.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
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

    /** Convierte la primera página de un PDF a Bitmap con el doble de resolución. */
    private fun pdfToBitmap(pdfUri: Uri): Bitmap? {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            fd = context.contentResolver.openFileDescriptor(pdfUri, "r") ?: return null
            renderer = PdfRenderer(fd)
            if (renderer.pageCount == 0) return null
            page = renderer.openPage(0)
            val scale = 2
            val width = page.width * scale
            val height = page.height * scale
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error convirtiendo PDF a bitmap", e)
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { fd?.close() } catch (_: Exception) {}
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

        private val INVOICE_PROMPT = """
            Analiza esta factura/recibo y extrae la siguiente información en formato JSON.
            IMPORTANTE:
            - Los precios en el ticket son **IVA incluido** (es lo que ve el cliente).
            - CADA producto puede tener una tasa de IVA distinta (Mercadona:
              4% para alimentos básicos, 10% para algunos alimentos y agua,
              21% para cosmética, limpieza, etc.). Devuelve `iva_percent` POR
              PRODUCTO, no un único valor global.
            - El `iva_percent` a nivel de factura es OPCIONAL y solo se usa
              si no hay productos. Si hay productos, puedes ponerlo a 0.
            {
                "proveedor": "nombre del proveedor",
                "fecha": "YYYY-MM-DD",
                "iva_percent_global": 0.0,
                "total": 0.0,
                "moneda": "EUR",
                "nif_emisor": "NIF/CIF del emisor si visible",
                "productos": [
                    {
                        "descripcion": "nombre del producto",
                        "cantidad": 1.0,
                        "precio_unitario": 0.0,
                        "subtotal": 0.0,
                        "iva_percent": 21.0
                    }
                ]
            }
            Convenciones:
            - total = importe IVA incluido (lo que paga el cliente).
            - precio_unitario y subtotal de cada producto: IVA incluido.
            - iva_percent de cada producto: el que aplique (4, 10 o 21 en España).
            Si no hay productos, devuelve iva_percent_global = 21 y array productos vacío.
            Si no puedes extraer algún campo, usa null. Devuelve solo el JSON.
        """.trimIndent()
    }
}
