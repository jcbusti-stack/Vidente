package com.vidente.app

/**
 * Used while the user hasn't set a backend URL yet in the voice settings
 * screen. Once [BackendConversationalAssistant] has a URL to call, that
 * implementation takes over.
 */
class UnconfiguredConversationalAssistant : ConversationalAssistant {
    override fun answer(question: String, screenSummary: String, callback: (String) -> Unit) {
        callback(
            "El modo conversacional todavía no está configurado. Ve a Ajustes de voz y agrega " +
                "la dirección de tu servidor de Vidente."
        )
    }
}
