package com.vidente.app

import android.content.Context

/**
 * Placeholder implementation of [ConversationalAssistant] backed by
 * Google's Gemini API.
 *
 * TODO(conectar Gemini de verdad): reemplazar el cuerpo de [answer] por una
 * llamada HTTP real (por ejemplo con OkHttp) al endpoint de Gemini
 * `generateContent`, enviando `apiKey`, `question` y `screenSummary` como
 * contexto, y entregando el texto de la respuesta al callback. Esa llamada
 * debe hacerse en un hilo de fondo (no en el hilo principal) y siempre
 * terminar invocando `callback` con el resultado o un mensaje de error.
 */
class GeminiConversationalAssistant(private val context: Context) : ConversationalAssistant {

    override fun answer(question: String, screenSummary: String, callback: (String) -> Unit) {
        val apiKey = VidentePreferences.getGeminiApiKey(context)
        if (apiKey.isNullOrBlank()) {
            callback(PLACEHOLDER_NO_KEY)
            return
        }

        // Placeholder: todavía no se llama a la API de Gemini.
        callback(PLACEHOLDER_NOT_IMPLEMENTED)
    }

    companion object {
        private const val PLACEHOLDER_NO_KEY =
            "El modo conversacional con inteligencia artificial todavía no está configurado. " +
                "Agrega tu clave de API de Gemini en Ajustes de voz para activarlo."
        private const val PLACEHOLDER_NOT_IMPLEMENTED =
            "El modo conversacional está listo, pero la conexión con Gemini todavía no está " +
                "implementada. Esta es una respuesta de prueba."
    }
}
