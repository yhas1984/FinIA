package com.gastos.feature.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.domain.model.SUPPORTED_CURRENCIES
import com.gastos.domain.model.TransactionCategories
import com.gastos.extension.SafeLog
import com.gastos.repository.CountryFiscalConfigRepository
import com.gastos.repository.CurrencyPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt
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

internal fun calculateDecodeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxDimension > 0) { "Maximum image dimension must be positive" }
    var sampleSize = 1
    val targetDimension = maxDimension.toLong() * DECODE_OVERSAMPLE_FACTOR
    val sourceDimension = maxOf(width.toLong(), height.toLong())
    while (sourceDimension / sampleSize > targetDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private const val DECODE_OVERSAMPLE_FACTOR = 2

@Singleton
class AIService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fiscalConfigRepository: CountryFiscalConfigRepository,
    private val geminiRestClient: GeminiRestClient,
    private val currencyPreference: CurrencyPreference
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
                parseCommandResponse(responseText, command)
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

    fun parseStreamingResult(responseText: String, originalCommand: String): AIResult =
        parseCommandResponse(responseText, originalCommand)

    suspend fun processInvoiceFromImage(imageUri: Uri): AIResult {
        if (!isConfigured()) return notConfiguredResult()
        return try {
            val preparationStartedAt = SystemClock.elapsedRealtime()
            val inlineImage = withContext(Dispatchers.IO) {
                uriToBitmap(imageUri)?.let { bitmap ->
                    try {
                        bitmap.toInlineImagePart()
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } ?: return AIResult(success = false, message = "Error al cargar la imagen")
            val preparationTimeMs = SystemClock.elapsedRealtime() - preparationStartedAt
            val fiscalConfig = currentFiscalConfig()
            val defaultCurrency = getDefaultCurrency()
            val prompt = buildOcrUserPrompt(fiscalConfig, defaultCurrency)
            val networkStartedAt = SystemClock.elapsedRealtime()
            val raw = geminiRestClient.generateContent(
                GeminiGenerateRequest(
                    apiKey = currentApiKey,
                    systemInstruction = buildOcrSystemPrompt(systemInstructions),
                    contents = listOf(
                        GeminiContent(
                            role = ROLE_USER,
                            textParts = listOf(GeminiTextPart(prompt)),
                            inlineDataParts = listOf(inlineImage)
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(
                        thinkingLevel = OCR_THINKING_LEVEL,
                        responseMimeType = OCR_RESPONSE_MIME_TYPE,
                        responseSchema = buildOcrResponseSchema(),
                        mediaResolution = OCR_MEDIA_RESOLUTION
                    )
                )
            )
            val networkTimeMs = SystemClock.elapsedRealtime() - networkStartedAt
            val parsingStartedAt = SystemClock.elapsedRealtime()
            val result = parseInvoiceResponse(raw, imageUri.toString(), currentFiscalCountry, defaultCurrency)
            val parsingTimeMs = SystemClock.elapsedRealtime() - parsingStartedAt
            SafeLog.d(
                TAG,
                "OCR timings: prepare=${preparationTimeMs}ms network=${networkTimeMs}ms " +
                    "parse=${parsingTimeMs}ms payload=${inlineImage.byteCount / 1024}KB"
            )
            result
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
        val bitmapToCompress = if (hasAlpha()) toOpaqueBitmap() else this
        val output = ByteArrayOutputStream()
        return try {
            check(bitmapToCompress.compress(CompressFormat.JPEG, IMAGE_COMPRESSION_QUALITY, output)) {
                "No se pudo comprimir la imagen"
            }
            val bytes = output.toByteArray()
            GeminiInlineDataPart(
                mimeType = MIME_TYPE_JPEG,
                data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                byteCount = bytes.size
            )
        } finally {
            if (bitmapToCompress !== this && !bitmapToCompress.isRecycled) bitmapToCompress.recycle()
        }
    }

    private fun Bitmap.toOpaqueBitmap(): Bitmap {
        val opaqueBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(opaqueBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@toOpaqueBitmap, 0f, 0f, null)
        }
        return opaqueBitmap
    }

    private fun buildSystemPrompt(userInstructions: String): String {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .format(java.util.Date())
        val defaultCurrency = getDefaultCurrency()
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
                {"action":"query","query_type":"gastos|ingresos|balance|productos|productos_por_comercio","periodo":"hoy|semana|mes|año","categoria":null,"subcategoria":null,"proveedor":null,"item":null,"match_mode":"exact|group|auto|null"}

               Diferencia siempre estos filtros:
                - COMERCIO/PROVEEDOR: Mercadona, Lidl, Amazon, Repsol, etc. Usa
                  query_type="gastos", proveedor="nombre", categoria=null e item=null.
                - CATEGORÍA: Alimentación, Transporte, Vivienda, Ocio, etc. Usa
                  categoria="nombre" y proveedor=null.
                - SUBCATEGORÍA: Supermercado, Restaurantes, Combustible, Farmacia, Internet,
                  Salario base, etc. Usa subcategoria="nombre" y no la confundas con la categoría.
                  Si el usuario menciona una subcategoría junto a una categoría, conserva ambas.
                - PRODUCTO: café, agua, pan, gasolina, etc. Usa query_type="productos"
                  e item="nombre". Una tienda o empresa NUNCA es un producto.
               - LISTADO DE PRODUCTOS POR COMERCIO: si el usuario pregunta qué productos
                 compró, qué se ha comprado o qué contiene un ticket, usa
                 query_type="productos_por_comercio" y rellena el proveedor si lo menciona.
                 Ejemplos:
                 * "¿qué he comprado en Consum?" => proveedor="Consum", query_type="productos_por_comercio", item=null.
                 * "¿qué productos he comprado?" => proveedor=null, query_type="productos_por_comercio", item=null.
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
                {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0.0,"total":0.0,"moneda":"$defaultCurrency","fecha":"$today","categoria":"texto","subcategoria":"texto"}
                - Si el usuario no menciona otra moneda, usa $defaultCurrency.
                - Usa una categoría predeterminada de gasto si encaja claramente.
                - Si el usuario menciona una categoría personalizada explícita, consérvala.
                - La subcategoría es OPCIONAL: úsala solo cuando el usuario mencione un detalle concreto
                  (por ejemplo, "en el supermercado" -> subcategoria "Supermercado" bajo Alimentación).
                  Si no estás seguro, omítela.

            3. REGISTRAR INGRESO: si menciona nómina, salario, cobro o ingreso recibido:
                {"action":"add_income","concepto":"texto","total_devengado":0.0,"total_neto":0.0,"monto":0.0,"moneda":"$defaultCurrency","fecha":"$today","fuente":"texto","categoria":"texto","subcategoria":"texto"}
                - Si el usuario no menciona otra moneda, usa $defaultCurrency.
                - Usa una categoría predeterminada de ingreso si encaja claramente.
                - Si es una nómina, la categoría por defecto es "Nómina".
                - La subcategoría es OPCIONAL: úsala solo cuando el usuario mencione un detalle concreto.
                  Si no estás seguro, omítela.

            4. CONVERSACIÓN GENERAL: saludos, agradecimientos, consejos financieros, dudas
               sobre conceptos (IVA, IRPF, ahorro, inversión), o cualquier otra cosa.
               EN ESTE CASO NO DEVUELVAS JSON: responde directamente con texto natural,
               conversacional y personalizado, evitando frases genéricas. Sin prefijos.
            $extraBlock
        """.trimIndent()
    }

    private fun buildOcrSystemPrompt(userInstructions: String): String {
        val extra = userInstructions.trim()
        val extraBlock = if (extra.isNotEmpty()) {
            "\n\nInstrucciones adicionales del usuario:\n$extra"
        } else {
            ""
        }
        return OCR_SYSTEM_PROMPT + extraBlock
    }

    private fun buildOcrUserPrompt(
        fiscalConfig: CountryFiscalConfig?,
        defaultCurrency: String
    ): String {
        val countryHint = fiscalConfig?.let {
            "Si el país no es legible, usa ${it.paisCodigo} (${it.nombrePais}) como respaldo. " +
                "Sus tipos habituales de ${it.nombreLeyFiscal} son ${it.ivaRates.joinToString()}%."
        }.orEmpty()
        return "Analiza toda la imagen adjunta, incluidos encabezado, pie, bloques fiscales y líneas de productos. " +
            "Amplía mentalmente el texto pequeño antes de extraerlo. $countryHint " +
            "Si la moneda no es legible, usa $defaultCurrency como respaldo. " +
            "Nunca sustituyas valores legibles por valores de respaldo y conserva literalmente los NIF."
    }

    private fun buildOcrResponseSchema(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("tipo_documento", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray().apply {
                    listOf("nomina", "factura_recibida", "factura_emitida", "ticket", "recibo").forEach(::put)
                })
            })
            put("pais", JSONObject().put("type", "STRING"))
            put("moneda", JSONObject().put("type", "STRING"))
            put("fecha", JSONObject().put("type", "STRING"))
            put("numero_factura", JSONObject().put("type", "STRING"))
            put("empresa", JSONObject().put("type", "STRING"))
            put("proveedor", JSONObject().put("type", "STRING"))
            put("categoria", JSONObject().put("type", "STRING"))
            put("subcategoria", JSONObject().put("type", "STRING"))
            put("nif_emisor", JSONObject().put("type", "STRING"))
            put("nif_receptor", JSONObject().put("type", "STRING"))
            put("base_imponible", JSONObject().apply {
                put("type", "NUMBER")
                put("nullable", true)
            })
            put("tipo_iva", JSONObject().put("type", "NUMBER"))
            put("cuota_iva", JSONObject().apply {
                put("type", "NUMBER")
                put("nullable", true)
            })
            put("retencion_irpf", JSONObject().put("type", "NUMBER"))
            put("total", JSONObject().put("type", "NUMBER"))
            listOf("devengado", "liquido", "base_cotizacion", "seguridad_social").forEach { key ->
                put(key, JSONObject().apply {
                    put("type", "NUMBER")
                    put("nullable", true)
                })
            }
            put("productos", JSONObject().apply {
                put("type", "ARRAY")
                put("items", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("descripcion", JSONObject().put("type", "STRING"))
                        put("cantidad", JSONObject().put("type", "NUMBER"))
                        put("precio_unitario", JSONObject().put("type", "NUMBER"))
                        put("subtotal", JSONObject().put("type", "NUMBER"))
                        put("iva_percent", JSONObject().put("type", "NUMBER"))
                    })
                    put("required", JSONArray().apply {
                        listOf("descripcion", "cantidad", "precio_unitario", "subtotal", "iva_percent")
                            .forEach(::put)
                    })
                })
            })
        })
        put("required", JSONArray().apply {
            listOf(
                "tipo_documento", "pais", "moneda", "fecha", "numero_factura", "empresa", "proveedor",
                "categoria", "subcategoria", "nif_emisor", "nif_receptor", "base_imponible", "tipo_iva",
                "cuota_iva", "retencion_irpf", "total", "devengado", "liquido", "base_cotizacion",
                "seguridad_social", "productos"
            ).forEach(::put)
        })
    }

    private fun queryExtractionPrompt(query: String): String = """
        Extrae los parámetros de esta consulta financiera y devuelve SOLO el JSON:
        {"query_type":"gastos|ingresos|balance|productos|productos_por_comercio","periodo":"hoy|semana|mes|año","categoria":"texto o null","subcategoria":"texto o null","proveedor":"texto o null","item":"texto o null","match_mode":"exact|group|auto|null"}

        Reglas:
        - comercio, tienda, supermercado, empresa o proveedor => proveedor, nunca item
        - categoría financiera (Alimentación, Transporte, etc.) => categoria
        - subcategoría concreta (Supermercado, Restaurantes, Combustible, Farmacia, Internet,
          Salario base, etc.) => subcategoria; no la subas a categoria
        - producto genérico (agua, café, leche, pan) => match_mode="group"
        - "solo", "únicamente", "exactamente" + descripción específica => match_mode="exact"
        - comercio y producto pueden combinarse: proveedor="Consum", item="agua", match_mode="group"
        - "qué he comprado", "qué compré" o preguntas de listado de productos
          sin item => query_type="productos_por_comercio" y proveedor si se nombra
        - si la consulta es sobre un producto, usa query_type="productos", nunca "balance"
        - "ganado", "ganancia", "beneficio", "neto" o "lo que me queda" => balance
        - "ingresado", "cobrado", "recibido", "salario" o "nómina" => ingresos

        Consulta: "$query"
    """.trimIndent()

    internal fun parseInvoiceResponse(
        responseText: String,
        imageUri: String,
        fiscalCountry: String,
        defaultCurrency: String
    ): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)
            val tipoDoc = json.optString("tipo_documento", "").lowercase()
            val rawLower = responseText.lowercase()
            val esNomina = tipoDoc == "nomina" ||
                (tipoDoc.isBlank() && NOMINA_KEYWORDS.any { rawLower.contains(it) })
            if (esNomina) {
                val empresa = readString(json, "empresa", "proveedor").ifBlank { "Nómina" }
                val moneda = resolveCurrency(json.optString("moneda"), defaultCurrency)
                val devengado = readDouble(json, "devengado", "total_devengado")
                val liquido = readDouble(json, "liquido", "neto", "total_neto")
                val total = readDouble(json, "total")
                val monto = when {
                    liquido > 0 -> liquido
                    devengado > 0 -> devengado
                    else -> total
                }
                if (monto <= 0) return AIResult(success = false, message = "No se pudo leer el importe de la nómina")
                val irpf = readDouble(json, "retencion_irpf")
                val fecha = parseDate(json.optString("fecha", ""))
                val concepto = "Nómina - $empresa"
                val subcategoria = readNullableString(json, "subcategoria")
                val income = Income(
                    fecha = fecha,
                    concepto = concepto,
                    monto = monto,
                    totalDevengado = if (devengado > 0) devengado else monto,
                    totalNeto = if (liquido > 0) liquido else monto,
                    moneda = moneda,
                    fuente = empresa,
                    categoria = TransactionCategories.canonicalIncomeCategory(readString(json, "categoria").ifBlank { "Nómina" }),
                    subcategoria = subcategoria,
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
            val proveedor = readString(
                json,
                "proveedor", "empresa", "razon_social", "razon social", "nombre", "name", "merchant", "comercio",
                "establecimiento", "vendedor", "supplier", "razon", "sociedad", "compañia", "compania"
            ).ifBlank { "Desconocido" }
            val total = readDouble(json, "total")
            val moneda = resolveCurrency(json.optString("moneda"), defaultCurrency)
            val ivaPercent = readDouble(json, "tipo_iva", "iva_percent")
            val irpfPercent = readDouble(json, "retencion_irpf")
            val numeroFactura = readNullableString(json, "numero_factura", "numeroFactura", "no_factura", "n_factura")
            val baseImponible = readNullableDouble(json, "base_imponible", "baseImponible")
            val cuotaIva = readNullableDouble(json, "cuota_iva", "cuotaIva", "iva_amount")
            val nifEmisor = readNullableString(json, "nif_emisor", "nifEmisor")
            val detectedPais = readString(json, "pais").ifBlank { fiscalCountry }
            val esIngresoFactura = tipoDoc.contains("emitida") || json.optString("tipo", "").lowercase() == "ingreso"
            val subcategoria = readNullableString(json, "subcategoria")
            val invoice = Invoice(
                fecha = parseDate(json.optString("fecha", "")),
                proveedor = proveedor,
                tipo = if (esIngresoFactura) InvoiceType.INGRESO else InvoiceType.GASTO,
                categoria = if (esIngresoFactura) {
                    TransactionCategories.canonicalIncomeCategory(readString(json, "categoria").ifBlank { "Ventas" })
                } else {
                    TransactionCategories.canonicalExpenseCategory(readString(json, "categoria").ifBlank { "Otros" })
                },
                subcategoria = subcategoria,
                moneda = moneda,
                total = total,
                numeroFactura = numeroFactura,
                baseImponible = baseImponible,
                cuotaIva = cuotaIva,
                ivaPercent = ivaPercent,
                irpfPercent = irpfPercent,
                paisCodigo = detectedPais,
                nifEmisor = nifEmisor,
                nifReceptor = readNullableString(json, "nif_receptor", "nifReceptor"),
                imagenUri = imageUri,
                ocrRawText = responseText
            )
            val productsArray = json.optJSONArray("productos")
            val products = mutableListOf<Product>()
            if (productsArray != null) {
                for (index in 0 until productsArray.length()) {
                    val productJson = productsArray.getJSONObject(index)
                    val descripcion = readNullableString(productJson, "descripcion") ?: continue
                    products.add(
                        Product(
                            invoiceId = 0,
                            descripcion = descripcion,
                            cantidad = readDouble(productJson, "cantidad").takeIf { it > 0.0 } ?: 1.0,
                            precioUnitario = readDouble(productJson, "precio_unitario"),
                            subtotal = readDouble(productJson, "subtotal"),
                            ivaPercent = readDouble(productJson, "iva_percent").takeIf { it > 0.0 } ?: ivaPercent
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

    private fun readString(json: JSONObject, vararg keys: String): String = keys.asSequence()
        .map { key -> json.optString(key, "").trim() }
        .firstOrNull(::isMeaningfulText)
        .orEmpty()

    private fun readNullableString(json: JSONObject, vararg keys: String): String? =
        readString(json, *keys).takeIf(String::isNotBlank)

    private fun readDouble(json: JSONObject, vararg keys: String): Double =
        readNullableDouble(json, *keys) ?: 0.0

    private fun readNullableDouble(json: JSONObject, vararg keys: String): Double? = keys.asSequence()
        .filter { key -> json.has(key) && !json.isNull(key) }
        .map { key -> json.optDouble(key, Double.NaN) }
        .firstOrNull(Double::isFinite)

    private fun isMeaningfulText(value: String): Boolean = value.isNotBlank() &&
        value.lowercase() !in MISSING_TEXT_VALUES && value !in setOf("-", "—", "N/A", "n/a")

    private fun parseCommandResponse(responseText: String, originalCommand: String): AIResult {
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
                    val moneda = resolveCommandCurrency(
                        rawCurrency = json.optString("moneda"),
                        defaultCurrency = getDefaultCurrency(),
                        originalCommand = originalCommand
                    )
                    val subcategoria = TransactionCategories.normalizeCategory(json.optString("subcategoria"))
                    val invoice = Invoice(
                        fecha = parseDate(json.optString("fecha", "")),
                        proveedor = descripcion,
                        tipo = InvoiceType.GASTO,
                        categoria = TransactionCategories.canonicalExpenseCategory(json.optString("categoria")),
                        subcategoria = subcategoria,
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
                    val moneda = resolveCommandCurrency(
                        rawCurrency = json.optString("moneda"),
                        defaultCurrency = getDefaultCurrency(),
                        originalCommand = originalCommand
                    )
                    val subcategoria = TransactionCategories.normalizeCategory(json.optString("subcategoria"))
                    val income = Income(
                        fecha = parseDate(json.optString("fecha", "")),
                        concepto = concepto,
                        monto = monto,
                        totalDevengado = if (totalDevengado > 0) totalDevengado else monto,
                        totalNeto = if (totalNeto > 0) totalNeto else monto,
                        moneda = moneda,
                        fuente = json.optString("fuente"),
                        categoria = TransactionCategories.canonicalIncomeCategory(json.optString("categoria")),
                        subcategoria = subcategoria
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

    private fun getDefaultCurrency(): String = resolveCurrency(
        rawCurrency = currencyPreference.defaultCurrency.value,
        defaultCurrency = "EUR"
    )

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input: InputStream -> BitmapFactory.decodeStream(input, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sampleSize = calculateDecodeSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null
            if (maxOf(bitmap.width, bitmap.height) <= MAX_IMAGE_DIMENSION) {
                bitmap
            } else {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
                val resized = bitmap.scale(
                    width = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    height = (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    filter = true
                )
                if (resized !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                resized
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AIService"
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val IMAGE_COMPRESSION_QUALITY = 88
        private const val MIME_TYPE_JPEG = "image/jpeg"
        private const val OCR_THINKING_LEVEL = "medium"
        private const val OCR_RESPONSE_MIME_TYPE = "application/json"
        private const val OCR_MEDIA_RESOLUTION = "MEDIA_RESOLUTION_HIGH"
        private const val ROLE_USER = "user"
        private const val ROLE_MODEL = "model"
        const val SETTINGS_PATH = "Configuración > IA"
        const val NO_API_KEY_MESSAGE =
            "Aún no has configurado tu API key de Gemini. Ve a $SETTINGS_PATH para añadir la tuya (es gratis en Google AI Studio)."
        private val OCR_SYSTEM_PROMPT = """
            Eres el extractor OCR contable de FinAI. Analiza una factura, ticket, recibo o nómina
            y devuelve exactamente un objeto JSON válido, sin markdown ni texto adicional.
            Devuelve SIEMPRE todas las claves definidas por el esquema. Para un texto ilegible usa
            "" y para un importe fiscal opcional ilegible usa null; nunca omitas claves ni inventes
            valores.
            Lee país, moneda y fecha directamente del documento; usa pais="XX" solo si no puedes
            determinar el país y conserva la fecha en formato YYYY-MM-DD.

            tipo_documento debe ser uno de: nomina, factura_recibida, factura_emitida, ticket o recibo.
            Para facturas, tickets y recibos extrae proveedor, numero_factura, categoria,
            subcategoria, nif_emisor, nif_receptor, base_imponible, tipo_iva, cuota_iva,
            retencion_irpf, total y productos. Copia los NIF carácter por carácter, incluyendo
            guiones. Cada producto contiene descripcion, cantidad, precio_unitario, subtotal e
            iva_percent. Si no aparece una línea de producto, productos debe ser [].

            La categoria de gasto debe ser una de: Alimentación, Vivienda, Transporte, Servicios,
            Salud, Educación, Ocio, Viajes, Impuestos, Negocio u Otros. Dedúcela por el comercio,
            el texto y los productos; usa Otros solo si no hay evidencia suficiente. La subcategoria
            debe reflejar el tipo de compra cuando se pueda inferir, por ejemplo Supermercado,
            Restaurantes, Combustible, Farmacia o Internet.

            Una nómina nunca es un gasto: usa empresa, devengado, liquido, retencion_irpf,
            base_cotizacion y seguridad_social. En campos que no correspondan al tipo de documento
            devuelve "" o null, pero no los omitas.

            Lee el IVA o impuesto mostrado, no lo asumas por el país. Los precios unitarios y
            subtotales deben conservar los valores legibles del documento. Verifica que base,
            impuestos y total sean coherentes antes de responder y no redondees los NIF ni los
            números de factura.
        """.trimIndent()
        const val FREE_MAX_HISTORY_TURNS = 3
        const val PREMIUM_MAX_HISTORY_TURNS = 10
        private val NOMINA_KEYWORDS = listOf(
            "nómina", "nomina", "salario", "sueldo", "devengado", "líquido a percibir", "liquido a percibir",
            "percepciones", "deducciones", "base de cotización", "cotización", "total devengado", "total a percibir", "seguridad social"
        )
        private val MISSING_TEXT_VALUES = setOf("null", "unknown", "desconocido", "n/a")
    }
}

internal fun resolveCurrency(rawCurrency: String?, defaultCurrency: String): String {
    val fallback = defaultCurrency.trim().uppercase().takeIf { it in SUPPORTED_CURRENCIES } ?: "EUR"
    val candidate = rawCurrency?.trim()?.uppercase().orEmpty()
    return candidate.takeUnless { it in MISSING_CURRENCY_VALUES } ?: fallback
}

internal fun resolveCommandCurrency(
    rawCurrency: String?,
    defaultCurrency: String,
    originalCommand: String
): String {
    val fallback = resolveCurrency(null, defaultCurrency)
    val candidate = resolveCurrency(rawCurrency, fallback)
    if (mentionsCurrency(originalCommand, candidate)) return candidate
    val mentionedCurrencies = (SUPPORTED_CURRENCIES + CURRENCY_ALIASES.keys)
        .distinct()
        .filter { mentionsCurrency(originalCommand, it) }
    return mentionedCurrencies.singleOrNull() ?: fallback
}

private fun mentionsCurrency(command: String, currency: String): Boolean {
    val codePattern = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(currency)}(?![\\p{L}\\p{N}])")
    if (codePattern.containsMatchIn(command)) return true
    val normalizedCommand = command.lowercase()
    return CURRENCY_ALIASES[currency].orEmpty().any { alias ->
        if (alias.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            normalizedCommand.contains(alias)
        } else {
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])")
                .containsMatchIn(normalizedCommand)
        }
    }
}

private val MISSING_CURRENCY_VALUES: Set<String> = setOf("", "NULL", "UNKNOWN", "XX")

private val CURRENCY_ALIASES: Map<String, List<String>> = mapOf(
    "EUR" to listOf("€", "euro", "euros"),
    "USD" to listOf("dólar", "dólares", "dolar", "dolares", "dollar", "dollars"),
    "MXN" to listOf("peso mexicano", "pesos mexicanos"),
    "ARS" to listOf("peso argentino", "pesos argentinos"),
    "COP" to listOf("peso colombiano", "pesos colombianos"),
    "CLP" to listOf("peso chileno", "pesos chilenos"),
    "PEN" to listOf("sol peruano", "soles peruanos", "s/"),
    "BOB" to listOf("boliviano", "bolivianos"),
    "GTQ" to listOf("quetzal", "quetzales"),
    "NIO" to listOf("córdoba", "córdobas", "cordoba", "cordobas"),
    "PYG" to listOf("guaraní", "guaraníes", "guarani", "guaranies", "₲"),
    "UYU" to listOf("peso uruguayo", "pesos uruguayos", "\$u"),
    "VES" to listOf("bolívar", "bolívares", "bolivar", "bolivares"),
    "GBP" to listOf("£", "libra", "libras", "libra esterlina", "libras esterlinas"),
    "BRL" to listOf("r$", "real brasileño", "reales brasileños"),
    "JPY" to listOf("¥", "yen", "yenes"),
    "CNY" to listOf("yuan", "yuanes"),
    "CHF" to listOf("franco suizo", "francos suizos")
)
