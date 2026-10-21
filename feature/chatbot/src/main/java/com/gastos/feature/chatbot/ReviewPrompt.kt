package com.gastos.feature.chatbot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Petición de reseña dentro de la app con la API oficial de Google Play.
 *
 * Reglas que respeta este fichero, y por qué:
 *  - Se pide **una sola vez en la vida** de la instalación (`asked`), porque la cuota de la API es
 *    limitada y machacar al usuario quema la función.
 *  - Se pide tras un **momento bueno**: el usuario ya ha mantenido una conversación real con la app
 *    (`MIN_AI_MESSAGES` respuestas), no al abrirla por primera vez.
 *  - Si la API falla (sin Play Store, sin red, cuota agotada) **se falla en silencio**: nunca se
 *    muestra un error al usuario por esto ni se rompe la pantalla.
 *  - No se pregunta «¿te gusta la app?» antes: Play prohíbe inducir la reseña y este flujo no lo hace.
 */
object ReviewPrompt {

    private const val PREFS = "finai_review"
    private const val KEY_ASKED = "asked"
    private const val MIN_AI_MESSAGES = 3

    /** ¿Ya se pidió? Se guarda para no repetir en toda la vida de la instalación. */
    fun alreadyAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * Lanza el flujo de reseña si procede. No hace nada si ya se pidió, si no hay actividad o si
     * Play no responde.
     */
    fun maybeAsk(activity: Activity?, aiMessageCount: Int) {
        if (activity == null) return
        if (aiMessageCount < MIN_AI_MESSAGES) return
        if (alreadyAsked(activity)) return

        val manager = try {
            ReviewManagerFactory.create(activity)
        } catch (_: Throwable) {
            return
        }

        runCatching {
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) return@addOnCompleteListener
                runCatching {
                    manager.launchReviewFlow(activity, request.result)
                        .addOnCompleteListener { markAsked(activity) }
                }.onFailure { markAsked(activity) }
            }
        }
    }

}

/** Sube por el árbol de contextos hasta encontrar la Activity que necesita la API de reseñas. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Engancha la petición de reseña a la pantalla del chat: se dispara cuando el número de respuestas
 * de la IA cruza el umbral. Es idempotente gracias al flag persistido en [ReviewPrompt].
 */
@Composable
fun ReviewPromptEffect(aiMessageCount: Int) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(aiMessageCount) {
        ReviewPrompt.maybeAsk(activity, aiMessageCount)
    }
}
