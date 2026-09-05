package com.vidente.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.Locale

class VidenteAccessibilityService :
    AccessibilityService(),
    TextToSpeech.OnInitListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken: String? = null
    private var pendingText: String? = null

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private val backendAssistant: ConversationalAssistant by lazy { BackendConversationalAssistant(this) }
    private val unconfiguredAssistant: ConversationalAssistant by lazy { UnconfiguredConversationalAssistant() }

    private fun currentAssistant(): ConversationalAssistant =
        if (VidentePreferences.getBackendUrl(this).isNullOrBlank()) unconfiguredAssistant else backendAssistant

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
        showFloatingButton()
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

    // ---- Modo conversacional ----

    private fun showFloatingButton() {
        val view = LayoutInflater.from(this).inflate(R.layout.floating_conversational_button, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = FLOATING_BUTTON_MARGIN_PX
            y = FLOATING_BUTTON_MARGIN_PX
        }

        view.findViewById<View>(R.id.buttonAskVidente).setOnClickListener {
            startConversationalMode()
        }

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        manager.addView(view, params)
        windowManager = manager
        floatingButton = view
    }

    private fun hideFloatingButton() {
        floatingButton?.let { windowManager?.removeView(it) }
        floatingButton = null
    }

    private fun startConversationalMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak(getString(R.string.conversational_need_mic_permission))
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
                }
            )
            return
        }

        speak(getString(R.string.conversational_listening))
        listenForQuestion { question ->
            if (question.isNullOrBlank()) {
                speak(getString(R.string.conversational_no_question_heard))
                return@listenForQuestion
            }

            val summary = buildScreenSummary()
            currentAssistant().answer(question, summary) { answer -> speak(answer) }
        }
    }

    private fun listenForQuestion(onResult: (String?) -> Unit) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                onResult(text)
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Error de reconocimiento de voz: $error")
                onResult(null)
                recognizer.destroy()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    /**
     * Recorre el árbol de accesibilidad de la pantalla activa y arma un
     * resumen corto (rol + texto) de los elementos con contenido visible,
     * en lugar de leer todo. Este resumen es el contexto que se le pasará
     * a la IA conversacional junto con la pregunta del usuario.
     */
    private fun buildScreenSummary(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        collectNodeSummaries(root, lines, depth = 0)
        root.recycle()
        return lines.joinToString("\n").take(MAX_SUMMARY_CHARS)
    }

    private fun collectNodeSummaries(node: AccessibilityNodeInfo, lines: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || lines.size >= MAX_LINES) return

        describeNode(node)?.let { lines.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeSummaries(child, lines, depth + 1)
            child.recycle()
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String? {
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: return null

        val role = when {
            node.isEditable -> "Campo de texto"
            node.isCheckable -> "Casilla"
            node.isClickable -> "Botón"
            else -> "Texto"
        }
        return "[$role] $label"
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        VidentePreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        hideFloatingButton()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidenteA11yService"
        private const val UTTERANCE_ID = "vidente_utterance"
        private const val FLOATING_BUTTON_MARGIN_PX = 24
        private const val MAX_DEPTH = 12
        private const val MAX_LINES = 60
        private const val MAX_SUMMARY_CHARS = 4000
    }
}
