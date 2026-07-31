package com.gastos.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.domain.model.TransactionCategories
import com.gastos.extension.SafeLog
import com.gastos.repository.CountryFiscalConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private val fiscalConfigRepository: CountryFiscalConfigRepository,
    private val geminiRestClient: GeminiRestClient
) {
    @Volatile
    private var currentApiKey: String = ""
    private var systemInstructions: String = ""
    private val chatMutex = Mutex()
    @Volatile
    private var currentFiscalCountry: String = "ES"
    @Volatile
    private var cachedFiscalConfig: CountryFiscalConfig? = null
    @Volatile
    private var cachedFiscalCountryForConfig: String? = null
    private val chatHistory: MutableList<GeminiContent> = mutableListOf()
    private var maxHistoryTurns: Int = FREE_MAX_HISTORY_TURNS

    suspend fun setFiscalCountry(countryCode: String) {
        val code = countryCode.uppercase()
        if (code == currentFiscalCountry && cachedFiscalConfig != null) return
        currentFiscalCountry = code
        cachedFiscalConfig = fiscalConfigRepository.getConfigByCountry(code)
        cachedFiscalCountryForConfig = code
    }

    private suspend fun currentFiscalConfig(): CountryFiscalConfig? {
        val code = currentFiscalCountry
        if (cachedFiscalCountryForConfig != code || cachedFiscalConfig == null) {
            cachedFiscalConfig = fiscalConfigRepository.getConfigByCountry(code)
            cachedFiscalCountryForConfig = code
        }
        return cachedFiscalConfig
    }

    suspend fun setPremiumLimits(isPremium: Boolean) {
        chatMutex.withLock {
            val newMax = if (isPremium) PREMIUM_MAX_HISTORY_TURNS else FREE_MAX_HISTORY_TURNS
            if (newMax != maxHistoryTurns) {
                maxHistoryTurns = newMax
                trimHistory()
            }
        }
    }

    suspend fun configureGemini(apiKey: String, systemInstructions: String) {
        chatMutex.withLock {
            currentApiKey = apiKey
            this.systemInstructions = systemInstructions
            if (apiKey.isBlank()) {
                chatHistory.clear()
            }
        }
    }

    suspend fun resetChat() {
        chatMutex.withLock { chatHistory.clear() }
    }

    suspend fun replaceChatHistory(messages: List<ChatMessageRecord>) {
        chatMutex.withLock {
            chatHistory.clear()
            buildChatContents(messages, maxHistoryTurns).forEach(chatHistory::add)
        }
    }

    internal fun buildChatContents(messages: List<ChatMessageRecord>, limitTurns: Int): List<GeminiContent> {
        return selectContextMessages(messages, limitTurns).map { message ->
            GeminiContent(
                role = sanitizeRole(message.role),
                textParts = listOf(GeminiTextPart((message.contextText ?: message.visibleText).trim()))
            )
        }
    }

    private fun trimHistory() {
        while (chatHistory.size > maxHistoryTurns * 2) {
            chatHistory.removeAt(0)
        }
    }

    fun isConfigured(): Boolean = currentApiKey.isNotBlank()

    suspend fun validateApiKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("API key vacía"))
        try {
            geminiRestClient.generateContent(
                GeminiGenerateRequest(
                    apiKey = apiKey,
                    systemInstruction = "Responde solo con pong.",
                    contents = listOf(GeminiContent(role = ROLE_USER, textParts = listOf(GeminiTextPart("ping"))))
                )
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun processCommand(command: String): AIResult {
        return chatMutex.withLock {
            if (!isConfigured()) return@withLock notConfiguredResult()
            try {
                val responseText = geminiRestClient.generateContent(
                    buildRequest(listOf(GeminiContent(role = ROLE_USER, textParts = listOf(GeminiTextPart(command)))))
                )
                recordTurn(command, responseText)
                parseCommandResponse(responseText)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeLog.e(TAG, "Error en processCommand", error)
                AIResult(success = false, message = friendlyError(error))
            }
        }
    }

    fun processCommandStreaming(command: String): Flow<String> {
        val userMsg = command
        return flow {
            chatMutex.withLock {
                if (!isConfigured()) throw IllegalStateException(NO_API_KEY_MESSAGE)
                val collected = StringBuilder()
                geminiRestClient.streamGenerateContent(
                    buildRequest(listOf(GeminiContent(role = ROLE_USER, textParts = listOf(GeminiTextPart(userMsg)))))
                ).collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        collected.append(chunk)
                        emit(chunk)
                    }
                }
                recordTurn(userMsg, collected.toString())
            }
        }.catch { error ->
            if (error is CancellationException) throw error
            SafeLog.e(TAG, "Error en streaming", error)
            throw error
        }
    }

    private fun recordTurn(user: String, model: String) {
        if (user.isNotBlank()) {
            chatHistory.add(GeminiContent(role = ROLE_USER, textParts = listOf(GeminiTextPart(user))))
        }
        if (model.isNotBlank()) {
            chatHistory.add(GeminiContent(role = ROLE_MODEL, textParts = listOf(GeminiTextPart(model))))
        }
        trimHistory()
    }

    fun parseStreamingResult(responseText: String): AIResult = parseCommandResponse(responseText)

    suspend fun processInvoiceFromImage(imageUri: Uri): AIResult {
        if (!isConfigured()) return notConfiguredResult()
        return try {
            val bitmap = uriToBitmap(imageUri) ?: return AIResult(success = false, message = "Error al cargar la imagen")
            val fiscalConfig = currentFiscalConfig()
            val prompt = if (fiscalConfig == null) {
                UNIVERSAL_OCR_PROMPT
            } else {
                "$UNIVERSAL_OCR_PROMPT\n\n" +
                    "PAÍS DE RESPALDO: si el documento no permite detectar el país, usa " +
                    "${fiscalConfig.paisCodigo} (${fiscalConfig.nombrePais}). " +
                    "Sus tipos habituales de ${fiscalConfig.nombreLeyFiscal} son " +
                    "${fiscalConfig.ivaRates.joinToString()}%. No sustituyas valores legibles del documento."
            }
            val raw = geminiRestClient.generateContent(
                GeminiGenerateRequest(
                    apiKey = currentApiKey,
                    systemInstruction = buildSystemPrompt(systemInstructions),
                    contents = listOf(
                        GeminiContent(
                            role = ROLE_USER,
                            textParts = listOf(GeminiTextPart(prompt)),
                            inlineDataParts = listOf(bitmap.toInlineImagePart())
                        )
                    )
                )
            )
            SafeLog.d(TAG, "OCR raw response: $raw")
            parseInvoiceResponse(raw, imageUri.toString(), currentFiscalCountry)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeLog.e(TAG, "Error procesando imagen", error)
            AIResult(success = false, message = friendlyError(error))
        }
    }

    suspend fun queryData(query: String): AIResult {
        if (!isConfigured()) return notConfiguredResult()
        return try {
            val responseText = geminiRestClient.generateContent(
                GeminiGenerateRequest(
                    apiKey = currentApiKey,
                    systemInstruction = buildSystemPrompt(systemInstructions),
                    contents = listOf(GeminiContent(role = ROLE_USER, textParts = listOf(GeminiTextPart(queryExtractionPrompt(query)))))
                )
            )
            AIResult(success = true, message = "Consulta procesada", queryResult = responseText)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeLog.e(TAG, "Error en queryData", error)
            AIResult(success = false, message = friendlyError(error))
        }
    }

    private fun notConfiguredResult(): AIResult = AIResult(success = false, message = NO_API_KEY_MESSAGE)

    private fun friendlyError(error: Exception): String {
        return when {
            error is GeminiApiException && error.statusCode in listOf(400, 401, 403) ->
                "Tu API key de Gemini no es válida. Revísala en $SETTINGS_PATH."
            error is GeminiApiException && error.statusCode == 429 ->
                "Se ha alcanzado el límite de uso de la API gratuita de Gemini. Inténtalo de nuevo más tarde."
            error is GeminiApiException && error.statusCode in 500..599 ->
                "Gemini no responde temporalmente. Inténtalo de nuevo más tarde."
            else -> "Error al contactar con Gemini. Inténtalo de nuevo más tarde."
        }
    }

    private fun buildRequest(newContents: List<GeminiContent>): GeminiGenerateRequest = GeminiGenerateRequest(
        apiKey = currentApiKey,
        systemInstruction = buildSystemPrompt(systemInstructions),
        contents = chatHistory.toList() + newContents
    )

    private fun sanitizeRole(role: String): String = if (role == ROLE_MODEL) ROLE_MODEL else ROLE_USER

    private fun Bitmap.toInlineImagePart(): GeminiInlineDataPart {
        val output = ByteArrayOutputStream()
        val format = if (hasAlpha()) CompressFormat.PNG else CompressFormat.JPEG
        val mimeType = if (hasAlpha()) MIME_TYPE_PNG else MIME_TYPE_JPEG
        compress(format, IMAGE_COMPRESSION_QUALITY, output)
        return GeminiInlineDataPart(
            mimeType = mimeType,
            data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        )
    }

    private fun buildSystemPrompt(userInstructions: String): String {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .format(java.util.Date())
        val extra = userInstructions.trim()
        val extraBlock = if (extra.isNotEmpty()) {
            "\n\nInstrucciones adicionales del usuario (sigue también estas reglas):\n$extra"
        } else ""
        return """
            Eres FinAI, un asistente financiero personal inteligente, cercano y conversacional.
            Respondes siempre en español, con tono amable y profesional. Hoy es $today.

            Tu trabajo es analizar el mensaje del usuario y decidir qué acción realizar.
            Para registrar o consultar datos devuelves SOLO un objeto JSON válido,
            sin markdown ni texto adicional. Para conversación general respondes
            directamente con texto natural, nunca con JSON.

            Reglas de acción:
            1. CONSULTA FINANCIERA: si pregunta cuánto gastó, sus ingresos, balance, totales,
               comercios, proveedores, productos comprados, etc.:
               {"action":"query","query_type":"gastos|ingresos|balance|productos","periodo":"hoy|semana|mes|año","categoria":null,"proveedor":null,"item":null,"match_mode":"exact|group|auto|null"}

               Diferencia siempre estos filtros:
               - COMERCIO/PROVEEDOR: Mercadona, Lidl, Amazon, Repsol, etc. Usa
                 query_type="gastos", proveedor="nombre", categoria=null e item=null.
               - CATEGORÍA: Alimentación, Transporte, Vivienda, Ocio, etc. Usa
                 categoria="nombre" y proveedor=null.
               - PRODUCTO: café, agua, pan, gasolina, etc. Usa query_type="productos"
                 e item="nombre". Una tienda o empresa NUNCA es un producto.
               - "ganado", "ganancia", "beneficio", "neto" o "lo que me queda"
                 significan balance (ingresos menos gastos).
               - "ingresado", "cobrado", "recibido", "salario" o "nómina"
                 significan ingresos, no balance.
               - "lo que va de mes", "este mes" y "mes actual" usan periodo="mes".
               - En seguimientos como "ese comercio", "esa tienda" o "ahí", conserva
                 el proveedor mencionado en la conversación anterior.

               Reglas extra para productos:
               - Un nombre genérico (agua, café, leche, pan) representa una familia y usa match_mode="group".
               - Si el usuario pide SOLO un producto exacto o escribe la descripción completa de la línea
                 (ej. "solo Agua Consum 8L"), usa match_mode="exact".
               - Comercio y producto se pueden combinar: "agua en Consum" => item="agua", proveedor="Consum", match_mode="group".
               - FinAI valida localmente el alcance final; match_mode es solo una sugerencia.
               - Si pregunta por un producto concreto, NO uses query_type="balance".

            2. REGISTRAR GASTO: si dice que gastó, compró o pagó algo:
               {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0.0,"total":0.0,"moneda":"EUR","fecha":"$today","categoria":"texto"}
               - Usa una categoría predeterminada de gasto si encaja claramente.
               - Si el usuario menciona una categoría personalizada explícita, consérvala.

            3. REGISTRAR INGRESO: si menciona nómina, salario, cobro o ingreso recibido:
               {"action":"add_income","concepto":"texto","total_devengado":0.0,"total_neto":0.0,"monto":0.0,"moneda":"EUR","fecha":"$today","fuente":"texto","categoria":"texto"}
               - Usa una categoría predeterminada de ingreso si encaja claramente.
               - Si es una nómina, la categoría por defecto es "Nómina".

            4. CONVERSACIÓN GENERAL: saludos, agradecimientos, consejos financieros, dudas
               sobre conceptos (IVA, IRPF, ahorro, inversión), o cualquier otra cosa.
               EN ESTE CASO NO DEVUELVAS JSON: responde directamente con texto natural,
               conversacional y personalizado, evitando frases genéricas. Sin prefijos.
            $extraBlock
        """.trimIndent()
    }

    private fun queryExtractionPrompt(query: String): String = """
        Extrae los parámetros de esta consulta financiera y devuelve SOLO el JSON:
        {"query_type":"gastos|ingresos|balance|productos","periodo":"hoy|semana|mes|año","categoria":"texto o null","proveedor":"texto o null","item":"texto o null","match_mode":"exact|group|auto|null"}

        Reglas:
        - comercio, tienda, supermercado, empresa o proveedor => proveedor, nunca item
        - categoría financiera (Alimentación, Transporte, etc.) => categoria
        - producto genérico (agua, café, leche, pan) => match_mode="group"
        - "solo", "únicamente", "exactamente" + descripción específica => match_mode="exact"
        - comercio y producto pueden combinarse: proveedor="Consum", item="agua", match_mode="group"
        - si la consulta es sobre un producto, usa query_type="productos", nunca "balance"
        - "ganado", "ganancia", "beneficio", "neto" o "lo que me queda" => balance
        - "ingresado", "cobrado", "recibido", "salario" o "nómina" => ingresos

        Consulta: "$query"
    """.trimIndent()

    private fun parseInvoiceResponse(responseText: String, imageUri: String, fiscalCountry: String): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)
            val tipoDoc = json.optString("tipo_documento", "").lowercase()
            val rawLower = responseText.lowercase()
            val esNomina = tipoDoc == "nomina" || NOMINA_KEYWORDS.any { rawLower.contains(it) }
            if (esNomina) {
                val empresa = json.optString("empresa", json.optString("proveedor", "")).ifBlank { "Nómina" }
                val moneda = json.optString("moneda").ifBlank { "EUR" }
                val devengado = json.optDouble("devengado", json.optDouble("total_devengado", 0.0))
                val liquido = json.optDouble("liquido", json.optDouble("neto", json.optDouble("total_neto", 0.0)))
                val total = json.optDouble("total", 0.0)
                val monto = when {
                    liquido > 0 -> liquido
                    devengado > 0 -> devengado
                    else -> total
                }
                if (monto <= 0) return AIResult(success = false, message = "No se pudo leer el importe de la nómina")
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
                    categoria = TransactionCategories.canonicalIncomeCategory(json.optString("categoria").ifBlank { "Nómina" }),
                    ivaPercent = 0.0,
                    irpfPercent = irpf,
                    imagenUri = imageUri,
                    notas = null
                )
                return AIResult(
                    success = true,
                    message = "Nómina procesada: $empresa — líquido ${if (liquido > 0) liquido else monto} $moneda${if (irpf > 0) " (IRPF ${irpf}%)" else ""}",
                    income = income
                )
            }
            val proveedor = listOf(
                "proveedor", "empresa", "razon_social", "razon social", "nombre", "name", "merchant", "comercio",
                "establecimiento", "vendedor", "supplier", "razon", "sociedad", "compañia", "compania"
            ).asSequence().map { json.optString(it, "").trim() }.firstOrNull { it.isNotBlank() } ?: "Desconocido"
            val total = json.optDouble("total", 0.0)
            val moneda = json.optString("moneda").ifBlank { "EUR" }
            val ivaPercent = json.optDouble("tipo_iva", json.optDouble("iva_percent", 0.0))
            val irpfPercent = json.optDouble("retencion_irpf", 0.0)
            val nifEmisor = json.optString("nif_emisor").ifBlank { null }
            val detectedPais = json.optString("pais", "").ifBlank { fiscalCountry }
            val esIngresoFactura = tipoDoc.contains("emitida") || json.optString("tipo", "").lowercase() == "ingreso"
            val invoice = Invoice(
                fecha = parseDate(json.optString("fecha", "")),
                proveedor = proveedor,
                tipo = if (esIngresoFactura) InvoiceType.INGRESO else InvoiceType.GASTO,
                categoria = if (esIngresoFactura) {
                    TransactionCategories.canonicalIncomeCategory(json.optString("categoria").ifBlank { "Ventas" })
                } else {
                    TransactionCategories.canonicalExpenseCategory(json.optString("categoria"))
                },
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
                for (index in 0 until productsArray.length()) {
                    val productJson = productsArray.getJSONObject(index)
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
            AIResult(
                success = true,
                message = if (esIngresoFactura) "Ingreso procesado correctamente" else "Gasto procesado correctamente",
                invoice = invoice,
                products = products
            )
        } catch (error: Exception) {
            AIResult(success = false, message = "Error al parsear documento: ${error.message}")
        }
    }

    private fun extractJsonFromResponse(responseText: String): JSONObject {
        val jsonMatch = Regex("""\{[\s\S]*\}""").find(responseText)
        return JSONObject(jsonMatch?.value ?: responseText)
    }

    private fun parseCommandResponse(responseText: String): AIResult {
        val trimmed = responseText.trim()
        if (!trimmed.startsWith("{")) return AIResult(success = true, message = trimmed)
        return try {
            val json = extractJsonFromResponse(responseText)
            when (json.optString("action", "chat")) {
                "add_expense" -> {
                    val descripcion = json.optString("descripcion", json.optString("concepto", ""))
                    val cantidad = json.optDouble("cantidad", 1.0)
                    val precioUnitario = json.optDouble("precio_unitario", 0.0)
                    val total = json.optDouble("total", json.optDouble("monto", cantidad * precioUnitario))
                    val moneda = json.optString("moneda").ifBlank { "EUR" }
                    val invoice = Invoice(
                        fecha = parseDate(json.optString("fecha", "")),
                        proveedor = descripcion,
                        tipo = InvoiceType.GASTO,
                        categoria = TransactionCategories.canonicalExpenseCategory(json.optString("categoria")),
                        moneda = moneda,
                        total = total
                    )
                    val product = Product(invoiceId = 0, descripcion = descripcion, cantidad = cantidad, precioUnitario = precioUnitario, subtotal = total)
                    AIResult(success = true, message = "Gasto agregado: $descripcion - $total $moneda", invoice = invoice, products = listOf(product))
                }
                "add_income" -> {
                    val concepto = json.optString("concepto", json.optString("descripcion", ""))
                    val totalDevengado = json.optDouble("total_devengado", 0.0)
                    val totalNeto = json.optDouble("total_neto", 0.0)
                    val monto = json.optDouble("monto", if (totalNeto > 0) totalNeto else totalDevengado)
                    val moneda = json.optString("moneda").ifBlank { "EUR" }
                    val income = Income(
                        fecha = parseDate(json.optString("fecha", "")),
                        concepto = concepto,
                        monto = monto,
                        totalDevengado = if (totalDevengado > 0) totalDevengado else monto,
                        totalNeto = if (totalNeto > 0) totalNeto else monto,
                        moneda = moneda,
                        fuente = json.optString("fuente"),
                        categoria = TransactionCategories.canonicalIncomeCategory(json.optString("categoria"))
                    )
                    val displayMonto = if (totalDevengado > 0 && totalNeto > 0) {
                        "Devengado: $totalDevengado $moneda / Neto: $totalNeto $moneda"
                    } else {
                        "$monto $moneda"
                    }
                    AIResult(success = true, message = "Ingreso agregado: $concepto - $displayMonto", income = income)
                }
                "query" -> AIResult(success = true, message = "Consulta procesada", queryResult = json.toString())
                "chat" -> AIResult(success = true, message = json.optString("response", ""))
                else -> AIResult(success = true, message = trimmed)
            }
        } catch (error: Exception) {
            AIResult(success = false, message = "No se pudo interpretar la respuesta: ${error.message}")
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input: InputStream -> BitmapFactory.decodeStream(input, null, bounds) }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION || bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { input: InputStream -> BitmapFactory.decodeStream(input, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AIService"
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val IMAGE_COMPRESSION_QUALITY = 90
        private const val MIME_TYPE_JPEG = "image/jpeg"
        private const val MIME_TYPE_PNG = "image/png"
        private const val ROLE_USER = "user"
        private const val ROLE_MODEL = "model"
        const val SETTINGS_PATH = "Configuración > IA"
        const val NO_API_KEY_MESSAGE =
            "Aún no has configurado tu API key de Gemini. Ve a $SETTINGS_PATH para añadir la tuya (es gratis en Google AI Studio)."
        private val UNIVERSAL_OCR_PROMPT = """
            Eres un experto en contabilidad internacional. Analiza el documento
            (factura, ticket, recibo o nómina) y devuelve SOLO un JSON válido,
            sin markdown ni comentarios. Todos los importes como NÚMEROS.

            PASO 1 — DETECTA EL PAÍS Y MONEDA automáticamente del documento:
            - "pais": código ISO 3166 de 2 letras (ES, MX, AR, CO, CL, PE, US, etc.).
              Basándote en: moneda mostrada, formato del NIF/RFC/CUIT/RUT, idioma,
              estructura del documento. Si no puedes determinarlo, usa "XX".
            - "moneda": código ISO 4217 (EUR, MXN, ARS, COP, CLP, PEN, USD, etc.).
              Detecta del símbolo (€, $, ₱) o texto del documento. NO asumas EUR.
            - "fecha": formato YYYY-MM-DD.

            PASO 2 — CLASIFICA el documento en EXACTAMENTE uno:
            - "nomina": recibo salarial. Palabras clave: nómina, salario, sueldo,
              devengado, líquido, percepciones, retención.
            - "factura_recibida": factura/ticket de compra o gasto.
            - "factura_emitida": factura de venta o servicio prestado.
            - "ticket": recibo simplificado sin identificación fiscal.
            - "recibo": otro documento de pago.

            REGLA CRÍTICA: en una NÓMINA NO devuelvas "proveedor" ni "total".
            Usa "empresa" + "devengado"/"liquido". Confundir una nómina con una
            factura produce un gasto erróneo que rompe la contabilidad.

            PASO 3 — EXTRAE LOS CAMPOS. Para nómina usa campos de salario:
              "empresa":"...", "devengado":0.0 (bruto), "liquido":0.0 (neto),
              "categoria":"Nómina",
              "retencion_irpf":0.0 (% de retención aplicado),
              "base_cotizacion":0.0, "seguridad_social":0.0

            Para facturas/tickets/recibos usa:
              "proveedor":"...", "categoria":"...",
              "nif_emisor":"...", "nif_receptor":"...",
              "base_imponible":0.0, "tipo_iva":0.0, "cuota_iva":0.0,
              "retencion_irpf":0.0, "total":0.0,
              "productos":[{"descripcion":"","cantidad":1.0,
                "precio_unitario":0.0,"subtotal":0.0,"iva_percent":0.0}]

            SOBRE EL IVA/IMPUESTO — lee el valor del documento, NO lo asumas:
            Cada país tiene tasas distintas:
            - España: IVA 0/4/10/21%
            - México: IVA 0/8/16%
            - Argentina: IVA 0/10.5/21/27%
            - Colombia: IVA 0/5/19%
            - Chile: IVA 19%
            - Perú: IGV 18%
            - Ecuador: IVA general 15% (además de tarifas 0% y 5% según el bien)
            Si el documento no muestra el IVA, pon 0.

            SOBRE LA IDENTIFICACIÓN FISCAL — detecta el formato del país:
            - España: NIF (8 números + letra)
            - México: RFC (4 letras + 6 números + 3 caracteres)
            - Argentina: CUIT (11 dígitos con guiones)
            - Chile: RUT (números + guión + dígito verificador)
            - Colombia: NIT (números + guión + dígito)
            - Perú: RUC (11 dígitos)

            EJEMPLO — Factura de México:
            {"tipo_documento":"factura_recibida","pais":"MX","moneda":"MXN",
             "categoria":"Alimentación",
             "fecha":"2026-06-15","proveedor":"OXXO S.A. DE C.V.",
             "nif_emisor":"OOXX840101AB1","total":58.00,
             "base_imponible":50.00,"tipo_iva":16.0,"cuota_iva":8.00,
             "retencion_irpf":0.0,
             "productos":[{"descripcion":"Café","cantidad":1.0,
             "precio_unitario":30.00,"subtotal":30.00,"iva_percent":16.0}]}

            EJEMPLO — Nómina de España:
            {"tipo_documento":"nomina","pais":"ES","moneda":"EUR","categoria":"Nómina",
             "fecha":"2026-06-30","empresa":"ACME S.L.",
             "devengado":1567.54,"liquido":1212.30,"retencion_irpf":15.0,
             "base_cotizacion":1313.46,"seguridad_social":105.90,
             "nif_emisor":"B12345678"}

            EJEMPLO — Factura de Argentina:
            {"tipo_documento":"factura_recibida","pais":"AR","moneda":"ARS",
             "categoria":"Alimentación",
             "fecha":"2026-06-20","proveedor":"Supermercado COTO S.A.",
             "nif_emisor":"30-12345678-9","total":15450.00,
             "base_imponible":12768.60,"tipo_iva":21.0,"cuota_iva":2681.40,
             "retencion_irpf":0.0}

            REGLAS:
            - Si un dato no es legible, usa null.
            - Devuelve SIEMPRE "tipo_documento", "pais" y "moneda".
            - Base + cuota IVA deben cuadrar con el total.
            - Los precios unitarios vienen con IVA incluido en el documento.
        """.trimIndent()
        const val FREE_MAX_HISTORY_TURNS = 3
        const val PREMIUM_MAX_HISTORY_TURNS = 10
        private val NOMINA_KEYWORDS = listOf(
            "nómina", "nomina", "salario", "sueldo", "devengado", "líquido a percibir", "liquido a percibir",
            "percepciones", "deducciones", "base de cotización", "cotización", "total devengado", "total a percibir", "seguridad social"
        )
    }
}
