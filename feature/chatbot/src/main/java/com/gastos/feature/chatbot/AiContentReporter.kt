package com.gastos.feature.chatbot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Canal de denuncia de contenido generado por IA.
 *
 * La política de Google Play sobre contenido generado por IA exige que la app ofrezca al usuario
 * una forma de reportar contenido ofensivo o inapropiado. Este objeto compone un correo al
 * desarrollador con el texto exacto de la respuesta, el motivo elegido y datos técnicos mínimos
 * (versión de la app y modelo), sin enviar nada por su cuenta: el envío lo hace el usuario desde
 * su propio gestor de correo.
 */
object AiContentReporter {

    /** Correo de contacto que aparece también en la política de privacidad. */
    private const val CONTACT = "ymi2021@gmail.com"

    enum class Reason(val key: String) {
        OFFENSIVE("offensive"),
        WRONG("wrong"),
        UNSAFE("unsafe"),
        OTHER("other")
    }

    /**
     * Abre el gestor de correo con el reporte ya redactado.
     *
     * @param context contexto de la pantalla (se usa para resolver la actividad).
     * @param aiText texto completo de la respuesta de la IA que se reporta.
     * @param reason motivo elegido por el usuario.
     * @param reasonLabel etiqueta legible del motivo, ya traducida.
     * @param appVersion versión de la app, para poder localizar el caso.
     * @return true si se pudo abrir un gestor de correo; false si no hay ninguno instalado.
     */
    fun sendReport(
        context: Context,
        aiText: String,
        reason: Reason,
        reasonLabel: String,
        appVersion: String
    ): Boolean {
        val subject = "[FinAI] Reporte de contenido generado por IA"
        val body = buildString {
            appendLine("Motivo: $reasonLabel (${reason.key})")
            appendLine()
            appendLine("Contenido reportado:")
            appendLine("---")
            appendLine(aiText.take(4000))
            appendLine("---")
            appendLine()
            appendLine("Versión de la app: $appVersion")
            appendLine("Plataforma: Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$CONTACT")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        return try {
            context.startActivity(Intent.createChooser(intent, null))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No hay gestor de correo instalado", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Versión instalada de la app, leída del paquete; nunca lanza. */
    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
    } catch (_: Exception) {
        "desconocida"
    }
}
