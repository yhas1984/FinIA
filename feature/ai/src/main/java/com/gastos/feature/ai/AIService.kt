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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.InputStream
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
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
private const val GEMMA_MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
private const val MIN_MODEL_SIZE = 500000000L // ~500MB minimum for 2B model
private const val GEMINI_IMAGE_MAX_SIDE_PX = 2048
/** OCR muy largo satura el contexto del modelo local y empeora el JSON. */
private const val GEMMA_OCR_MAX_CHARS = 7000
/** Reducir píxeles acelera ML Kit y suele bastar para el texto del ticket. */
private const val OCR_MAX_SIDE_PX = 2200
/** Caracteres del inicio del OCR (cabecera); el total suele estar al final. */
private const val GEMMA_OCR_HEAD_CHARS = 3200

/** Memoria de chat en el proceso: turnos usuario/asistente hasta cerrar la app. */
private const val MAX_SESSION_TURNS = 14
private const val MAX_SESSION_CONTEXT_CHARS = 12_000

/**
 * Gemma local necesita salida JSON estable: system prompt, poca temperatura y respuesta completa (sendMessage),
 * no streaming por chunks (menos corrupción y menos “contaminación” entre peticiones).
 */
private val gemmaStructuredConversationConfig: ConversationConfig
    get() = ConversationConfig(
        Contents.of(
            "Eres FinAI. Regla técnica: si la tarea es JSON, una sola línea, UTF-8, sin markdown ni ``` ni //. " +
                "Regla de tono: cuando el JSON lleve action \"chat\", el campo \"response\" debe sonar como una persona " +
                "en español (tú, cercano, espontáneo): ni robot, ni manual corporativo, ni frases vacías del tipo " +
                "\"estoy aquí para ayudarte\". Puedes ser breve o explicar con naturalidad."
        ),
        emptyList<Message>(),
        emptyList<ToolProvider>(),
        SamplerConfig(40, 0.92, 0.18, 0),
        false
    )

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

    /** Una sola instancia; crear el cliente en cada escaneo penaliza mucho el tiempo. */
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class SessionTurn(val user: String, val assistant: String)

    private val sessionLock = Any()
    private val sessionTurns = mutableListOf<SessionTurn>()

    /**
     * Registra un intercambio mostrado en el chat (texto del usuario y respuesta final de FinAI).
     * Se usa en la siguiente llamada a [processCommand] como contexto.
     */
    fun recordSessionTurn(user: String, assistant: String) {
        val u = user.trim().replace("\n", " ").take(2000)
        val a = assistant.trim().replace("\n", " ").take(6000)
        if (u.isEmpty() || a.isEmpty()) return
        synchronized(sessionLock) {
            sessionTurns.add(SessionTurn(u, a))
            while (sessionTurns.size > MAX_SESSION_TURNS) {
                sessionTurns.removeAt(0)
            }
        }
    }

    /** Borra el historial de chat en memoria (p. ej. al limpiar conversación). */
    fun clearSessionConversation() {
        synchronized(sessionLock) { sessionTurns.clear() }
    }

    private fun buildSessionContextPrefix(): String {
        synchronized(sessionLock) {
            if (sessionTurns.isEmpty()) return ""
            val sb = StringBuilder()
            for (t in sessionTurns) {
                sb.append("Usuario: ").append(t.user).append('\n')
                sb.append("FinAI: ").append(t.assistant).append("\n\n")
            }
            var block = sb.toString().trim()
            while (block.length > MAX_SESSION_CONTEXT_CHARS) {
                val cut = block.indexOf("\n\nUsuario:")
                if (cut < 0) {
                    block = block.takeLast(MAX_SESSION_CONTEXT_CHARS)
                    break
                }
                block = block.substring(cut + 2).trim()
            }
            return "Historial del chat (mismo usuario por texto o por voz; tolera errores de transcripción). " +
                "Úsalo para entender referencias — «eso», «lo de antes», pronombres — y datos que ya salieron. " +
                "No copies el historial literal en la respuesta.\n" +
                "$block\n\n" +
                "---\n"
        }
    }

    fun setEngine(engineType: AIEngine, apiKey: String? = null) {
        currentEngine = engineType
        _engineState.value = engineType

        when (engineType) {
            AIEngine.GEMINI_API -> {
                if (engine != null) cleanup()
                val key = apiKey?.trim().orEmpty()
                geminiModel = if (key.isNotEmpty()) {
                    GenerativeModel(
                        modelName = "gemini-2.5-flash",
                        apiKey = key
                    )
                } else {
                    null
                }
            }
            AIEngine.GEMMA_LOCAL -> {
                geminiModel = null
            }
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
                    modelSize = "Gemma 4 E2B IT (${String.format("%.2f", modelFile.length() / 1024.0 / 1024.0 / 1024.0)} GB)"
                )
                Result.success("Modelo descargado")
            } else {
                _gemmaModelState.value = GemmaModelState(
                    isAvailable = false,
                    modelSize = "Gemma 4 E2B IT (~2.4 GB)"
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
                return@withContext Result.failure(Exception("Se requiere un token de HuggingFace (HF_TOKEN) para descargar Gemma 4."))
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
                    modelSize = "Gemma 4 E2B IT (${String.format("%.2f", modelFile.length() / 1024.0 / 1024.0 / 1024.0)} GB)"
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
                throw Exception("Error $responseCode: No autorizado. Verifica tu Token de HuggingFace y acepta la licencia de Gemma 4.")
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
            this.engine = eng
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
            AIEngine.GEMMA_LOCAL -> processInvoiceWithGemma(imageUri)
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

            val bitmapRaw = uriToBitmap(imageUri)
            if (bitmapRaw == null) {
                return AIResult(success = false, message = "Error al cargar la imagen")
            }
            val ocrBitmap = scaleBitmapMaxSide(bitmapRaw, OCR_MAX_SIDE_PX)
            val ocrPlain = performLocalOCR(ocrBitmap).takeIf { it.isNotBlank() }
            if (ocrBitmap !== bitmapRaw && !ocrBitmap.isRecycled) ocrBitmap.recycle()

            val bitmap = scaleBitmapForCloudApi(bitmapRaw)

            val prompt = """
                Analiza esta factura, ticket o NÓMINA y extrae datos en JSON (solo el objeto, sin markdown):
                {
                    "proveedor": "nombre comercial o razón social",
                    "fecha": "YYYY-MM-DD",
                    "total": número_decimal,
                    "base_imponible": número_decimal o null,
                    "iva_importe": cuota_iva_en_euros o null,
                    "moneda": "EUR",
                    "iva_percent": porcentaje_iva,
                    "nif_emisor": "NIF/CIF del emisor si visible",
                    "categoria": "categoría útil del gasto o ingreso",
                    "tipo_ingreso": "solo nómina: nomina|complemento|pagas_extra|otro o null",
                    "concepto": "solo nómina: descripción corta ej. Nómina marzo 2025",
                    "total_devengado": solo nómina, bruto/devengado o null,
                    "total_deducciones": solo nómina, suma deducciones SS+IRPF+otras o null,
                    "productos": [
                        {
                            "descripcion": "nombre del producto",
                            "cantidad": número,
                            "precio_unitario": número_decimal,
                            "subtotal": número_decimal (cantidad × precio_unitario salvo descuento en línea),
                            "iva_percent": porcentaje
                        }
                    ]
                }
                GASTO: "total" = IMPORTE FINAL (TOTAL A PAGAR / TOTAL FACTURA), no IVA suelto ni códigos de barras.
                NÓMINA (hay muchos formatos; busca el RECUADRO RESUMEN al pie, no líneas sueltas del detalle):
                - "proveedor"/empresa = razón social empleadora; "nif_emisor" = CIF empresa.
                - "total" = TOTAL LÍQUIDO / neto a percibir (ej. 351,22), NUNCA el Total Devengado (920,16) ni bases SS.
                - "total_devengado" = suma devengos del resumen; "total_deducciones" = suma deducciones del resumen.
                - "concepto": "Nómina [mes año] - [empresa]" si puedes leer periodo de liquidación; "tipo_ingreso":"nomina".
                - "fecha": último día del periodo o del mes liquidado (YYYY-MM-DD).
                productos: [] en nómina. En ticket cada línea con su precio_unitario y subtotal distintos si en el papel lo son.
                Usa punto decimal en JSON (72.75 si en la imagen sale 72,75).
                No confundas **kg** (peso, puede tener 3 decimales, ej. 0,368 kg) con **euros**; **€/kg** es precio por kilo; el total del ticket NO es la devolución ni “entrega efectivo”.
                Si no sabes un campo opcional, null.
            """.trimIndent()

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(prompt)
                }
            )

            val responseText = response.text
            if (responseText.isNullOrBlank()) {
                Log.w("AIService", "Gemini invoice: empty text (blocked or unsupported)")
                return AIResult(
                    success = false,
                    message = "Gemini no devolvió datos para la imagen. Prueba otra foto más nítida, más cerca del texto, o revisa tu cuota/API key en Configuración > IA."
                )
            }
            parseInvoiceResponse(responseText, imageUri.toString(), ocrPlainText = ocrPlain)

        } catch (e: Exception) {
            Log.e("AIService", "processInvoiceWithGemini", e)
            val errorMsg = e.message ?: ""
            val friendlyMsg = when {
                errorMsg.contains("clipboard", ignoreCase = true) || errorMsg.contains("image input", ignoreCase = true) ->
                    "Error de API de Gemini: la clave no es válida. Revisa tu API key en Configuración > IA."
                errorMsg.contains("API key", ignoreCase = true) || errorMsg.contains("api_key", ignoreCase = true) ->
                    "API key de Gemini no válida. Ve a Configuración > IA para configurarla."
                errorMsg.contains("429", ignoreCase = true) || errorMsg.contains("quota", ignoreCase = true) || errorMsg.contains("resource exhausted", ignoreCase = true) ->
                    "Límite de uso de Gemini alcanzado. Espera un momento o revisa tu plan en Google AI Studio."
                errorMsg.contains("image", ignoreCase = true) && errorMsg.contains("size", ignoreCase = true) ->
                    "La imagen es demasiado grande para Gemini. Intenta recortar la factura o bajar resolución."
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

            val today = java.time.LocalDate.now().toString()
            val historyPrefix = buildSessionContextPrefix()
            val prompt = """
                $historyPrefix
                El mensaje NUEVO del usuario es el que figura abajo (puede venir de voz: interpreta la intención aunque falten tildes o palabras sueltas).
                Usa el historial para coherencia conversational y lo que ya acordamos; las consultas/registros deben reflejar los DATOS REALES de la app cuando apliques action query/add_*.
                Responde a ese mensaje con EXACTAMENTE un JSON según las reglas; no devuelvas el historial como texto libre.

                Eres FinAI: escribes en español como si hablaras con un amigo que confía en ti con temas de dinero.
                Natural, cálido, sin postureo de call center. Nada de "como asistente", "estoy programado para",
                ni listas frías si la respuesta va en "chat". Hoy es $today.

                Analiza el mensaje y devuelve EXACTAMENTE un JSON (una sola pieza) según estas reglas:

                1. CONSULTA FINANCIERA (gastos, ingresos, balance, listados, etc.):
                   {"action":"query","query_type":"gastos|ingresos|balance|productos","periodo":"hoy|semana|mes|año","categoria":null,"item":null}
                   Usa query_type "productos" para ranking de artículos más comprados o mayor gasto por producto (sin nombrar uno solo).
                   Si pregunta CUÁNTO GASTÓ en un producto o artículo CONCRETO (ej. "¿cuánto gasté en leche este mes?", "total de café en la semana", "dinero en gasolina en el año"):
                   usa query_type "gastos", el periodo que indique (si dice "este mes" → "mes", "hoy" → "hoy"), categoria null, y en "item" el nombre o palabras clave del producto (ej. "leche", "café", "gasolina"). Sin comillas anidadas en el valor.

                2. REGISTRAR GASTO (si dice que gastó, compró, pagó algo):
                   {"action":"add_expense","descripcion":"texto","cantidad":1,"precio_unitario":0,"total":0,"moneda":"EUR","fecha":"$today","categoria":"texto"}

                3. REGISTRAR INGRESO (si menciona nómina, salario, cobro, ingreso recibido):
                   {"action":"add_income","concepto":"texto","tipo_ingreso":"nomina|venta|otro","total_devengado":0,"total_deducciones":0,"total_neto":0,"monto":0,"moneda":"EUR","fecha":"$today","fuente":"texto"}

                4. CONVERSACIÓN GENERAL (saludos, charla, dudas, consejos sin consultar la app):
                   {"action":"chat","response":"texto aquí en primera o segunda persona, fluido, como en WhatsApp con alguien que sabe de finanzas"}
                   El "response" debe parecer escrito por una persona: puedes usar ejemplos cortos, un toque de humor suave si encaja,
                   y cerrar a veces con una pregunta abierta solo si tiene sentido (no siempre). Evita sonar a FAQ o a bot.

                Mensaje del usuario: "$command"

                Responde SOLO con el JSON, sin texto fuera del JSON ni markdown.
            """.trimIndent()

            val response = model.generateContent(content { text(prompt) })
            val responseText = response.text
            if (responseText.isNullOrBlank()) {
                Log.w("AIService", "Gemini command: empty text")
                return AIResult(
                    success = false,
                    message = "Gemini no devolvió respuesta. Revisa API key y cuota en Configuración > IA."
                )
            }
            parseCommandResponse(responseText)

        } catch (e: Exception) {
            Log.e("AIService", "processCommandWithGemini", e)
            val errorMsg = e.message ?: ""
            val friendlyMsg = when {
                errorMsg.contains("clipboard", ignoreCase = true) || errorMsg.contains("image input", ignoreCase = true) ->
                    "Error de API de Gemini: la clave no es válida. Revisa tu API key en Configuración > IA."
                errorMsg.contains("API key", ignoreCase = true) || errorMsg.contains("api_key", ignoreCase = true) ->
                    "API key de Gemini no válida. Ve a Configuración > IA para configurarla correctamente."
                errorMsg.contains("quota", ignoreCase = true) || errorMsg.contains("rate limit", ignoreCase = true) ->
                    "Límite de uso de Gemini API alcanzado. Intenta más tarde o cambia a Gemma 4 Local."
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
                    "item": "producto o artículo si pregunta cuánto gastó en algo concreto"
                }
                
                Solo devuelve el JSON.
            """.trimIndent()

            val response = model.generateContent(content { text(prompt) })
            val responseText = response.text
                ?: return AIResult(success = false, message = "Gemini no devolvió texto para la consulta.")

            AIResult(success = true, message = "Consulta procesada", queryResult = responseText)

        } catch (e: Exception) {
            Log.e("AIService", "queryDataWithGemini", e)
            AIResult(success = false, message = "Error al procesar la consulta: ${e.message}")
        }
    }

    private suspend fun processInvoiceWithGemma(imageUri: Uri): AIResult {
        return try {
            val rawBitmap = uriToBitmap(imageUri) ?: return AIResult(success = false, message = "Error al cargar la imagen")
            val ocrBitmap = scaleBitmapMaxSide(rawBitmap, OCR_MAX_SIDE_PX)
            if (ocrBitmap !== rawBitmap && !rawBitmap.isRecycled) rawBitmap.recycle()

            val fullOcrText = performLocalOCR(ocrBitmap)
            if (!ocrBitmap.isRecycled) ocrBitmap.recycle()

            if (fullOcrText.isBlank()) {
                return AIResult(success = false, message = "No se pudo extraer texto de la imagen (OCR local vacío)")
            }
            val extractedForModel = truncateOcrForGemma(fullOcrText)

            val prompt = """
                Del texto OCR de ticket, factura o NÓMINA (España: importes con coma), devuelve SOLO un JSON (una línea):
                {"tipo":"gasto"|"ingreso","categoria":"...","emisor":"...","fecha":"YYYY-MM-DD","total":0.0,
                "base_imponible":null,"iva_importe":null,"moneda":"EUR","nif_emisor":null,
                "concepto":null,"tipo_ingreso":null,"total_devengado":null,"total_deducciones":null,"productos":[]}
                Reglas:
                - NÓMINA: "tipo":"ingreso". Lee el BLOQUE RESUMEN (totales), no celdas del detalle: "total" = Total Líquido/neto (no Total Devengado). "total_devengado", "total_deducciones" del mismo resumen. "emisor"=empresa, "nif_emisor"=CIF. "concepto":"Nómina [mes] [año] - empresa". "fecha"=fin de periodo (YYYY-MM-DD). "tipo_ingreso":"nomina". "productos":[].
                - TICKET/GASTO: "tipo":"gasto". "total" = TOTAL A PAGAR (no una cuota de IVA suelta como 0,36 si el total es 72,75). "base_imponible" y "iva_importe" si constan.
                - Cada producto: descripcion, cantidad, precio_unitario y subtotal coherentes; no repitas el mismo precio_unitario en todas las líneas si en el ticket son distintos.
                - Líneas a peso: cantidad en kg (puede ser 0,368 o 1,554), precio_unitario = €/kg, subtotal = importe final de la línea. No tomes trozos de kg como euros.
                - Punto decimal en JSON: 72.75 para 72,75 en papel.
                Texto OCR:
                $extractedForModel
            """.trimIndent()

            val fullResponse = sendGemmaMessage(prompt)
            if (fullResponse.isNotBlank()) {
                parseInvoiceResponse(fullResponse, imageUri.toString(), ocrPlainText = fullOcrText)
            } else {
                AIResult(success = false, message = "Gemma 4 no pudo procesar el texto de la factura")
            }
        } catch (e: Exception) {
            AIResult(success = false, message = "Error en OCR local: ${e.message}")
        }
    }

    /** Conserva cabecera y pie del ticket: el total suele ir al final; recortar solo el inicio lo oculta al modelo. */
    private fun truncateOcrForGemma(text: String): String {
        val t = text.trim()
        if (t.length <= GEMMA_OCR_MAX_CHARS) return t
        val marker = "\n...[… texto central omitido …]\n"
        val budget = GEMMA_OCR_MAX_CHARS - marker.length
        val head = GEMMA_OCR_HEAD_CHARS.coerceAtMost(budget / 2)
        val tail = (budget - head).coerceAtLeast(800)
        return buildString(head + marker.length + tail) {
            append(t, 0, head)
            append(marker)
            append(t, t.length - tail, t.length)
        }
    }

    /** Extrae texto del modelo (no uses Message.toString para JSON: puede incluir basura estructural). */
    private fun plainTextFromLlmMessage(msg: Message): String {
        val sb = StringBuilder()
        for (c in msg.contents.contents) {
            when (c) {
                is Content.Text -> sb.append(c.text)
                else -> Unit
            }
        }
        return sb.toString().trim()
    }

    private suspend fun sendGemmaMessage(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            if (!isGemmaReady) {
                initGemmaEngine() ?: return@withContext ""
            }
            val eng = engine ?: return@withContext ""
            val conversation = eng.createConversation(gemmaStructuredConversationConfig)
            try {
                val reply = conversation.sendMessage(prompt)
                val text = plainTextFromLlmMessage(reply)
                if (text.isNotEmpty()) text else reply.toString().trim()
            } finally {
                try {
                    conversation.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e("AIService", "Gemma sendMessage failed", e)
            ""
        }
    }

    private suspend fun performLocalOCR(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText.text)
            }
            .addOnFailureListener { e ->
                Log.e("AIService", "ML Kit OCR failed", e)
                continuation.resume("")
            }
    }

    private fun scaleBitmapMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxDim = maxOf(w, h)
        if (maxDim <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxDim
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    /** Parsea importes en JSON o strings con formato ES (17,86 / 1.234,56). */
    private fun parseJsonMoney(json: JSONObject, key: String, default: Double = 0.0): Double {
        if (!json.has(key) || json.isNull(key)) return default
        return when (val raw = json.opt(key)) {
            is Number -> raw.toDouble()
            is String -> parseLocaleMoneyString(raw)?.takeIf { !it.isNaN() } ?: default
            else -> default
        }
    }

    /** Varias claves típicas en español / APIs distintas: primer valor &gt; 0, si no el último definido. */
    private fun parseJsonMoneyKeys(json: JSONObject, vararg keys: String): Double {
        var last = 0.0
        var any = false
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val v = parseJsonMoney(json, key, 0.0)
            any = true
            last = v
            if (v > 0) return v
        }
        return if (any) last else 0.0
    }

    private fun parseJsonQuantityLine(json: JSONObject): Double {
        val v = parseJsonMoneyKeys(json, "cantidad", "unidades", "uds", "qty", "quantity")
        return when {
            v > 0 -> v
            else -> 1.0
        }
    }

    private fun normalizeOcrMatchKey(s: String): String {
        val lower = s.lowercase(java.util.Locale.getDefault())
            .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ñ", "n")
        return lower.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun ocrTokensOverlap(descKey: String, lineKey: String): Boolean {
        val ta = descKey.split(' ').filter { it.length >= 3 }.toSet()
        val tb = lineKey.split(' ').filter { it.length >= 3 }.toSet()
        if (ta.isEmpty()) return false
        return ta.any { it in tb }
    }

    /** Rellena precio/subtotal buscando la línea del OCR donde aparece la descripción. */
    private fun enrichProductsFromOcr(products: MutableList<Product>, ocr: String?) {
        if (ocr.isNullOrBlank() || products.isEmpty()) return
        val lines = ocr.lines().map { it.trim() }.filter { it.length >= 4 }
        val moneyTail =
            Regex("""\s+(\d{1,5}(?:\.\d{3})*,\d{2}|\d{1,5},\d{2}|\d{1,4}\.\d{2})\s*€?\s*$""")
        for (i in products.indices) {
            val p = products[i]
            if (abs(p.subtotal) >= 0.015 || p.descripcion.isBlank()) continue
            val descKey = normalizeOcrMatchKey(p.descripcion)
            if (descKey.length < 3) continue
            var found: Double? = null
            for (line in lines) {
                val lower = line.lowercase()
                if (lower.contains("total") && !line.contains(p.descripcion, ignoreCase = true)) continue
                if (lower.contains("subtotal") || lower.contains("iva") && !lower.contains(p.descripcion, ignoreCase = true)) {
                    if (!line.contains(p.descripcion, ignoreCase = true)) continue
                }
                if (Regex("""\d{7,}""").containsMatchIn(line)) continue
                val lineKey = normalizeOcrMatchKey(line)
                if (!lineKey.contains(descKey) && !ocrTokensOverlap(descKey, lineKey)) continue
                val m = moneyTail.find(line) ?: continue
                val amt = parseLocaleMoneyString(m.groupValues[1]) ?: continue
                if (amt in 0.005..5_000.0) {
                    found = amt
                    break
                }
            }
            if (found != null) {
                val c = p.cantidad.coerceAtLeast(0.01)
                products[i] = p.copy(
                    precioUnitario = found / c,
                    subtotal = found
                )
            }
        }
    }

    /**
     * Líneas tipo ticket ES: descripción al inicio e importe con decimales al final.
     * Se usa si el modelo devuelve productos con importes en cero.
     */
    private fun parseLineItemsFromOcr(ocr: String): List<Product> {
        val out = mutableListOf<Product>()
        val lineRx =
            Regex("""^(.{3,}?)\s+(\d{1,4}(?:\.\d{3})*,\d{2}|\d{1,5},\d{2}|\d{1,4}\.\d{2})\s*€?\s*$""")
        // Venta a peso: "PATATA 1,554 kg 1,90 €/kg 2,95" (Mercadona y similares)
        val weightLineRx = Regex(
            """^(.+?)\s+(\d+[\.,]\d+)\s*kg\s+(\d+[\.,]\d+)\s*€?\s*/\s*kg\s+(\d+[\.,]\d+)(?:\s*€)?\s*$""",
            RegexOption.IGNORE_CASE
        )
        val moneyTailRx =
            Regex("""(\d{1,5}(?:\.\d{3})*,\d{2}|\d{1,6},\d{2}|\d{1,4}\.\d{2})""")
        lineLoop@ for (raw in ocr.lines()) {
            val line = raw.trim()
            if (line.length < 6) continue
            val lower = line.lowercase()
            if (lower.contains("total") || lower.contains("subtotal") || lower.contains("base impo") ||
                lower.contains("cambio") || lower.contains("devoluc") ||
                (lower.contains("entrega") && lower.contains("efectivo")) ||
                lower.contains("tarjeta") ||
                lower.contains("visa") || lower.contains("master") || lower.contains("bizum") ||
                lower.contains("factura simpl") || lower.contains("ticket:") || lower.contains("nif") ||
                lower.contains("cif") || lower.contains("iva ") || lower.contains("% iva") ||
                Regex("""iva\s*\d+\s*%""").containsMatchIn(lower) ||
                lower.contains("descuento global")
            ) {
                continue
            }
            if (Regex("""\d{8,}""").containsMatchIn(line)) continue
            val wm = weightLineRx.find(line)
            if (wm != null) {
                val desc = wm.groupValues[1].trim()
                val kg = parseLocaleMoneyString(wm.groupValues[2]) ?: continue
                val precioKg = parseLocaleMoneyString(wm.groupValues[3]) ?: continue
                val sub = parseLocaleMoneyString(wm.groupValues[4]) ?: continue
                if (desc.length < 2 || !desc.any { it.isLetter() }) continue
                if (kg <= 0 || precioKg <= 0 || sub !in 0.01..5_000.0) continue
                out.add(
                    Product(
                        invoiceId = 0,
                        descripcion = desc,
                        cantidad = kg,
                        precioUnitario = precioKg,
                        subtotal = sub,
                        categoria = null
                    )
                )
                continue
            }
            val m = lineRx.find(line)
            if (m != null) {
                var desc = m.groupValues[1].trim()
                if (desc.length < 2) continue
                if (desc.all { it.isDigit() || it.isWhitespace() }) continue
                if (!desc.any { it.isLetter() }) continue
                val amt = parseLocaleMoneyString(m.groupValues[2]) ?: continue
                if (amt !in 0.01..5_000.0) continue
                val qtyMatch = Regex("""\b(\d{1,3})\s*[xX]\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$""").find(desc)
                if (qtyMatch != null) {
                    val q = qtyMatch.groupValues[1].toDoubleOrNull() ?: 1.0
                    val pu = parseLocaleMoneyString(qtyMatch.groupValues[2]) ?: (amt / q)
                    val dm = qtyMatch.value
                    desc = desc.removeSuffix(dm).trim()
                    if (desc.length >= 2) {
                        out.add(
                            Product(
                                invoiceId = 0,
                                descripcion = desc,
                                cantidad = q.coerceAtLeast(0.01),
                                precioUnitario = pu,
                                subtotal = amt,
                                categoria = null
                            )
                        )
                        continue@lineLoop
                    }
                }
                out.add(
                    Product(
                        invoiceId = 0,
                        descripcion = desc,
                        cantidad = 1.0,
                        precioUnitario = amt,
                        subtotal = amt,
                        categoria = null
                    )
                )
                continue@lineLoop
            }
            // Fallback: último importe de la línea (el OCR a veces añade texto o símbolos tras el precio).
            if (!lower.contains("€/kg") && !lower.contains("eur/kg") && !lower.contains("/kg")) {
                val amtMatches = findEuroAmountMatches(line, moneyTailRx)
                if (amtMatches.isNotEmpty()) {
                    val lastM = amtMatches.last()
                    val amt = parseLocaleMoneyString(lastM.groupValues[1]) ?: continue@lineLoop
                    if (amt !in 0.02..500.0) continue@lineLoop
                    var desc = line.substring(0, lastM.range.first).trim()
                    if (desc.length < 2 || desc.all { it.isDigit() || it.isWhitespace() } ||
                        !desc.any { it.isLetter() }
                    ) {
                        continue@lineLoop
                    }
                    if (Regex("""\d+\s*,\s*\d+\s*(?:kg|g)\b""", RegexOption.IGNORE_CASE).containsMatchIn(desc)) {
                        continue@lineLoop
                    }
                    val qtyMatchLoose =
                        Regex("""\b(\d{1,3})\s*[xX]\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*$""").find(desc)
                    if (qtyMatchLoose != null) {
                        val q = qtyMatchLoose.groupValues[1].toDoubleOrNull() ?: 1.0
                        val pu = parseLocaleMoneyString(qtyMatchLoose.groupValues[2]) ?: (amt / q)
                        desc = desc.removeSuffix(qtyMatchLoose.value).trim()
                        if (desc.length >= 2) {
                            out.add(
                                Product(
                                    invoiceId = 0,
                                    descripcion = desc,
                                    cantidad = q.coerceAtLeast(0.01),
                                    precioUnitario = pu,
                                    subtotal = amt,
                                    categoria = null
                                )
                            )
                            continue@lineLoop
                        }
                    }
                    out.add(
                        Product(
                            invoiceId = 0,
                            descripcion = desc,
                            cantidad = 1.0,
                            precioUnitario = amt,
                            subtotal = amt,
                            categoria = null
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * El OCR usa comas en pesos (0,368 kg) y en euros (0,36 €). Un regex "número,2dec"
     * puede casar **0,36** dentro de **0,368** o **1,55** dentro de **1,554 kg** → total mal.
     * Solo aceptar el match si no está pegado a más dígitos.
     */
    private fun isTruncatedQuantityMatch(line: String, match: MatchResult): Boolean {
        val a = match.range.first
        val b = match.range.last
        if (a > 0 && line[a - 1].isDigit()) return true
        if (b < line.lastIndex && line[b + 1].isDigit()) return true
        return false
    }

    private fun findEuroAmountMatches(line: String, regex: Regex): List<MatchResult> =
        regex.findAll(line).filter { !isTruncatedQuantityMatch(line, it) }.toList()

    private fun parseLocaleMoneyString(s: String): Double? {
        var t = s.trim()
        if (t.isEmpty()) return null
        t = t.replace("€", "", ignoreCase = true)
            .replace("EUR", "", ignoreCase = true)
            .trim()
        val negative = t.startsWith("-")
        if (negative) t = t.removePrefix("-").trim()
        t = t.replace(Regex("\\s+"), "")
        t = t.filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        val hasComma = t.contains(',')
        val hasDot = t.contains('.')
        val normalized = when {
            hasComma && hasDot -> {
                if (t.lastIndexOf(',') > t.lastIndexOf('.')) {
                    t.replace(".", "").replace(',', '.')
                } else {
                    t.replace(",", "")
                }
            }
            hasComma -> t.replace(',', '.')
            hasDot -> {
                val lastDot = t.lastIndexOf('.')
                val after = t.substring(lastDot + 1)
                val before = t.substring(0, lastDot)
                if (after.length == 3 && before.split('.').all { part -> part.length <= 3 }) {
                    t.replace(".", "")
                } else {
                    t
                }
            }
            else -> t
        }
        val v = normalized.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    /** Palabras típicas de nómina / documento de ingreso en el texto OCR. */
    private fun ocrSuggestsIncomeDocument(ocr: String?): Boolean {
        if (ocr.isNullOrBlank()) return false
        val l = ocr.lowercase(java.util.Locale.getDefault())
        return listOf(
            "nomina", "nómina", "nominas", "nóminas",
            "salario", "sueldo",
            "liquidacion", "liquidación", "periodo de liquidacion", "periodo de liquidación",
            "total devengado", "total deducc", "total a deducir", "total líquido", "total liquido",
            "devengado", "liquido", "líquido", "liquido total", "líquido total",
            "total neto", "importe neto", "neto a percibir", "a percibir",
            "retribucion", "retribución", "antiguedad", "antigüedad", "horas complementarias",
            "seguridad social", "seg. social", "descuento ss", "deducciones",
            "cotizació", "cotizac",
            "retención irpf", "irpf", "tipo %", "ccc ", "iban",
            "empresa cotiza", "trabajador", "emplead", "nif", "documento identidad"
        ).any { l.contains(it.trim()) }
    }

    /** Totales del recuadro resumen (no líneas de concepto sueltas). */
    private fun parsePayrollTotalsFromOcr(ocr: String?): Triple<Double?, Double?, Double?> {
        if (ocr.isNullOrBlank()) return Triple(null, null, null)
        val numberRegex =
            Regex("""(\d{1,6}(?:\.\d{3})*,\d{2}|\d{1,6},\d{2}|\d{1,5}\.\d{2})""")
        var devengado: Double? = null
        var deducciones: Double? = null
        var liquido: Double? = null
        for (raw in ocr.lines()) {
            val line = raw.trim()
            if (line.length < 5) continue
            val l = line.lowercase(java.util.Locale.getDefault())
            if (l.contains("iban") || l.contains("ccc ") || l.contains("forma de cobro")) continue
            if (Regex("""\d{1,2}/\d{1,2}/\d{2,4}""").containsMatchIn(line) && line.length < 24) continue
            if ((l.contains("dia") || l.contains("día")) && l.contains("total") && !l.contains("devengado")) continue
            val matches = findEuroAmountMatches(line, numberRegex)
            val lastAmt = matches.lastOrNull()?.groupValues?.get(1)?.let { parseLocaleMoneyString(it) }
                ?: continue
            if (lastAmt !in 0.01..500_000.0) continue
            val isTotalWord = l.contains("total")
            if (isTotalWord && l.contains("devengado") && !l.contains("liquido") && !l.contains("líquido")) {
                devengado = lastAmt
                continue
            }
            if (isTotalWord && (l.contains("deducc") || l.contains("a deducir"))) {
                deducciones = lastAmt
                continue
            }
            if (
                (isTotalWord && (l.contains("liquido") || l.contains("líquido")) && !l.contains("devengado")) ||
                    l.contains("liquido total") || l.contains("líquido total")
            ) {
                liquido = lastAmt
                continue
            }
            if (l.contains("neto") && (l.contains("percibir") || l.contains("liquido") || l.contains("líquido"))) {
                liquido = lastAmt
            }
        }
        return Triple(devengado, deducciones, liquido)
    }

    /**
     * Pie de ticket tipo Mercadona/ES: totales de base imponible y cuota IVA (varias alícuotas sumadas).
     * Si falta una magnitud y hay total del ticket, completa: total ≈ base + cuota.
     */
    private fun parseTicketFooterBaseIvaFromOcr(ocr: String?, totalTicketHint: Double?): Pair<Double?, Double?> {
        if (ocr.isNullOrBlank()) return Pair(null, null)
        val tail = ocr.takeLast(2200)
        fun findAmt(rx: Regex): Double? =
            rx.find(tail)?.groupValues?.get(1)?.let { parseLocaleMoneyString(it) }?.takeIf { it in 0.01..50_000.0 }

        var base = findAmt(Regex("""(?is)base\s+imponible\D{0,40}(\d{1,5},\d{2})"""))
        var cuota = findAmt(Regex("""(?is)cuota\s*(?:de\s*)?(?:iva|\(iva\))\D{0,40}(\d{1,4},\d{2})"""))
        if (cuota == null) {
            cuota = findAmt(Regex("""(?is)iva\s+total\D{0,30}(\d{1,4},\d{2})"""))
        }
        val hint = totalTicketHint?.takeIf { it in 1.0..50_000.0 }
        if (hint != null) {
            if (base != null && (cuota == null || cuota < 0.01)) {
                val c = hint - base
                if (c in 0.01..hint * 0.5) cuota = c
            } else if (cuota != null && (base == null || base < 0.01)) {
                val b = hint - cuota
                if (b in 0.01..hint * 0.98) base = b
            }
        }
        val linePair = Regex("""(?is)total\D{0,12}(\d{1,5},\d{2})\D{1,16}(\d{1,4},\d{2})""").find(tail)
        if (linePair != null && base == null && cuota == null) {
            val a = parseLocaleMoneyString(linePair.groupValues[1])
            val b = parseLocaleMoneyString(linePair.groupValues[2])
            if (a != null && b != null && a > b && a + b in (hint?.times(0.97) ?: 0.0)..(hint?.times(1.03) ?: 100_000.0)) {
                base = a
                cuota = b
            } else if (a != null && b != null && a > b) {
                base = a
                cuota = b
            }
        }
        return Pair(base, cuota)
    }

    private fun applyTicketContextToProducts(
        products: MutableList<Product>,
        comercio: String,
        fechaMs: Long,
        categoriaTicket: String,
    ) {
        val cat = categoriaTicket.takeIf { it.isNotBlank() && it != "General" }
        for (i in products.indices) {
            val p = products[i]
            products[i] = p.copy(
                comercio = p.comercio.orEmpty().ifBlank { comercio },
                fechaCompra = p.fechaCompra ?: fechaMs,
                categoria = p.categoria ?: cat
            )
        }
    }

    /**
     * Si las líneas reconstruidas desde el OCR suman más cerca del total del ticket que el JSON del modelo,
     * usar el OCR (p. ej. Mercadona: pesos €/kg y precios correctos por línea).
     */
    private fun preferOcrProductLinesIfBetterMatch(
        products: MutableList<Product>,
        ocrPlain: String?,
        totalHint: Double?,
        comercio: String,
        modelTotalFallback: Double? = null,
    ): Boolean {
        if (ocrPlain.isNullOrBlank()) return false
        val hint = when {
            totalHint != null && totalHint >= 3.0 -> totalHint
            modelTotalFallback != null && modelTotalFallback >= 3.0 -> modelTotalFallback
            else -> return false
        }
        val ocrLines = parseLineItemsFromOcr(ocrPlain)
        if (ocrLines.size < 2) return false
        val sumOcr = ocrLines.sumOf { it.subtotal }
        val sumJson = products.sumOf { it.subtotal }
        val tol = max(2.0, 0.055 * hint)
        val errOcr = abs(sumOcr - hint)
        val errJson = abs(sumJson - hint)
        val ocrAnchored = errOcr <= tol
        val jsonAnchored = errJson <= tol
        val jsonVeryOff = errJson > max(2.5, 0.14 * hint)
        val ocrClearlyBetter =
            ocrAnchored && (!jsonAnchored || errOcr + 0.75 < errJson || ocrLines.size > products.size + 1)
        val replaceWeakJson =
            products.isEmpty() ||
                sumJson < hint * 0.55 ||
                (ocrAnchored && jsonVeryOff) ||
                (sumJson < 1.0 && ocrLines.size >= 3)
        if (!ocrClearlyBetter && !replaceWeakJson) return false
        if (!ocrAnchored && !replaceWeakJson) return false
        products.clear()
        products.addAll(ocrLines.map { it.copy(comercio = comercio) })
        Log.w("AIService", "Productos: preferido OCR (${ocrLines.size} líneas, suma $sumOcr, ref. ticket $hint)")
        return true
    }

    /** Clasifica ingreso vs gasto usando JSON del modelo + pistas del OCR (la nómina manda sobre un "gasto" mal etiquetado). */
    private fun isIncomeDocument(
        tipoStr: String,
        responseTextLower: String,
        ocrPlainText: String?,
    ): Boolean {
        if (ocrSuggestsIncomeDocument(ocrPlainText)) return true
        if (tipoStr == "ingreso") return true
        if (tipoStr == "gasto") return false
        val incomeInJson = listOf(
            "nómina", "nomina", "salario", "sueldo",
            "\"tipo\":\"ingreso\"", "\"tipo\": \"ingreso\"",
        ).any { responseTextLower.contains(it) }
        return incomeInJson
    }

    /**
     * Intenta leer el importe final desde el OCR: en tickets TOTAL A PAGAR;
     * en nóminas líquido / neto a percibir (no el total devengado como primera opción).
     */
    private fun guessBestTotalFromOcr(ocr: String?, incomeDocument: Boolean): Double? {
        if (ocr.isNullOrBlank()) return null
        val candidates = mutableListOf<Triple<Double, Int, Boolean>>() // valor, puntuación, es línea neto/líquido
        val numberRegex =
            Regex("""(\d{1,6}(?:\.\d{3})*,\d{2}|\d{1,6},\d{2}|\d{1,5}\.\d{2})""")
        val maxVal = if (incomeDocument) 500_000.0 else 25_000.0
        for (rawLine in ocr.lines()) {
            val line = rawLine.trim()
            if (line.length < 3) continue
            val lower = line.lowercase(java.util.Locale.getDefault())
            if (!incomeDocument && lower.contains("cambio") && !lower.contains("total")) continue
            if (!incomeDocument && (lower.contains("devoluc") ||
                    (lower.contains("entrega") && lower.contains("efectivo")))
            ) {
                continue
            }
            if (!incomeDocument && (lower.contains("€/kg") || lower.contains("eur/kg") || lower.contains("€ / kg"))) {
                continue
            }
            if (!incomeDocument && (lower.contains("cuota iva") || lower.contains("iva 21") && lower.contains("€")) &&
                !lower.contains("pagar") && !lower.contains("total")
            ) {
                continue
            }
            if (!incomeDocument && Regex("""iva\s*\d+\s*%""").containsMatchIn(lower) && !lower.contains("total a pagar")) {
                continue
            }
            val netPayLine =
                lower.contains("liquido") || lower.contains("líquido") ||
                    (lower.contains("importe") && lower.contains("neto")) ||
                    (lower.contains("total") && lower.contains("neto") && !lower.contains("bruto")) ||
                    lower.contains("a percibir") ||
                    (lower.contains("neto") && lower.contains("percibir"))
            val keyword = when {
                lower.contains("subtotal") && !incomeDocument -> 0
                incomeDocument && lower.contains("total") &&
                    (lower.contains("liquido") || lower.contains("líquido")) &&
                    !lower.contains("devengado") -> 20
                incomeDocument && netPayLine -> 16
                incomeDocument && lower.contains("devengado") && !lower.contains("neto") -> 5
                incomeDocument && lower.contains("liquido") -> 15
                incomeDocument && lower.contains("líquido") -> 15
                lower.contains("base imponible") && !lower.contains("total") -> 1
                lower.contains("total") && lower.contains("pagar") -> 10
                lower.contains("importe") && lower.contains("total") -> 9
                lower.contains("total") -> 7
                lower.contains("a pagar") -> 6
                else -> 0
            }
            val hasMoneySym = line.contains('€') || lower.contains("eur")
            if (keyword == 0 && !hasMoneySym) continue
            for (m in findEuroAmountMatches(line, numberRegex)) {
                val v = parseLocaleMoneyString(m.groupValues[1]) ?: continue
                if (v !in 0.01..maxVal) continue
                var score = keyword
                if (line.contains('€')) score += 2
                if (lower.contains("eur")) score += 1
                candidates.add(Triple(v, score, netPayLine && incomeDocument))
            }
        }
        val tail = ocr.takeLast(1500)
        if (tail.contains("total", ignoreCase = true) || tail.contains('€') ||
            (incomeDocument && (tail.contains("neto", true) || tail.contains("liquido", true)))
        ) {
            for (m in findEuroAmountMatches(tail, numberRegex)) {
                parseLocaleMoneyString(m.groupValues[1])?.let { v ->
                    if (v in 0.01..maxVal) {
                        candidates.add(Triple(v, if (incomeDocument) 6 else 4, false))
                    }
                }
            }
        }
        // Pie del ticket: líneas tipo "total a pagar" / "importe total" suelen llevar el importe final (p. ej. 72,75).
        if (!incomeDocument) {
            for (raw in ocr.takeLast(2000).lines()) {
                val line = raw.trim()
                if (line.length < 5) continue
                val l = line.lowercase(java.util.Locale.getDefault())
                if (l.contains("€/kg") || l.contains("eur/kg") || l.contains("/kg")) continue
                if (l.contains("subtotal")) continue
                if (l.contains("base") && l.contains("impon")) continue
                if (Regex("""iva\s*\d+\s*%""").containsMatchIn(l) && !l.contains("pagar")) continue
                if (l.contains("cambio") || l.contains("devoluc")) continue
                if (l.contains("entrega") && l.contains("efectivo")) continue
                if (l.contains("cuota") && l.contains("iva") && !l.contains("total")) continue
                val looksFinalTotal = l.contains("total a pagar") ||
                    (l.contains("importe") && l.contains("total")) ||
                    (l.contains("a pagar") && l.contains("total")) ||
                    (l.contains("total") && !l.contains("iva ") && !l.contains("cuota"))
                if (!looksFinalTotal) continue
                for (m in findEuroAmountMatches(line, numberRegex)) {
                    parseLocaleMoneyString(m.groupValues[1])?.let { v ->
                        if (v in 2.5..maxVal) candidates.add(Triple(v, 22, false))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        val bestScore = candidates.maxOf { it.second }
        val tier = candidates.filter { it.second == bestScore }
        val netPreferred = tier.filter { it.third }
        val pickFrom = if (netPreferred.isNotEmpty()) netPreferred else tier
        // En tickets de gasto el total a pagar suele ser el importe más alto entre candidatos con la misma puntuación;
        // minOf elegía a veces una cuota de IVA pequeña (ej. 0,36) frente al total (72,75).
        return if (incomeDocument && netPreferred.isNotEmpty()) {
            netPreferred.maxOf { it.first }
        } else if (!incomeDocument) {
            pickFrom.maxOfOrNull { it.first } ?: tier.maxOf { it.first }
        } else {
            pickFrom.minOfOrNull { it.first } ?: candidates.maxByOrNull { it.second }?.first
        }
    }

    private fun reconcileInvoiceTotal(
        modelTotal: Double,
        sumLineSubtotals: Double,
        ocrGuess: Double?,
        incomeDocument: Boolean,
        payrollLiquidoFromSummary: Double? = null,
    ): Double {
        var total = modelTotal
        if (incomeDocument && payrollLiquidoFromSummary != null && payrollLiquidoFromSummary in 10.0..500_000.0 &&
            modelTotal > payrollLiquidoFromSummary * 1.12 && modelTotal > payrollLiquidoFromSummary + 25.0
        ) {
            Log.w(
                "AIService",
                "Nómina: el modelo parece haber puesto el bruto ($modelTotal); líquido del resumen OCR ${payrollLiquidoFromSummary}"
            )
            total = payrollLiquidoFromSummary
        }
        // Modelo suele confundir una cuota de IVA o % con el total del ticket.
        if (!incomeDocument && ocrGuess != null && ocrGuess >= 3.0 && modelTotal in 0.01..<2.5 &&
            ocrGuess / max(modelTotal, 0.01) >= 8.0
        ) {
            Log.w("AIService", "Total corregido: modelo parece cuota/ruido ($modelTotal) → OCR $ocrGuess")
            total = ocrGuess
        }
        val hasLines = sumLineSubtotals > 0.01
        if (hasLines) {
            val relDiff = abs(total - sumLineSubtotals) / max(sumLineSubtotals, total).coerceAtLeast(0.01)
            val linesConflictOcr = !incomeDocument && ocrGuess != null && ocrGuess >= 5.0 &&
                abs(sumLineSubtotals - ocrGuess) > max(2.5, 0.065 * ocrGuess)
            if ((total <= 0 || relDiff > 0.15) &&
                (total / max(sumLineSubtotals, 0.01) > 2.5 || sumLineSubtotals / max(total, 0.01) > 2.5 || relDiff > 0.5)
            ) {
                if (!linesConflictOcr) {
                    Log.w(
                        "AIService",
                        "Total ajustado por suma de líneas: modelo=$total → líneas=$sumLineSubtotals"
                    )
                    total = sumLineSubtotals
                }
            }
        }
        val maxOcr = if (incomeDocument) 500_000.0 else 25_000.0
        if (incomeDocument && payrollLiquidoFromSummary == null && ocrGuess != null &&
            ocrGuess in 50.0..500_000.0 && total > ocrGuess * 1.12 && total > ocrGuess + 25.0
        ) {
            Log.w("AIService", "Nómina: total parece bruto ($total) vs líquido OCR $ocrGuess")
            total = ocrGuess
        }
        if (ocrGuess != null && ocrGuess in 0.01..maxOcr) {
            val denom = max(max(total, ocrGuess), 0.01)
            val relToOcr = abs(total - ocrGuess) / denom
            if (total <= 0) {
                total = ocrGuess
            } else if (relToOcr > 0.18) {
                val linesAgreeOcr =
                    hasLines && abs(sumLineSubtotals - ocrGuess) <= max(0.75, 0.08 * ocrGuess)
                val capHuge = if (incomeDocument) 200_000.0 else 500.0
                val modelHugeVsOcr = total > ocrGuess * 4 && ocrGuess < capHuge
                if (linesAgreeOcr || modelHugeVsOcr || (!hasLines && relToOcr > 0.35)) {
                    Log.w("AIService", "Total ajustado por OCR: modelo=$modelTotal → ocr=$ocrGuess")
                    total = ocrGuess
                }
            }
        }
        // Gasto: el modelo a veces llena todas las líneas con el mismo precio; la suma queda muy por debajo del total real.
        // Si el total OCR es plausible y la suma de líneas no cuadra, el total del ticket manda sobre la suma errónea.
        if (!incomeDocument && ocrGuess != null && ocrGuess >= 5.0 &&
            abs(sumLineSubtotals - ocrGuess) > max(2.5, 0.065 * ocrGuess)
        ) {
            if (abs(total - ocrGuess) > max(1.25, 0.055 * ocrGuess)) {
                Log.w(
                    "AIService",
                    "Total: prioridad OCR ($ocrGuess €) frente a suma líneas=$sumLineSubtotals € (previo=$total €)"
                )
                total = ocrGuess
            }
        }
        return total
    }

    /** Si el modelo no envía concepto, armar uno legible (mes + empresa). */
    private fun buildDefaultPayrollConcept(ocr: String?, emisor: String): String {
        if (ocr.isNullOrBlank()) return "Nómina - $emisor"
        val l = ocr.lowercase(java.util.Locale.getDefault())
        val meses = listOf(
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
        )
        for (mes in meses) {
            if (!l.contains(mes)) continue
            val mesTit = mes.replaceFirstChar { it.uppercase() }
            val y20 = Regex("""(20[12]\d)""").find(ocr)?.groupValues?.get(1)
            val yWeird = Regex("""2[.,]\s*0?(\d{2})\b""").find(ocr)?.groupValues?.get(1)?.let { "20$it" }
            val anio = y20 ?: yWeird
            return if (anio != null) "Nómina $mesTit $anio - $emisor" else "Nómina $mesTit - $emisor"
        }
        return "Nómina - $emisor"
    }

    private suspend fun processCommandWithGemma(command: String): AIResult {
        return try {
            val today = java.time.LocalDate.now().toString()
            val historyPrefix = buildSessionContextPrefix()
            val prompt = """
                $historyPrefix
                Hoy es $today. Mensaje ACTUAL (texto o voz transcrita; infiere intención si suena coloquial o incompleto):
                ${command.trim()}
                Mantén coherencia con el historial si el usuario alude a algo anterior. Los actions query/add_* deben cuadrar con la app.

                Devuelve SOLO un JSON válido en una línea (sin markdown). Tipos:
                {"action":"query","query_type":"gastos"|"ingresos"|"balance"|"productos","periodo":"hoy"|"semana"|"mes"|"año","categoria":null,"item":null}
                Producto concreto ("cuánto en leche"): query_type "gastos", periodo correcto, "item" con la palabra clave.
                {"action":"add_expense",...}
                {"action":"add_income",...}
                {"action":"chat","response":"..."}  — en "response", español hablado, cercano, como charlando; nada de tono robótico ni de manual.
                Un solo objeto según el mensaje.
            """.trimIndent()

            val fullResponse = sendGemmaMessage(prompt)
            if (fullResponse.isBlank()) {
                return AIResult(success = false, message = "Gemma 4 no respondió")
            }

            parseCommandResponse(fullResponse)
        } catch (e: Exception) {
            Log.e("AIService", "Error with Gemma", e)
            AIResult(success = false, message = "Error con Gemma 4: ${e.message}")
        }
    }

    /** Líquido nómina: claves largas primero; "neto" al final para no tomar una cuota suelta. */
    private fun parseIncomeNetTotal(json: JSONObject): Double = parseJsonMoneyKeys(
        json,
        "total_liquido",
        "liquido_total",
        "total_neto",
        "liquido",
        "importe_liquido",
        "liquido_percibir",
        "neto_percibir",
        "total_percibir",
        "importe_neto",
        "neto_liquido",
        "total",
        "neto"
    )

    private suspend fun queryDataWithGemma(query: String): AIResult {
        return try {
            val prompt = """
                Consulta: ${query.trim()}
                Responde SOLO con: {"query_type":"gastos"|"ingresos"|"balance","periodo":"hoy"|"semana"|"mes"|"año","categoria":null,"item":null}
                Si la consulta nombra un producto concreto, rellena "item" con la palabra clave.
            """.trimIndent()

            val fullResponse = sendGemmaMessage(prompt)
            if (fullResponse.isBlank()) {
                return AIResult(success = false, message = "Gemma 4 no respondió")
            }

            AIResult(success = true, message = "Consulta procesada", queryResult = fullResponse)
        } catch (e: Exception) {
            Log.e("AIService", "Error with Gemma", e)
            AIResult(success = false, message = "Error con Gemma 4: ${e.message}")
        }
    }

    private fun parseInvoiceResponse(
        responseText: String,
        imageUri: String,
        ocrPlainText: String? = null,
    ): AIResult {
        return try {
            val json = extractJsonFromResponse(responseText)

            val emisor = json.optString("emisor", json.optString("proveedor", "Desconocido"))
            val fechaStr = json.optString("fecha", "")
            val moneda = json.optString("moneda", "EUR")
            val ivaPercent = parseJsonMoney(json, "iva_percent", 21.0)
            val nifEmisor = if (json.isNull("nif_emisor")) null else json.optString("nif_emisor", "").takeIf { it.isNotEmpty() }
            val tipoStr = json.optString("tipo", "gasto").lowercase()
            val responseLower = responseText.lowercase(java.util.Locale.getDefault())
            val isIncome = isIncomeDocument(tipoStr, responseLower, ocrPlainText)

            val modelTotalRaw = if (isIncome) {
                val net = parseIncomeNetTotal(json)
                if (net > 0) net else parseJsonMoney(json, "total", 0.0)
            } else {
                parseJsonMoneyKeys(json, "total", "total_a_pagar", "importe_total", "total_factura")
            }

            val categoriaGlobal = json.optString("categoria", "General")
            var baseImponible = if (!isIncome) {
                parseJsonMoneyKeys(json, "base_imponible", "base", "base_imposable", "importe_base")
            } else 0.0
            var ivaImporte = if (!isIncome) {
                parseJsonMoneyKeys(json, "iva_importe", "cuota_iva", "importe_iva", "iva_total")
            } else 0.0
            val ingresoDevengado = if (isIncome) {
                parseJsonMoneyKeys(json, "total_devengado", "devengado", "total_bruto", "bruto", "salario_bruto")
            } else 0.0
            val ingresoDeducciones = if (isIncome) {
                parseJsonMoneyKeys(
                    json,
                    "total_deducciones",
                    "suma_deducciones",
                    "total_a_deducir",
                    "deducciones",
                    "deducciones_total",
                    "total_retenciones"
                )
            } else 0.0
            val ingresoTipo = if (isIncome) {
                json.optString("tipo_ingreso", "").takeIf { it.isNotBlank() }
            } else null
            val conceptoIngreso = if (isIncome) {
                json.optString("concepto", "").takeIf { it.isNotBlank() }
            } else null

            val fecha = try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .parse(fechaStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            val ocrGuess = guessBestTotalFromOcr(ocrPlainText, isIncome)

            val products = mutableListOf<Product>()
            var sumLineSubtotals = 0.0

            if (!isIncome) {
                val productsArray = json.optJSONArray("productos")
                    ?: json.optJSONArray("items")
                    ?: json.optJSONArray("lineas")

                if (productsArray != null) {
                    for (i in 0 until productsArray.length()) {
                        val productJson = productsArray.optJSONObject(i) ?: continue
                        val descripcion = productJson.optString("descripcion", "")
                            .ifBlank { productJson.optString("nombre", "") }
                            .ifBlank { productJson.optString("articulo", "") }
                            .ifBlank { productJson.optString("producto", "") }
                        var cantidad = parseJsonQuantityLine(productJson)
                        var precio = parseJsonMoneyKeys(
                            productJson,
                            "precio_unitario",
                            "precio_unitario_iva",
                            "pvp",
                            "precio",
                            "p_unit",
                            "unitario"
                        )
                        var subtotal = parseJsonMoneyKeys(
                            productJson,
                            "subtotal",
                            "importe",
                            "importe_linea",
                            "total_linea",
                            "precio_total",
                            "total"
                        )
                        val lineIva = parseJsonMoneyKeys(productJson, "iva_percent", "iva").takeIf { it > 0 } ?: ivaPercent
                        if (precio > 0 && abs(subtotal) < 0.005) {
                            subtotal = cantidad * precio
                        } else if (abs(subtotal) > 0.005 && precio <= 0 && cantidad > 0) {
                            precio = subtotal / cantidad
                        } else if (abs(subtotal) < 0.005 && precio > 0) {
                            subtotal = cantidad * precio
                        }
                        val comercioLinea = productJson.optString("comercio", "").ifBlank { emisor }
                        products.add(
                            Product(
                                invoiceId = 0,
                                descripcion = descripcion,
                                cantidad = cantidad,
                                precioUnitario = precio,
                                subtotal = subtotal,
                                ivaPercent = lineIva,
                                categoria = productJson.optString("categoria", categoriaGlobal).takeIf { it.isNotBlank() },
                                comercio = comercioLinea
                            )
                        )
                    }
                }

                enrichProductsFromOcr(products, ocrPlainText)

                sumLineSubtotals = products.sumOf { it.subtotal }
                if (ocrPlainText != null && products.size >= 2 && ocrGuess != null && ocrGuess > 2.0) {
                    val prices = products.map { it.precioUnitario }
                    val allSamePrice = prices.all { abs(it - prices.first()) < 0.008 }
                    if (allSamePrice) {
                        val fromOcr = parseLineItemsFromOcr(ocrPlainText)
                        val ocrSum = fromOcr.sumOf { it.subtotal }
                        if (fromOcr.size >= 2 && ocrSum > sumLineSubtotals * 1.05 &&
                            abs(ocrSum - ocrGuess) <= max(3.0, 0.2 * ocrGuess) && ocrSum >= ocrGuess * 0.72
                        ) {
                            Log.w("AIService", "Productos desde OCR: precios idénticos en modelo; suma OCR $ocrSum")
                            products.clear()
                            products.addAll(fromOcr.map { it.copy(comercio = emisor) })
                            sumLineSubtotals = products.sumOf { it.subtotal }
                        }
                    }
                }
                val zeroishLines = products.count { abs(it.subtotal) < 0.015 }
                val majorityZeros = products.isNotEmpty() && zeroishLines * 2 >= products.size
                val sumTooSmall = sumLineSubtotals < 0.05 && products.size >= 2

                if (ocrPlainText != null && (products.isEmpty() || majorityZeros || sumTooSmall)) {
                    val fromOcr = parseLineItemsFromOcr(ocrPlainText)
                    val ocrSum = fromOcr.sumOf { it.subtotal }
                    val agreeTotal = ocrGuess == null ||
                        ocrSum in ocrGuess * 0.72..ocrGuess * 1.28 ||
                        abs(ocrSum - ocrGuess) <= max(1.5, 0.12 * ocrGuess)
                    if (fromOcr.isNotEmpty() && ocrSum >= 0.25 && agreeTotal &&
                        (fromOcr.size >= 2 || majorityZeros || products.isEmpty()) &&
                        (products.isEmpty() || majorityZeros || sumLineSubtotals < ocrSum * 0.45)
                    ) {
                        Log.w("AIService", "Líneas de producto reconstruidas desde OCR: ${fromOcr.size} pzas, suma $ocrSum")
                        products.clear()
                        products.addAll(fromOcr.map { it.copy(comercio = emisor) })
                        sumLineSubtotals = products.sumOf { it.subtotal }
                    } else if (products.isEmpty() && fromOcr.isNotEmpty() && ocrSum >= 0.2) {
                        products.addAll(fromOcr.map { it.copy(comercio = emisor) })
                        sumLineSubtotals = products.sumOf { it.subtotal }
                    }
                }

                val (footerBase, footerIva) = parseTicketFooterBaseIvaFromOcr(ocrPlainText, ocrGuess)
                footerBase?.let { fb ->
                    if (baseImponible < 0.02 ||
                        abs(baseImponible - fb) / max(fb, 0.01) > 0.12
                    ) {
                        baseImponible = fb
                    }
                }
                footerIva?.let { fi ->
                    if (ivaImporte < 0.02 ||
                        abs(ivaImporte - fi) / max(fi, 0.01) > 0.15
                    ) {
                        ivaImporte = fi
                    }
                }
                if (preferOcrProductLinesIfBetterMatch(
                        products,
                        ocrPlainText,
                        ocrGuess,
                        emisor,
                        modelTotalFallback = modelTotalRaw.takeIf { it >= 3.0 },
                    )
                ) {
                    sumLineSubtotals = products.sumOf { it.subtotal }
                }
                applyTicketContextToProducts(products, emisor, fecha, categoriaGlobal)
            }

            val payrollOcr = if (isIncome) parsePayrollTotalsFromOcr(ocrPlainText) else Triple(null, null, null)
            var resolvedTotal = reconcileInvoiceTotal(
                modelTotalRaw,
                sumLineSubtotals,
                ocrGuess,
                isIncome,
                payrollOcr.third
            )
            var resolvedDevengado = ingresoDevengado
            var resolvedDeducciones = ingresoDeducciones
            var resolvedConcepto = conceptoIngreso
            var resolvedTipoIngreso = ingresoTipo
            if (isIncome) {
                payrollOcr.first?.let { ocrD ->
                    if (resolvedDevengado < 0.02 ||
                        abs(resolvedDevengado - ocrD) / max(ocrD, 0.01) > 0.25
                    ) {
                        resolvedDevengado = ocrD
                    }
                }
                payrollOcr.second?.let { ocrDd ->
                    if (resolvedDeducciones < 0.02 ||
                        abs(resolvedDeducciones - ocrDd) / max(ocrDd, 0.01) > 0.25
                    ) {
                        resolvedDeducciones = ocrDd
                    }
                }
                payrollOcr.third?.let { liq ->
                    if (liq > 0 &&
                        (resolvedTotal <= 0 || abs(resolvedTotal - liq) / max(liq, 0.01) > 0.08)
                    ) {
                        resolvedTotal = liq
                    }
                }
                if (resolvedConcepto.isNullOrBlank()) {
                    resolvedConcepto = buildDefaultPayrollConcept(ocrPlainText, emisor)
                }
                if (resolvedTipoIngreso.isNullOrBlank()) {
                    resolvedTipoIngreso = "nomina"
                }
            }

            if (!isIncome && ivaImporte < 0.02 && baseImponible > 0.01 && ivaPercent > 0.01) {
                val est = baseImponible * ivaPercent / 100.0
                if (est in 0.02..50_000.0) ivaImporte = est
            }

            val invoice = Invoice(
                fecha = fecha,
                proveedor = emisor,
                tipo = if (isIncome) InvoiceType.INGRESO else InvoiceType.GASTO,
                moneda = moneda,
                total = resolvedTotal,
                baseImponible = baseImponible,
                ivaImporte = ivaImporte,
                ivaPercent = ivaPercent,
                nifEmisor = nifEmisor,
                imagenUri = imageUri,
                ocrRawText = responseText,
                categoria = categoriaGlobal,
                ingresoDevengado = resolvedDevengado,
                ingresoDeducciones = resolvedDeducciones,
                ingresoTipo = resolvedTipoIngreso,
                conceptoIngreso = resolvedConcepto
            )

            val tipoLabel = if (isIncome) "Ingreso" else "Factura"
            AIResult(success = true, message = "$tipoLabel procesada correctamente", invoice = invoice, products = products)

        } catch (e: Exception) {
            AIResult(success = false, message = "Error al parsear la respuesta: ${e.message}")
        }
    }

    private fun extractJsonFromResponse(responseText: String): JSONObject {
        var t = responseText.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val fence = t.lastIndexOf("```")
            if (fence >= 0) t = t.substring(0, fence).trim()
        }
        val jsonMatch = Regex("""\{[\s\S]*\}""").find(t)
        val jsonString = jsonMatch?.value?.trim() ?: t
        return JSONObject(jsonString)
    }

    /** Evita rechazos o errores por imágenes de muy alta resolución en la API. */
    private fun scaleBitmapForCloudApi(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxDim = maxOf(w, h)
        if (maxDim <= GEMINI_IMAGE_MAX_SIDE_PX) return bitmap
        val scale = GEMINI_IMAGE_MAX_SIDE_PX.toFloat() / maxDim
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled != bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
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
                    val categoria = json.optString("categoria", "General")

                    val invoice = Invoice(
                        fecha = fecha,
                        proveedor = descripcion,
                        tipo = InvoiceType.GASTO,
                        moneda = moneda,
                        total = total,
                        categoria = categoria
                    )

                    val product = Product(
                        invoiceId = 0,
                        descripcion = descripcion,
                        cantidad = cantidad,
                        precioUnitario = precioUnitario,
                        subtotal = total,
                        categoria = categoria
                    )

                    AIResult(success = true, message = "Gasto agregado: $descripcion - $total $moneda", invoice = invoice, products = listOf(product))
                }
                "add_income" -> {
                    val concepto = json.optString("concepto", json.optString("descripcion", ""))
                    val totalDevengado = json.optDouble("total_devengado", 0.0)
                    val totalDeducciones = json.optDouble("total_deducciones", 0.0)
                    val totalNeto = json.optDouble("total_neto", 0.0)
                    val monto = json.optDouble("monto", if (totalNeto > 0) totalNeto else totalDevengado)
                    val moneda = json.optString("moneda", "EUR")
                    val fechaStr = json.optString("fecha", "")
                    val fuente = if (json.isNull("fuente")) null else json.optString("fuente", "").takeIf { it.isNotEmpty() }
                    val fecha = parseDate(fechaStr)
                    val categoria = json.optString("categoria", "General")
                    val tipoIngreso = json.optString("tipo_ingreso", "").takeIf { it.isNotEmpty() }
                    val netoEff = if (totalNeto > 0) totalNeto else monto
                    val devEff = if (totalDevengado > 0) totalDevengado else monto
                    val dedEff = if (totalDeducciones > 0) totalDeducciones
                        else (devEff - netoEff).takeIf { it > 0.01 } ?: 0.0

                    val income = Income(
                        fecha = fecha,
                        concepto = concepto,
                        monto = monto,
                        totalDevengado = devEff,
                        totalDeducciones = dedEff,
                        totalNeto = netoEff,
                        moneda = moneda,
                        tipoIngreso = tipoIngreso,
                        fuente = fuente,
                        categoria = categoria
                    )

                    val displayMonto = if (totalDevengado > 0 && totalNeto > 0) {
                        "Devengado: $totalDevengado $moneda / Neto: $totalNeto $moneda"
                    } else {
                        "$monto $moneda"
                    }

                    val incomeJson = JSONObject().apply {
                        put("concepto", income.concepto)
                        put("monto", income.monto)
                        put("moneda", income.moneda)
                        put("fecha", income.fecha)
                        put("total_devengado", income.totalDevengado)
                        put("total_deducciones", income.totalDeducciones)
                        put("total_neto", income.totalNeto)
                        if (income.tipoIngreso != null) put("tipo_ingreso", income.tipoIngreso) else put("tipo_ingreso", JSONObject.NULL)
                        if (income.fuente != null) put("fuente", income.fuente) else put("fuente", JSONObject.NULL)
                        if (income.categoria != null) put("categoria", income.categoria) else put("categoria", JSONObject.NULL)
                    }
                    AIResult(success = true, message = "Ingreso agregado: $concepto - $displayMonto", queryResult = "${IncomeQueryResultParser.PREFIX_RECORD}$incomeJson")
                }
                "query" -> {
                    AIResult(success = true, message = "Consulta procesada", queryResult = json.toString())
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
            val type = context.contentResolver.getType(uri)
            if (type == "application/pdf") {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val pdfRenderer = android.graphics.pdf.PdfRenderer(pfd)
                if (pdfRenderer.pageCount > 0) {
                    val page = pdfRenderer.openPage(0)
                    // Render to a reasonably large bitmap for OCR
                    val bitmap = Bitmap.createBitmap(
                        page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                    )
                    // Fill with white background
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    pdfRenderer.close()
                    pfd.close()
                    return bitmap
                }
                pdfRenderer.close()
                pfd.close()
                null
            } else {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e("AIService", "Error converting URI to Bitmap", e)
            null
        }
    }

    fun cleanup() {
        engine?.close()
        engine = null
        isGemmaReady = false
    }
}
