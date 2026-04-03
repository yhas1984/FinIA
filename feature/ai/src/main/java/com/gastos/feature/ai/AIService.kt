package com.gastos.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.edge.litertlm.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

enum class AIEngine {
    GEMINI_API,
    GEMMA_LOCAL
}

data class AIResult(
    val success: Boolean,
    val message: String,
    val invoice: Invoice? = null,
    val products: List<Product> = emptyList(),
    val queryResult: String? = null
)

data class GemmaModelState(
    val isAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val modelSize: String = "",
    val error: String? = null
)

// Model URLs from HuggingFace LiteRT Community
private const val GEMMA_MODEL_URL = "https://huggingface.co/litert-community/Gemma3-4B-IT/resolve/main/Gemma3-4B-IT.litertlm"
private const val MODEL_FILE_NAME = "Gemma3-4B-IT.litertlm"
private const val MIN_MODEL_SIZE = 1000000000L // ~1GB minimum for 4B model

@Singleton
class AIService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var geminiModel: GenerativeModel? = null
    private var currentEngine: AIEngine = AIEngine.GEMINI_API
    private var engine: Engine? = null
    private var isGemmaReady = false

    private val _engineState = MutableStateFlow(AIEngine.GEMINI_API)
    val engineState: StateFlow<AIEngine> = _engineState.asStateFlow()

    private val _gemmaModelState = MutableStateFlow(GemmaModelState())
    val gemmaModelState: StateFlow<GemmaModelState> = _gemmaModelState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    fun setEngine(engineType: AIEngine, apiKey: String? = null) {
        currentEngine = engineType
        _engineState.value = engineType

        if (engineType == AIEngine.GEMINI_API && apiKey != null) {
            geminiModel = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey
            )
        }
    }

    suspend fun checkGemmaStatus(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()
            val modelFile = File(modelDir, MODEL_FILE_NAME)

            if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
                _gemmaModelState.value = GemmaModelState(
                    isAvailable = isGemmaReady,
                    modelSize = "Gemma 3 4B IT (${String.format("%.2f", modelFile.length() / 1024.0 / 1024.0 / 1024.0)} GB)"
                )
                Result.success("Modelo descargado")
            } else {
                _gemmaModelState.value = GemmaModelState(
                    isAvailable = false,
                    modelSize = "Gemma 3 4B IT (~4.1 GB)"
                )
                Result.success("Modelo disponible para descargar")
            }
        } catch (e: Exception) {
            _gemmaModelState.value = GemmaModelState(
                isAvailable = false,
                error = "Error: ${e.message}"
            )
            Result.failure(e)
        }
    }

    suspend fun downloadGemmaModel(hfToken: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()
            val modelFile = File(modelDir, MODEL_FILE_NAME)

            if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
                return@withContext Result.success("Modelo ya descargado")
            }

            if (hfToken.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Se requiere un token de HuggingFace (HF_TOKEN) para descargar Gemma 3."))
            }

            _gemmaModelState.value = _gemmaModelState.value.copy(
                isDownloading = true,
                downloadProgress = 0f,
                error = null
            )

            downloadModel(modelFile, hfToken)

            if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
                _gemmaModelState.value = GemmaModelState(
                    isAvailable = false,
                    isDownloading = false,
                    modelSize = "Gemma 3 4B IT (${String.format("%.2f", modelFile.length() / 1024.0 / 1024.0 / 1024.0)} GB)"
                )
                Result.success("Modelo descargado correctamente")
            } else {
                modelFile.delete()
                Result.failure(Exception("Descarga incompleta o token inválido"))
            }
        } catch (e: Exception) {
            Log.e("AIService", "Error downloading model", e)
            _gemmaModelState.value = _gemmaModelState.value.copy(
                isDownloading = false,
                error = "Error: ${e.message}"
            )
            Result.failure(e)
        }
    }

    private fun downloadModel(destFile: File, hfToken: String) {
        try {
            val url = URL(GEMMA_MODEL_URL)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 60000
            connection.readTimeout = 600000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Authorization", "Bearer $hfToken")
            connection.instanceFollowRedirects = true
            
            val responseCode = connection.responseCode
            if (responseCode == 401 || responseCode == 403) {
                throw Exception("Error $responseCode: No autorizado. Verifica tu Token de HuggingFace y acepta la licencia de Gemma 3.")
            } else if (responseCode != 200 && responseCode != 302) {
                throw Exception("Error de red: $responseCode")
            }

            val totalSize = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var lastUpdate = System.currentTimeMillis()
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = System.currentTimeMillis()
                        if (totalSize > 0 && now - lastUpdate > 500) {
                            _downloadProgress.value = downloadedBytes.toFloat() / totalSize
                            _gemmaModelState.value = _gemmaModelState.value.copy(
                                downloadProgress = downloadedBytes.toFloat() / totalSize
                            )
                            lastUpdate = now
                        }
                    }
                }
            }
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
    }

    private fun initGemmaEngine(): Engine? {
        return try {
            val modelDir = File(context.filesDir, "models")
            val modelFile = File(modelDir, MODEL_FILE_NAME)
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE) return null

            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )

            val eng = Engine(engineConfig)
            eng.initialize()
            isGemmaReady = true
            eng
        } catch (e: Exception) {
            Log.e("AIService", "Error initializing Gemma engine", e)
            null
        }
    }

    fun isGemmaAvailable(): Boolean = isGemmaReady

    suspend fun processInvoiceFromImage(imageUri: Uri): AIResult {
        return when (currentEngine) {
            AIEngine.GEMINI_API -> processInvoiceWithGemini(imageUri)
            AIEngine.GEMMA_LOCAL -> AIResult(
                success = false,
                message = "Gemma 3 no soporta OCR de imágenes. Usa Gemini API para escanear facturas."
            )
        }
    }

    suspend fun processCommand(command: String): AIResult {
        return when (currentEngine) {
            AIEngine.GEMINI_API -> processCommandWithGemini(command)
            AIEngine.GEMMA_LOCAL -> processCommandWithGemma(command)
        }
    }

    suspend fun queryData(query: String): AIResult {
        return when (currentEngine) {
            AIEngine.GEMINI_API -> queryDataWithGemini(query)
            AIEngine.GEMMA_LOCAL -> queryDataWithGemma(query)
        }
    }

    private suspend fun processInvoiceWithGemini(imageUri: Uri): AIResult {
        return try {
            val model = geminiModel ?: return AIResult(
                success = false,
                message = "Gemini API no configurada. Ve a Configuración > IA y añade tu API key."
            )

            val bitmap = uriToBitmap(imageUri)
            if (bitmap == null) {
                return AIResult(success = false, message = "Error al cargar la imagen")
            }

            val prompt = """
                Analiza esta factura/recibo y extrae la siguiente información en formato JSON:
                {
                    "proveedor": "nombre del proveedor",
                    "fecha": "YYYY-MM-DD",
                    "total": número_decimal,
                    "moneda": "código moneda (EUR, USD, etc.)",
                    "iva_percent": porcentaje_iva,
                    "nif_emisor": "NIF/CIF del emisor si visible",
                    "productos": [
                        {
                            "descripcion": "nombre del producto",
                            "cantidad": número,
                            "precio_unitario": número_decimal,
                            "subtotal": número_decimal,
                            "iva_percent": porcentaje
                        }
                    ]
                }
                Si no puedes extraer algún campo, usa null. Solo devuelve el JSON, sin texto adicional.
            """.trimIndent()

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            val responseText = response.text ?: ""
            parseInvoiceResponse(responseText, imageUri.toString())

        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            val friendlyMsg = when {
                errorMsg.contains("clipboard", ignoreCase = true) || errorMsg.contains("image input", ignoreCase = true) ->
                    "Error de API de Gemini: la clave no es válida. Revisa tu API key en Configuración > IA."
                errorMsg.contains("API key", ignoreCase = true) || errorMsg.contains("api_key", ignoreCase = true) ->
                    "API key de Gemini no válida. Ve a Configuración > IA para configurarla."
                else -> "Error al procesar la imagen: ${e.message}"
            }
            AIResult(success = false, message = friendlyMsg)
        }
    }

    private suspend fun processCommandWithGemini(command: String): AIResult {
        return try {
            val model = geminiModel ?: return AIResult(
                success = false,
                message = "Gemini API no configurada."
            )

            val prompt = """
                Eres FinAI, un asistente financiero. Analiza la consulta y devuelve EXACTAMENTE un JSON con "action".

                PRIORIDAD DE CLASIFICACIÓN (en orden):

                1. CONSULTA FINANCIERA (query): Si pregunta sobre gastos, ingresos, balance, totales, cuánto gastó, cuánto tiene, etc.
                   → {"action":"query","query_type":"gastos|ingresos|balance","periodo":"hoy|semana|mes|año","categoria":null,"item":null}

                2. REGISTRAR GASTO (add_expense): Si dice que gastó, compró, pagó algo.
                   → {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0,"total":0,"moneda":"EUR","fecha":"hoy","categoria":"texto"}

                3. REGISTRAR INGRESO (add_income): Si menciona nómina, salario, cobro, ingreso que recibió.
                   → {"action":"add_income","concepto":"texto","total_devengado":0,"total_neto":0,"monto":0,"moneda":"EUR","fecha":"hoy","fuente":"texto"}

                4. CONVERSACIÓN GENERAL (chat): Saludos, despedidas, preguntas sobre conceptos financieros, qué puedes hacer.
                   → {"action":"chat","response":"tu respuesta"}

                EJEMPLOS CLAVE:
                "¿cuánto he gastado este mes?" → query
                "¿cuál es mi balance?" → query
                "gasté 50 en comida" → add_expense
                "nómina de 2500" → add_income
                "¿qué es el IVA?" → chat
                "hola" → chat

                Solo JSON.
            """.trimIndent()

            val response = model.generateContent(content { text(prompt) })
            val responseText = response.text ?: ""
            parseCommandResponse(responseText)

        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            val friendlyMsg = when {
                errorMsg.contains("clipboard", ignoreCase = true) || errorMsg.contains("image input", ignoreCase = true) ->
                    "Error de API de Gemini: la clave no es válida. Revisa tu API key en Configuración > IA."
                errorMsg.contains("API key", ignoreCase = true) || errorMsg.contains("api_key", ignoreCase = true) ->
                    "API key de Gemini no válida. Ve a Configuración > IA para configurarla correctamente."
                errorMsg.contains("quota", ignoreCase = true) || errorMsg.contains("rate limit", ignoreCase = true) ->
                    "Límite de uso de Gemini API alcanzado. Intenta más tarde o cambia a Gemma 3 Local."
                else -> "Error al procesar el comando: ${e.message}"
            }
            AIResult(success = false, message = friendlyMsg)
        }
    }

    private suspend fun queryDataWithGemini(query: String): AIResult {
        return try {
            val model = geminiModel ?: return AIResult(
                success = false,
                message = "Gemini API no configurada. Ve a Configuración > IA."
            )

            val prompt = """
                Procesa esta consulta financiera y extrae los parámetros en JSON:
                
                Consulta: "$query"
                
                {
                    "query_type": "gastos|ingresos|balance|categoria",
                    "periodo": "hoy|semana|mes|año",
                    "categoria": "categoría si mencionada",
                    "item": "item específico si mencionado"
                }
                
                Solo devuelve el JSON.
            """.trimIndent()

            val response = model.generateContent(prompt)
            val responseText = response.text ?: ""
            
            AIResult(success = true, message = "Consulta procesada", queryResult = responseText)

        } catch (e: Exception) {
            AIResult(success = false, message = "Error al procesar la consulta: ${e.message}")
        }
    }

    private suspend fun processCommandWithGemma(command: String): AIResult {
        return try {
            if (!isGemmaReady) {
                val modelDir = File(context.filesDir, "models")
                val modelFile = File(modelDir, MODEL_FILE_NAME)
                if (!modelFile.exists() || modelFile.length() < 100000000) {
                    return AIResult(success = false, message = "Gemma 3 no descargado. Ve a Configuración > IA para descargarlo.")
                }

                val eng = initGemmaEngine()
                if (eng == null) {
                    return AIResult(success = false, message = "Error al cargar Gemma 3. Verifica que el modelo esté descargado correctamente.")
                }
            }

            val eng = engine ?: return AIResult(success = false, message = "Gemma 3 no disponible")

            val today = java.time.LocalDate.now().toString()
            val prompt = """
                Eres FinAI, un asistente financiero. Hoy es $today.

                Analiza la consulta y devuelve EXACTAMENTE un JSON con "action".

                PRIORIDAD:
                1. Si pregunta sobre gastos/ingresos/balance → query
                2. Si quiere registrar un gasto → add_expense
                3. Si quiere registrar un ingreso → add_income
                4. Si es conversación general → chat

                Comando: "$command"

                Si es consulta: {"action":"query","query_type":"gastos|ingresos|balance","periodo":"hoy|semana|mes|año","categoria":null,"item":null}
                Si es gasto: {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0,"total":0,"moneda":"EUR","fecha":"$today","categoria":"texto"}
                Si es ingreso: {"action":"add_income","concepto":"texto","total_devengado":0,"total_neto":0,"monto":0,"moneda":"EUR","fecha":"$today","fuente":"texto"}
                Si es chat: {"action":"chat","response":"tu respuesta"}

                Solo JSON, sin texto adicional.
            """.trimIndent()

            val conversation = eng.createConversation()
            var fullResponse = ""

            conversation.sendMessageAsync(prompt)
                .catch { e ->
                    Log.e("AIService", "Gemma streaming error", e)
                }
                .collect { message ->
                    fullResponse += message.toString()
                }

            conversation.close()

            if (fullResponse.isBlank()) {
                return AIResult(success = false, message = "Gemma 3 no respondió")
            }

            parseCommandResponse(fullResponse)

        } catch (e: Exception) {
            Log.e("AIService", "Error with Gemma", e)
            AIResult(success = false, message = "Error con Gemma 3: ${e.message}")
        }
    }

    private suspend fun queryDataWithGemma(query: String): AIResult {
        return try {
            if (!isGemmaReady) {
                val modelDir = File(context.filesDir, "models")
                val modelFile = File(modelDir, MODEL_FILE_NAME)
                if (!modelFile.exists() || modelFile.length() < 100000000) {
                    return AIResult(success = false, message = "Gemma 3 no descargado. Ve a Configuración > IA.")
                }
                val eng = initGemmaEngine()
                if (eng == null) {
                    return AIResult(success = false, message = "Error al cargar Gemma 3.")
                }
            }

            val eng = engine ?: return AIResult(success = false, message = "Gemma 3 no disponible")

            val prompt = """
                Procesa esta consulta y extrae en JSON: {"query_type":"gastos|ingresos|balance","periodo":"hoy|semana|mes","categoria":"texto","item":"texto"}
                Consulta: "$query"
                Solo devuelve el JSON.
            """.trimIndent()

            val conversation = eng.createConversation()
            var fullResponse = ""

            conversation.sendMessageAsync(prompt)
                .catch { e ->
                    Log.e("AIService", "Gemma streaming error", e)
                }
                .collect { message ->
                    fullResponse += message.toString()
                }

            conversation.close()

            if (fullResponse.isBlank()) {
                return AIResult(success = false, message = "Gemma 3 no respondió")
            }

            AIResult(success = true, message = "Consulta procesada", queryResult = fullResponse)

        } catch (e: Exception) {
            Log.e("AIService", "Error with Gemma", e)
            AIResult(success = false, message = "Error con Gemma 3: ${e.message}")
        }
    }

    private fun parseInvoiceResponse(responseText: String, imageUri: String): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)
            
            val proveedor = json.optString("proveedor", "Desconocido")
            val fechaStr = json.optString("fecha", "")
            val total = json.optDouble("total", 0.0)
            val moneda = json.optString("moneda", "EUR")
            val ivaPercent = json.optDouble("iva_percent", 21.0)
            val nifEmisor = json.optString("nif_emisor", null)
            val tipoStr = json.optString("tipo", "").lowercase()
            
            val rawLower = responseText.lowercase()
            val incomeKeywords = listOf("nómina", "nomina", "salario", "sueldo", "ingreso", "paga", "devengado", "neto", "bruto", "irpf", "seguridad social", "deducciones", "retenciones")
            val isIncome = incomeKeywords.any { rawLower.contains(it) } || tipoStr.contains("ingreso") || tipoStr.contains("income") || tipoStr.contains("nomina")
            
            val fecha = try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .parse(fechaStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            val invoice = Invoice(
                fecha = fecha,
                proveedor = proveedor,
                tipo = if (isIncome) InvoiceType.INGRESO else InvoiceType.GASTO,
                moneda = moneda,
                total = total,
                ivaPercent = ivaPercent,
                nifEmisor = nifEmisor,
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

            val tipoLabel = if (isIncome) "Ingreso" else "Factura"
            AIResult(success = true, message = "$tipoLabel procesada correctamente", invoice = invoice, products = products)

        } catch (e: Exception) {
            AIResult(success = false, message = "Error al parsear la respuesta: ${e.message}")
        }
    }

    private fun extractJsonFromResponse(responseText: String): JSONObject {
        val jsonMatch = Regex("""\{[\s\S]*\}""").find(responseText)
        val jsonString = jsonMatch?.value ?: responseText
        return JSONObject(jsonString)
    }

    private fun parseCommandResponse(responseText: String): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)
            var action = json.optString("action", "unknown")
            val rawText = responseText.lowercase()

            val incomeKeywords = listOf("nómina", "nomina", "salario", "sueldo", "cobré", "cobre", "recibí", "recibi", "ingreso", "venta", "cobro", "paga", "devolución", "devolucion", "dividendo", "comisión", "comision", "bono", "prima", "aguinaldo")
            val expenseKeywords = listOf("gasté", "gaste", "compré", "compre", "pagué", "pague", "gasto", "compra", "multa", "suscripción", "suscripcion")

            val isIncomeKeyword = incomeKeywords.any { rawText.contains(it) }
            val isExpenseKeyword = expenseKeywords.any { rawText.contains(it) }

            if (action == "add_expense" && isIncomeKeyword && !isExpenseKeyword) {
                action = "add_income"
            } else if (action == "add_income" && isExpenseKeyword && !isIncomeKeyword) {
                action = "add_expense"
            }

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
                    val fuente = json.optString("fuente", null)
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

                    AIResult(success = true, message = "Ingreso agregado: $concepto - $displayMonto", queryResult = "INCOME:${income.concepto}:${income.monto}:${income.moneda}:${income.fecha}:${income.fuente}")
                }
                "query" -> {
                    AIResult(success = true, message = "Consulta procesada", queryResult = responseText)
                }
                "chat" -> {
                    val chatResponse = json.optString("response", "")
                    AIResult(success = true, message = chatResponse, queryResult = "CHAT:$chatResponse")
                }
                else -> {
                    AIResult(success = false, message = "Comando no reconocido: $action")
                }
            }

        } catch (e: Exception) {
            AIResult(success = false, message = "Error al parsear el comando: ${e.message}")
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
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    fun cleanup() {
        engine?.close()
        engine = null
        isGemmaReady = false
    }
}
