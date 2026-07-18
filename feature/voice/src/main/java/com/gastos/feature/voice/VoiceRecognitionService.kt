package com.gastos.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 0.0f,
    /** true si [text] describe un error/una no-disponibilidad y NO debe
     *  tratarse como entrada del usuario (p.ej. enviarse al asistente). */
    val isError: Boolean = false
)

@Singleton
class VoiceRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(): Flow<VoiceResult> = callbackFlow {
        if (!isAvailable()) {
            trySend(VoiceResult(text = "Reconocimiento de voz no disponible", isFinal = true, confidence = 0.0f, isError = true))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceResult(text = "Escuchando...", isFinal = false))
            }

            override fun onBeginningOfSpeech() {
                trySend(VoiceResult(text = "Habla ahora...", isFinal = false))
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Indicador de volumen
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                trySend(VoiceResult(text = "Procesando...", isFinal = false))
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                    SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se encontró coincidencia"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                    SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado"
                    else -> "Error desconocido: $error"
                }
                trySend(VoiceResult(text = errorMessage, isFinal = true, confidence = 0.0f, isError = true))
                close()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (matches != null && matches.isNotEmpty()) {
                    val bestResult = matches[0]
                    val confidence = confidenceScores?.getOrNull(0) ?: 0.0f
                    trySend(VoiceResult(text = bestResult, isFinal = true, confidence = confidence))
                } else {
                    trySend(VoiceResult(text = "No se detectó texto", isFinal = true, confidence = 0.0f, isError = true))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    trySend(VoiceResult(text = matches[0], isFinal = false))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        speechRecognizer?.startListening(intent)

        awaitClose {
            stopListening()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun destroy() {
        stopListening()
    }
}
