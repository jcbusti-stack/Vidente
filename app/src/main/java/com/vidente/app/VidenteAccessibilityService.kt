package com.vidente.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    // Aviso ("Principio/Final de la pantalla") pendiente de anteponer a la
    // próxima lectura de elemento tras envolver en la navegación lineal (P5).
    private var boundaryAnnouncement: String? = null

    private enum class TutorialStep { NONE, EXPLORE, DOUBLE_TAP, NAVIGATE, SYSTEM, READING, MODES }
    private var tutorialStep = TutorialStep.NONE
    private val practicedGestures = mutableSetOf<Int>()
    private var pendingTutorial = false
    // Solo se aceptan gestos de práctica cuando la instrucción hablada terminó,
    // para que un evento de foco al arrancar no salte el primer paso ni corte
    // la introducción.
    @Volatile private var tutorialInputEnabled = false

    private val NAVIGATE_GESTURES = listOf(GESTURE_SWIPE_RIGHT, GESTURE_SWIPE_LEFT)
    private val SYSTEM_GESTURES =
        listOf(GESTURE_SWIPE_UP, GESTURE_SWIPE_DOWN_AND_LEFT, GESTURE_SWIPE_DOWN_AND_RIGHT)
    private val READING_GESTURES = listOf(GESTURE_SWIPE_DOWN_AND_UP, GESTURE_SWIPE_UP_AND_DOWN)

    // ---- Lectura continua (P6) ----
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var continuousReading = false
    @Volatile private var continuousPaused = false
    private var continuousLines: List<String> = emptyList()
    private var continuousIndex = 0
    private var continuousStartedAt = 0L

    // ---- Navegación granular y por tipo (P7) ----
    private enum class NavMode(val label: String) {
        ELEMENT("elemento"),
        CHARACTER("carácter"),
        WORD("palabra"),
        LINE("línea"),
        PARAGRAPH("párrafo"),
        HEADING("encabezados"),
        LINK("enlaces"),
        CONTROL("controles"),
        FIELD("campos")
    }
    private var navMode = NavMode.ELEMENT

    // Antirebote solo para los gestos que alternan estado o ciclan (no para
    // deslizar derecha/izquierda, que se encadenan a propósito). Reduce que un
    // único trazo tembloroso cuente dos veces.
    private var lastDebouncedGestureId = -1
    private var lastDebouncedGestureAt = 0L

    // ---- Anuncio de título de pantalla (P8a) ----
    private var lastWindowTitle: String? = null
    private var pendingTitleRunnable: Runnable? = null

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

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == TUTORIAL_UTTERANCE_ID) tutorialInputEnabled = true
                if (utteranceId == CONTINUOUS_UTTERANCE_ID) onContinuousUtteranceDone()
            }

            // Firma obligatoria de la clase abstracta. Reactivamos la práctica
            // igual que en onDone para no dejar el tutorial bloqueado si una
            // locución falla, y cortamos la lectura continua si su locución
            // falla para no quedar en un estado a medias.
            override fun onError(utteranceId: String?) {
                if (utteranceId == TUTORIAL_UTTERANCE_ID) tutorialInputEnabled = true
                if (utteranceId == CONTINUOUS_UTTERANCE_ID) {
                    mainHandler.post { stopContinuousReading() }
                }
            }
        })

        ttsReady = true
        // El primer elemento enfocado puede llegar antes de que el motor TTS
        // termine de inicializarse; lo guardamos para no perder esa lectura.
        pendingText?.let { speak(it) }
        pendingText = null
        if (pendingTutorial) startTutorial()
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

        if (key == VidentePreferences.KEY_TUTORIAL_REQUESTED &&
            VidentePreferences.isTutorialRequested(this)
        ) {
            VidentePreferences.setTutorialRequested(this, false)
            startTutorial()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Vidente conectado")
        showFloatingButton()

        // Tutorial de bienvenida la primera vez que se activa el servicio.
        // Se marca como visto al arrancarlo para no repetirlo en cada
        // reconexión; se puede repasar desde Ajustes.
        if (!VidentePreferences.isTutorialDone(this)) {
            VidentePreferences.setTutorialDone(this, true)
            startTutorial()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (DIAG_MODE) diagLogEvent(event)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> handleFocusEvent(event)

            // Al recorrer texto por carácter, palabra, línea o párrafo (P7) el
            // sistema no lee el fragmento: lo lee Vidente a partir de este evento.
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY ->
                handleTextTraversed(event)

            // En Android 10 o menos el doble toque del tutorial no llega como
            // gesto, pero el sistema lo convierte en un click: lo usamos como
            // señal de que el usuario practicó el paso de "activar".
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                if (tutorialStep == TutorialStep.DOUBLE_TAP) onDoubleTapPracticed()

            // Pantalla o diálogo nuevo: se reinicia el estado dependiente de la
            // pantalla y se anuncia el título de la nueva pantalla (P8a).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onScreenChanged()
                if (tutorialStep == TutorialStep.NONE) handleWindowTitle(event)
            }

            // Base para P8: contenido de ventana, escritura, scroll, selección
            // y anuncios de la app. Por ahora solo se reciben.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> Unit

            else -> Unit
        }
    }

    private fun onScreenChanged() {
        stopContinuousReading()
        navMode = NavMode.ELEMENT
    }

    /** Diagnóstico P8a: registra en Logcat los eventos candidatos a "cambio de pantalla". */
    private fun diagLogEvent(event: AccessibilityEvent) {
        val name = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
            else -> return
        }
        Log.d(DIAG_TAG, "$name pkg=${event.packageName} cls=${event.className} text=${event.text}")
    }

    /**
     * P8a: anuncio del título de la pantalla nueva.
     *
     * BUILD DE DIAGNÓSTICO 2 (DIAG_MODE = true): Vidente narra en voz qué trae
     * cada evento de cambio de ventana. Respecto a la build anterior se ha
     * QUITADO toda llamada a getWindows(), que se sospecha que competía con el
     * despacho de gestos y acciones (los gestos se anunciaban pero no se
     * ejecutaban). Ahora solo se usa el texto del evento y el nombre de la
     * aplicación (PackageManager), y con más retardo.
     */
    private fun handleWindowTitle(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == packageName || pkg == "com.android.systemui") return

        val fromEvent = event.text?.joinToString(" ")?.trim()?.takeIf { it.isNotBlank() }
        val cls = event.className?.toString()?.substringAfterLast('.')

        pendingTitleRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            try {
                resolveAndAnnounceTitle(pkg, cls, fromEvent)
            } catch (e: Exception) {
                Log.e(TAG, "P8a: fallo resolviendo el título de pantalla", e)
            }
        }
        pendingTitleRunnable = r
        mainHandler.postDelayed(r, WINDOW_TITLE_DEBOUNCE_MS)
    }

    private fun resolveAndAnnounceTitle(pkg: String?, cls: String?, fromEvent: String?) {
        val app = appLabel(pkg)
        Log.d(TAG, "P8a titulo: evento='$fromEvent' app='$app' clase='$cls'")

        if (DIAG_MODE) {
            speak("Ventana. Texto: ${fromEvent ?: "vacío"}. Aplicación: ${app ?: "ninguna"}.")
            return
        }

        val title = fromEvent ?: app
        if (title.isNullOrBlank() || title == lastWindowTitle) return
        lastWindowTitle = title
        speak(title)
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Lectura hablada del elemento que recibe el foco o el toque. */
    private fun handleFocusEvent(event: AccessibilityEvent) {
        // Durante el tutorial no se lee nada hasta que termina la instrucción
        // hablada; así el primer paso no se completa solo al arrancar.
        if (tutorialStep != TutorialStep.NONE && !tutorialInputEnabled) return

        // Un toque en la pantalla mientras hay lectura continua activa la pausa
        // (P6). Se ignora la ventana inmediatamente posterior al inicio para
        // que el final del propio gesto de arranque no la corte.
        if (continuousReading && !continuousPaused) {
            if (SystemClock.uptimeMillis() - continuousStartedAt >= CONTINUOUS_START_GUARD_MS) {
                pauseContinuousReading()
            }
            return
        }

        val node = event.source ?: return
        val text = describeForSpeech(node)
        node.recycle()

        if (text.isNullOrBlank()) return

        // Un aviso de borde de pantalla se antepone y salta la deduplicación,
        // para que "Final de la pantalla" no se pierda si el elemento repite
        // texto con el anterior.
        val boundary = boundaryAnnouncement
        boundaryAnnouncement = null
        if (text == lastSpoken && boundary == null) return

        lastSpoken = text
        val toSpeak = if (boundary != null) "$boundary. $text" else text
        if (ttsReady) speak(toSpeak) else pendingText = toSpeak

        if (tutorialStep == TutorialStep.EXPLORE) onExplorePracticed()
    }

    /**
     * Anuncio hablado de un nodo: nombre, rol y estado.
     * Ej: "Wi-Fi, interruptor, activado".
     */
    private fun describeForSpeech(node: AccessibilityNodeInfo): String? {
        val label = findLabel(node) ?: return null
        val parts = mutableListOf(label)
        roleOf(node)?.let { parts.add(it) }
        parts.addAll(statesOf(node))
        return parts.joinToString(", ")
    }

    /** Etiqueta propia del nodo, sin mirar el resto del árbol. */
    private fun ownLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Un Switch suele venir sin texto propio: su etiqueta vive en un TextView
     * hermano (o en un hijo, si el foco cayó sobre la fila que los contiene).
     * Sin esta búsqueda el nodo se descartaba y no se leía nada.
     */
    private fun findLabel(node: AccessibilityNodeInfo): String? =
        ownLabel(node)
            ?: labelFromDescendants(node, depth = 0)
            ?: labelFromSiblings(node)

    private fun labelFromDescendants(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth >= MAX_LABEL_DEPTH) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val label = ownLabel(child) ?: labelFromDescendants(child, depth + 1)
            child.recycle()
            if (label != null) return label
        }
        return null
    }

    private fun labelFromSiblings(node: AccessibilityNodeInfo): String? {
        val parent = node.parent ?: return null
        var label: String? = null

        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            if (sibling != node) {
                label = ownLabel(sibling) ?: labelFromDescendants(sibling, depth = 1)
            }
            sibling.recycle()
            if (label != null) break
        }

        parent.recycle()
        return label
    }

    private fun roleOf(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString().orEmpty()
        return when {
            node.isEditable || className.endsWith("EditText") -> "campo de texto"
            className.endsWith("Switch") ||
                className.endsWith("SwitchCompat") ||
                className.endsWith("SwitchMaterial") ||
                className.endsWith("ToggleButton") -> "interruptor"
            className.endsWith("CheckBox") -> "casilla"
            className.endsWith("RadioButton") -> "opción"
            node.isCheckable -> "casilla"
            className.endsWith("SeekBar") -> "control deslizante"
            className.endsWith("Button") || node.isClickable -> "botón"
            else -> null
        }
    }

    private fun statesOf(node: AccessibilityNodeInfo): List<String> {
        val states = mutableListOf<String>()

        // stateDescription lo define la app y es más preciso que deducirlo.
        val stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        when {
            stateDescription != null -> states.add(stateDescription)
            node.isCheckable -> states.add(if (node.isChecked) "activado" else "desactivado")
        }

        if (node.isSelected) states.add("seleccionado")
        if (!node.isEnabled) states.add("no disponible")

        return states
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Habla una instrucción del tutorial. Deshabilita la práctica hasta que
     * esta locución termine (lo reactiva el UtteranceProgressListener).
     * flush=false encola detrás de la lectura del elemento recién explorado.
     */
    private fun speakTutorial(text: String, flush: Boolean = true) {
        tutorialInputEnabled = false
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, TUTORIAL_UTTERANCE_ID)
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
        val label = ownLabel(node) ?: return null
        val role = (roleOf(node) ?: "texto").replaceFirstChar { it.uppercase() }
        return "[$role] $label"
    }

    // ---- Reparto de gestos (P5 Opción 1, P6, P7) ----

    /**
     * Reparto de gestos de un dedo:
     *
     *   Deslizar a la derecha            -> siguiente, según el modo activo (P5/P7)
     *   Deslizar a la izquierda          -> anterior, según el modo activo (P5/P7)
     *   Deslizar hacia arriba            -> Inicio
     *   Deslizar hacia abajo             -> cambiar de modo de navegación (P7)
     *   Deslizar abajo y volver arriba   -> iniciar/reanudar lectura continua (P6)
     *   Deslizar arriba y volver abajo   -> repetir la última frase (P6)
     *   Deslizar abajo y luego izquierda -> Atrás
     *   Deslizar abajo y luego derecha   -> Recientes
     *   Un toque durante la lectura continua -> pausa (P6)
     *
     * La clasificación de cada trazo la hace el sistema, no Vidente; no se
     * puede ajustar su tolerancia desde un servicio de accesibilidad sin
     * asumir todo el manejo táctil. Para reducir confusiones se aplica un
     * antirebote a los gestos que alternan estado o ciclan, se ignora la
     * ventana justo después de arrancar la lectura continua, y cualquier gesto
     * durante la lectura continua solo la pausa (no ejecuta su acción). Cambiar
     * de modo se anuncia siempre, así un ciclo accidental se deshace ciclando.
     */
    override fun onGesture(gestureId: Int): Boolean {
        if (tutorialStep != TutorialStep.NONE) return handleTutorialGesture(gestureId)

        // Con lectura continua en marcha, el primer gesto solo la pausa.
        if (continuousReading && !continuousPaused) {
            if (SystemClock.uptimeMillis() - continuousStartedAt >= CONTINUOUS_START_GUARD_MS) {
                pauseContinuousReading()
            }
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && gestureId == GESTURE_DOUBLE_TAP) {
            return activateFocusedElement()
        }

        when (gestureId) {
            GESTURE_SWIPE_DOWN_AND_UP -> {
                if (isDebounced(gestureId)) return true
                startOrResumeContinuousReading()
                return true
            }
            GESTURE_SWIPE_UP_AND_DOWN -> {
                if (isDebounced(gestureId)) return true
                repeatLastPhrase()
                return true
            }
            GESTURE_SWIPE_DOWN -> {
                if (isDebounced(gestureId)) return true
                cycleNavMode()
                return true
            }
            GESTURE_SWIPE_RIGHT -> return moveInMode(forward = true)
            GESTURE_SWIPE_LEFT -> return moveInMode(forward = false)
        }

        val (action, spoken) = when (gestureId) {
            GESTURE_SWIPE_UP -> GLOBAL_ACTION_HOME to "Inicio"
            GESTURE_SWIPE_DOWN_AND_LEFT -> GLOBAL_ACTION_BACK to "Atrás"
            GESTURE_SWIPE_DOWN_AND_RIGHT -> GLOBAL_ACTION_RECENTS to "Recientes"
            else -> return false
        }

        val done = performGlobalAction(action)
        if (done && ttsReady) speak(spoken)
        return done
    }

    private fun isDebounced(gestureId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (gestureId == lastDebouncedGestureId && now - lastDebouncedGestureAt < GESTURE_DEBOUNCE_MS) {
            return true
        }
        lastDebouncedGestureId = gestureId
        lastDebouncedGestureAt = now
        return false
    }

    // ---- P6: lectura continua ----

    private fun startOrResumeContinuousReading() {
        if (continuousReading && continuousPaused) {
            continuousPaused = false
            continuousStartedAt = SystemClock.uptimeMillis()
            speakContinuousCurrent()
            return
        }
        if (continuousReading) return

        val root = rootInActiveWindow ?: return
        val nodes = collectNavigable(root)
        root.recycle()
        if (nodes.isEmpty()) return

        val focusedIdx = nodes.indexOfFirst { it.isAccessibilityFocused }
        val start = if (focusedIdx >= 0) focusedIdx else 0
        continuousLines = nodes.drop(start).mapNotNull { describeForSpeech(it) }
        nodes.forEach { it.recycle() }
        if (continuousLines.isEmpty()) return

        continuousIndex = 0
        continuousReading = true
        continuousPaused = false
        continuousStartedAt = SystemClock.uptimeMillis()
        speakContinuousCurrent()
    }

    private fun speakContinuousCurrent() {
        val text = continuousLines.getOrNull(continuousIndex)
        if (text == null) {
            finishContinuousReading()
            return
        }
        lastSpoken = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, CONTINUOUS_UTTERANCE_ID)
    }

    private fun onContinuousUtteranceDone() {
        mainHandler.post {
            if (!continuousReading || continuousPaused) return@post
            continuousIndex++
            if (continuousIndex >= continuousLines.size) {
                finishContinuousReading()
            } else {
                speakContinuousCurrent()
            }
        }
    }

    private fun pauseContinuousReading() {
        if (!continuousReading || continuousPaused) return
        continuousPaused = true
        tts?.stop()
        speak("Pausa")
    }

    private fun stopContinuousReading() {
        if (!continuousReading) return
        continuousReading = false
        continuousPaused = false
        continuousLines = emptyList()
        continuousIndex = 0
        tts?.stop()
    }

    private fun finishContinuousReading() {
        continuousReading = false
        continuousPaused = false
        continuousLines = emptyList()
        continuousIndex = 0
        speak("Fin de la lectura")
    }

    private fun repeatLastPhrase() {
        val last = lastSpoken ?: return
        speak(last)
    }

    // ---- P7: navegación granular y por tipo ----

    private fun cycleNavMode() {
        val values = NavMode.values()
        navMode = values[(navMode.ordinal + 1) % values.size]
        speak(navMode.label)
    }

    private fun moveInMode(forward: Boolean): Boolean = when (navMode) {
        NavMode.ELEMENT -> moveAccessibilityFocus(forward)
        NavMode.CHARACTER -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, forward)
        NavMode.WORD -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, forward)
        NavMode.LINE -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward)
        NavMode.PARAGRAPH -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH, forward)
        NavMode.HEADING, NavMode.LINK, NavMode.CONTROL, NavMode.FIELD -> moveToType(navMode, forward)
    }

    /**
     * Recorre el texto del elemento enfocado por carácter, palabra, línea o
     * párrafo. El fragmento recorrido lo anuncia handleTextTraversed a partir
     * del evento que dispara la acción.
     */
    private fun moveByGranularity(granularity: Int, forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        root.recycle()
        focused ?: return false

        val supported = (focused.movementGranularities and granularity) != 0
        if (!supported) {
            focused.recycle()
            speak("Aquí no hay texto para recorrer")
            return false
        }

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        } else {
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        }
        val done = focused.performAction(action, args)
        focused.recycle()
        if (!done) speak(if (forward) "Final del texto" else "Principio del texto")
        return done
    }

    private fun handleTextTraversed(event: AccessibilityEvent) {
        val full = event.text?.joinToString("") ?: return
        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to <= from || to > full.length) return
        val piece = full.substring(from, to)
        if (piece.isNotBlank()) {
            lastSpoken = piece
            speak(piece)
        }
    }

    /**
     * Salta al siguiente o anterior nodo visible de un tipo dado (encabezado,
     * enlace, control o campo) en orden de lectura, partiendo del elemento
     * enfocado. El nodo destino se anuncia por su evento de foco.
     */
    private fun moveToType(mode: NavMode, forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val all = collectAllNodes(root)
        root.recycle()
        if (all.isEmpty()) return false

        val anchor = all.indexOfFirst { it.isAccessibilityFocused }
        val n = all.size
        var found = -1
        var crossed = false

        if (anchor < 0) {
            val range = if (forward) 0 until n else (n - 1) downTo 0
            for (i in range) {
                if (matchesType(all[i], mode)) { found = i; break }
            }
        } else {
            val step = if (forward) 1 else -1
            var i = anchor
            for (k in 1 until n) {
                val next = i + step
                if (next < 0 || next >= n) crossed = true
                i = (next % n + n) % n
                if (matchesType(all[i], mode)) { found = i; break }
            }
        }

        val result = if (found >= 0) {
            val ok = all[found].performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (ok && crossed) {
                boundaryAnnouncement = if (forward) BOUNDARY_START else BOUNDARY_END
            }
            ok
        } else {
            speak("No hay ${mode.label} en la pantalla")
            false
        }
        all.forEach { it.recycle() }
        return result
    }

    private fun matchesType(node: AccessibilityNodeInfo, mode: NavMode): Boolean {
        if (!node.isVisibleToUser) return false
        return when (mode) {
            NavMode.HEADING -> isHeadingNode(node)
            NavMode.LINK -> isLinkNode(node)
            NavMode.CONTROL -> isControlNode(node)
            NavMode.FIELD -> node.isEditable ||
                node.className?.toString()?.endsWith("EditText") == true
            else -> false
        }
    }

    private fun isHeadingNode(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.isHeading
        } else {
            node.collectionItemInfo?.isHeading == true
        }

    private fun isLinkNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val cn = node.className?.toString() ?: return false
        // Enlaces de texto en apps nativas y en WebView (donde suelen llegar
        // como android.view.View clickeable).
        return cn.endsWith("TextView") || cn == "android.view.View" || cn.contains("Link", ignoreCase = true)
    }

    private fun isControlNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cn = node.className?.toString().orEmpty()
        if (cn.endsWith("Button") || cn.endsWith("Switch") || cn.endsWith("SwitchCompat") ||
            cn.endsWith("SwitchMaterial") || cn.endsWith("CheckBox") || cn.endsWith("RadioButton") ||
            cn.endsWith("SeekBar") || cn.endsWith("Spinner") || cn.endsWith("ToggleButton")
        ) {
            return true
        }
        return node.isCheckable
    }

    /** Todos los nodos visibles en orden de lectura, sin filtrar ni colapsar. */
    @Suppress("DEPRECATION")
    private fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= MAX_ALL_NODES) return
            if (node.isVisibleToUser) out.add(AccessibilityNodeInfo.obtain(node))
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }

        walk(root)
        return out
    }

    /**
     * Mueve el foco de accesibilidad al siguiente o anterior elemento
     * navegable de la ventana activa con ACTION_ACCESSIBILITY_FOCUS. El
     * elemento se anuncia por el evento TYPE_VIEW_ACCESSIBILITY_FOCUSED que
     * dispara la acción. Al pasar del último al primero, o al revés, deja
     * pendiente un aviso de borde de pantalla.
     */
    private fun moveAccessibilityFocus(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = collectNavigable(root)
        root.recycle()
        if (nodes.isEmpty()) return false

        val currentIndex = nodes.indexOfFirst { it.isAccessibilityFocused }
        val lastIndex = nodes.lastIndex
        val (targetIndex, wrapped) = when {
            currentIndex < 0 -> (if (forward) 0 else lastIndex) to false
            forward && currentIndex == lastIndex -> 0 to true
            !forward && currentIndex == 0 -> lastIndex to true
            else -> (currentIndex + if (forward) 1 else -1) to false
        }

        val done = nodes[targetIndex].performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (done && wrapped && targetIndex != currentIndex) {
            boundaryAnnouncement = if (forward) BOUNDARY_START else BOUNDARY_END
        }
        nodes.forEach { it.recycle() }
        return done
    }

    /** Lista, en orden de lectura, los nodos visibles que Vidente sabe anunciar. */
    @Suppress("DEPRECATION")
    private fun collectNavigable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= MAX_NAV_NODES) return
            if (isNavigable(node)) {
                out.add(AccessibilityNodeInfo.obtain(node))
                // Una fila clickeable es una sola parada: no se entra en sus hijos.
                if (node.isClickable) return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }

        walk(root)
        return out
    }

    private fun isNavigable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val interactive = node.isClickable || node.isCheckable || node.isEditable
        if (!interactive && ownLabel(node) == null) return false
        if (!interactive && !node.isEnabled) return false
        return true
    }

    /**
     * Activa el elemento con foco de accesibilidad. Si ese nodo no es
     * clickeable (caso típico: el foco cae sobre el texto de una fila, no
     * sobre la fila), se sube por el árbol hasta el primer ancestro clickeable
     * y se le manda ACTION_CLICK.
     */
    private fun activateFocusedElement(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        root.recycle()
        focused ?: return false

        val target = nearestClickable(focused)
        val done = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (target != null && target != focused) target.recycle()
        focused.recycle()
        return done
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return null
    }

    // ---- Tutorial de bienvenida (P21) ----

    /**
     * Guía por voz los cuatro gestos básicos: explorar (un toque), activar
     * (doble toque), moverse entre elementos (deslizar a la derecha o a la
     * izquierda) y la barra del sistema (arriba para Inicio, abajo en ángulo
     * para Atrás y Recientes). Cada paso explica el gesto, pide practicarlo y
     * confirma en voz antes de avanzar. Mientras el tutorial está activo
     * ningún gesto ejecuta su acción real: solo cuenta como práctica.
     */
    private fun startTutorial() {
        if (!ttsReady) {
            pendingTutorial = true
            return
        }
        pendingTutorial = false
        practicedGestures.clear()
        tutorialStep = TutorialStep.EXPLORE
        speakTutorial("$TUTORIAL_INTRO $TUTORIAL_EXPLORE")
    }

    private fun onExplorePracticed() {
        if (!tutorialInputEnabled) return
        tutorialStep = TutorialStep.DOUBLE_TAP
        speakTutorial("$TUTORIAL_EXPLORE_OK $TUTORIAL_DOUBLE_TAP", flush = false)
    }

    private fun onDoubleTapPracticed() {
        if (tutorialStep != TutorialStep.DOUBLE_TAP || !tutorialInputEnabled) return
        tutorialStep = TutorialStep.NAVIGATE
        practicedGestures.clear()
        speakTutorial("$TUTORIAL_DOUBLE_TAP_OK $TUTORIAL_NAVIGATE $TUTORIAL_NAVIGATE_FIRST")
    }

    private fun handleTutorialGesture(gestureId: Int): Boolean {
        if (!tutorialInputEnabled) return true
        when (tutorialStep) {
            TutorialStep.DOUBLE_TAP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && gestureId == GESTURE_DOUBLE_TAP) {
                    onDoubleTapPracticed()
                }
            TutorialStep.NAVIGATE -> onNavigatePracticed(gestureId)
            TutorialStep.SYSTEM -> onSystemPracticed(gestureId)
            TutorialStep.READING -> onReadingPracticed(gestureId)
            TutorialStep.MODES -> onModesPracticed(gestureId)
            else -> Unit
        }
        return true
    }

    private fun onNavigatePracticed(gestureId: Int) {
        if (gestureId !in NAVIGATE_GESTURES) return

        val firstTime = practicedGestures.add(gestureId)
        val ok = if (gestureId == GESTURE_SWIPE_RIGHT) "Bien, elemento siguiente." else "Bien, elemento anterior."

        if (practicedGestures.containsAll(NAVIGATE_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.SYSTEM
            speakTutorial("$ok $TUTORIAL_SYSTEM $TUTORIAL_SYSTEM_FIRST")
            return
        }

        val next = if (GESTURE_SWIPE_RIGHT !in practicedGestures) {
            "Ahora desliza a la derecha para ir al siguiente."
        } else {
            "Ahora desliza a la izquierda para volver al anterior."
        }
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onSystemPracticed(gestureId: Int) {
        if (gestureId !in SYSTEM_GESTURES) return

        val firstTime = practicedGestures.add(gestureId)
        val ok = when (gestureId) {
            GESTURE_SWIPE_UP -> "Bien, eso es Inicio."
            GESTURE_SWIPE_DOWN_AND_LEFT -> "Bien, eso es Atrás."
            else -> "Bien, eso es Recientes."
        }

        if (practicedGestures.containsAll(SYSTEM_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.READING
            speakTutorial("$ok $TUTORIAL_READING $TUTORIAL_READING_FIRST")
            return
        }

        val next = when {
            GESTURE_SWIPE_UP !in practicedGestures ->
                "Ahora desliza hacia arriba para Inicio."
            GESTURE_SWIPE_DOWN_AND_LEFT !in practicedGestures ->
                "Ahora desliza hacia abajo y luego a la izquierda para Atrás."
            else ->
                "Ahora desliza hacia abajo y luego a la derecha para Recientes."
        }
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onReadingPracticed(gestureId: Int) {
        if (gestureId !in READING_GESTURES) return

        val firstTime = practicedGestures.add(gestureId)
        val ok = if (gestureId == GESTURE_SWIPE_DOWN_AND_UP) {
            "Bien, así se empieza a leer de corrido."
        } else {
            "Bien, así se repite la última frase."
        }

        if (practicedGestures.containsAll(READING_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.MODES
            speakTutorial("$ok $TUTORIAL_MODES $TUTORIAL_MODES_FIRST")
            return
        }

        val next = if (GESTURE_SWIPE_DOWN_AND_UP !in practicedGestures) {
            "Ahora desliza hacia abajo y vuelve arriba sin levantar el dedo, para empezar a leer de corrido."
        } else {
            "Ahora desliza hacia arriba y vuelve abajo sin levantar el dedo, para repetir la última frase."
        }
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onModesPracticed(gestureId: Int) {
        if (gestureId != GESTURE_SWIPE_DOWN) return

        tutorialStep = TutorialStep.NONE
        practicedGestures.clear()
        VidentePreferences.setTutorialDone(this, true)
        speakTutorial("Bien, así se cambia de modo. $TUTORIAL_DONE")
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        VidentePreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        mainHandler.removeCallbacksAndMessages(null)
        hideFloatingButton()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidenteA11yService"
        private const val UTTERANCE_ID = "vidente_utterance"
        private const val TUTORIAL_UTTERANCE_ID = "vidente_tutorial"
        private const val CONTINUOUS_UTTERANCE_ID = "vidente_continuo"
        private const val FLOATING_BUTTON_MARGIN_PX = 24
        private const val MAX_DEPTH = 12
        private const val MAX_LABEL_DEPTH = 3
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 6
        private const val MAX_LINES = 60
        private const val MAX_SUMMARY_CHARS = 4000
        private const val MAX_NAV_NODES = 200
        private const val MAX_ALL_NODES = 500

        // Gestos que alternan estado o ciclan: se ignora una repetición del
        // mismo gesto dentro de esta ventana.
        private const val GESTURE_DEBOUNCE_MS = 350L
        // Tras arrancar la lectura continua se ignoran gestos y toques durante
        // esta ventana, para que el final del propio gesto de arranque no la
        // pause de inmediato.
        private const val CONTINUOUS_START_GUARD_MS = 700L

        private const val BOUNDARY_START = "Principio de la pantalla"
        private const val BOUNDARY_END = "Final de la pantalla"

        private const val WINDOW_TITLE_DEBOUNCE_MS = 500L

        // Build de diagnóstico de P8a: Vidente narra qué trae cada evento de
        // cambio de ventana y registra en Logcat los eventos candidatos.
        // Poner en false cuando P8a quede resuelto.
        private const val DIAG_MODE = true
        private const val DIAG_TAG = "VidenteDiag"

        // ---- Textos del tutorial de bienvenida (P21) ----
        private const val TUTORIAL_INTRO =
            "Bienvenido a Vidente. Vamos a practicar los gestos, uno a uno. " +
                "Puedes repetir este tutorial cuando quieras desde Ajustes."
        private const val TUTORIAL_EXPLORE =
            "Primer gesto: explorar. Apoya un dedo en la pantalla y muévelo despacio. " +
                "Vidente te irá leyendo lo que hay bajo tu dedo, sin activar nada. " +
                "Pruébalo ahora: toca cualquier parte de la pantalla."
        private const val TUTORIAL_EXPLORE_OK = "Muy bien. Eso es explorar."
        private const val TUTORIAL_DOUBLE_TAP =
            "Segundo gesto: activar. Da dos toques rápidos en cualquier parte de la pantalla. " +
                "No hace falta tocar justo encima del elemento: se activa el último que Vidente leyó. " +
                "Pruébalo ahora: dos toques rápidos."
        private const val TUTORIAL_DOUBLE_TAP_OK = "Muy bien. Eso es activar."
        private const val TUTORIAL_NAVIGATE =
            "Tercer gesto: moverte por la pantalla, elemento por elemento. " +
                "Desliza un dedo a la derecha para ir al elemento siguiente, " +
                "y a la izquierda para volver al anterior. " +
                "Vidente te leerá cada elemento al llegar. Vamos a probar los dos."
        private const val TUTORIAL_NAVIGATE_FIRST =
            "Empieza deslizando a la derecha para ir al siguiente."
        private const val TUTORIAL_SYSTEM =
            "Cuarto y último gesto: la barra del sistema. " +
                "Desliza hacia arriba para ir a Inicio. " +
                "Desliza hacia abajo y luego a la izquierda, en un solo movimiento, para Atrás. " +
                "Y hacia abajo y luego a la derecha para Recientes. Vamos a probar los tres."
        private const val TUTORIAL_SYSTEM_FIRST =
            "Empieza deslizando hacia arriba para Inicio."
        private const val TUTORIAL_READING =
            "Quinto gesto: leer de corrido. Desliza hacia abajo y vuelve arriba sin levantar " +
                "el dedo, y Vidente empezará a leer desde donde estás hasta el final. " +
                "Un toque en la pantalla lo pausa; el mismo gesto de abajo y arriba lo reanuda. " +
                "Y deslizando hacia arriba y volviendo abajo se repite la última frase. " +
                "Vamos a probar esos dos."
        private const val TUTORIAL_READING_FIRST =
            "Empieza deslizando hacia abajo y volviendo arriba sin levantar el dedo."
        private const val TUTORIAL_MODES =
            "Sexto y último gesto: cambiar de modo. Deslizando hacia abajo, recto, se va " +
                "cambiando entre modos: elemento, carácter, palabra, línea, párrafo, encabezados, " +
                "enlaces, controles y campos. Vidente dice el modo al cambiar. Luego, deslizar a " +
                "la derecha o a la izquierda te mueve según el modo elegido."
        private const val TUTORIAL_MODES_FIRST =
            "Pruébalo ahora: desliza hacia abajo, recto."
        private const val TUTORIAL_DONE =
            "El tutorial terminó. Puedes repetirlo cuando quieras desde Ajustes, con el botón " +
                "Repetir tutorial."
    }
}
