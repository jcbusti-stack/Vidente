package com.vidente.app

/**
 * Abstraction over the AI backend that answers questions about the current
 * screen. [GeminiConversationalAssistant] is the (currently placeholder)
 * implementation; swapping in a real backend only requires implementing
 * this interface.
 */
interface ConversationalAssistant {
    fun answer(question: String, screenSummary: String, callback: (String) -> Unit)
}
