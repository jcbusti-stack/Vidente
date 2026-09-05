package com.vidente.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class VidenteAccessibilityService :
    AccessibilityService(),
    TextToSpeech.OnInitListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken: String? = null
    private var pendingText: String? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        VidentePreferences.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(TAG, "No se pudo inicializar TextToSpeech (status=$status)")
            return
        }

        engine.language = Locale.getDefault()
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        applyPreferences(engine)

        ttsReady = true
        // El primer elemento enfocado puede llegar antes de que el motor TTS
        // termine de inicializarse; lo guardamos para no perder esa lectura.
        pendingText?.let { speak(it) }
        pendingText = null
    }

    private fun applyPreferences(engine: TextToSpeech) {
        engine.setSpeechRate(VidentePreferences.getRate(this))
        engine.setPitch(VidentePreferences.getPitch(this))

        val savedVoiceName = VidentePreferences.getVoiceName(this)
        val voices = VoiceUtils.availableVoicesForLocale(engine, Locale.getDefault())
        val voice = voices.firstOrNull { it.name == savedVoiceName }
            ?: VoiceUtils.bestVoiceForLocale(engine, Locale.getDefault())
        voice?.let { engine.voice = it }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        tts?.let { applyPreferences(it) }
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
        if (ttsReady) speak(text) else pendingText = text
    }

    private fun extractText(node: AccessibilityNodeInfo): String? = when {
        !node.text.isNullOrBlank() -> node.text.toString()
        !node.contentDescription.isNullOrBlank() -> node.contentDescription.toString()
        else -> null
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        VidentePreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidenteA11yService"
        private const val UTTERANCE_ID = "vidente_utterance"
    }
}
