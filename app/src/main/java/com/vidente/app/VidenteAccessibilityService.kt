package com.vidente.app

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class VidenteAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken: String? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
        } else {
            Log.e(TAG, "No se pudo inicializar TextToSpeech (status=$status)")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Vidente conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val node = event?.source ?: return
        val text = extractText(node)
        node.recycle()

        if (text.isNullOrBlank() || text == lastSpoken) return

        lastSpoken = text
        speak(text)
    }

    private fun extractText(node: AccessibilityNodeInfo): String? = when {
        !node.text.isNullOrBlank() -> node.text.toString()
        !node.contentDescription.isNullOrBlank() -> node.contentDescription.toString()
        else -> null
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidenteA11yService"
        private const val UTTERANCE_ID = "vidente_utterance"
    }
}
