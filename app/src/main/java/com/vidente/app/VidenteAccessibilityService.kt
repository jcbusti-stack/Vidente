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

    private enum class TutorialStep { NONE, EXPLORE, DOUBLE_TAP, NAVIGATE, SYSTEM }
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
            }

            // Firma obligatoria de la clase abstracta. Reactivamos la práctica
            // igual que en onDone para no dejar el tutorial bloqueado si una
            // locución falla.
            override fun onError(utteranceId: String?) {
                if (utteranceId == TUTORIAL_UTTERANCE_ID) tutorialInputEnabled = true
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
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> handleFocusEvent(event)

            // En Android 10 o menos el doble toque del tutorial no llega como
            // gesto, pero el sistema lo convierte en un click: lo usamos como
            // señal de que el usuario practicó el paso de "activar".
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                if (tutorialStep == TutorialStep.DOUBLE_TAP) onDoubleTapPracticed()

            // Base para P8: cambios de pantalla y ventana, diálogos, escritura,
            // scroll, selección y anuncios de la app. Por ahora solo se
            // reciben; el anuncio hablado de cada uno se implementa en P8.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> Unit

            else -> Unit
        }
    }

    /** Lectura hablada del elemento que recibe el foco o el toque. */
    private fun handleFocusEvent(event: AccessibilityEvent) {
        // Durante el tutorial no se lee nada hasta que termina la instrucción
        // hablada; así el primer paso no se completa solo al arrancar.
        if (tutorialStep != TutorialStep.NONE && !tutorialInputEnabled) return

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

    // ---- Reparto de gestos (P5, Opción 1) ----

    /**
     * Reparto de gestos de un dedo:
     *
     *   Deslizar a la derecha            -> elemento siguiente (P5)
     *   Deslizar a la izquierda          -> elemento anterior  (P5)
     *   Deslizar hacia arriba            -> Inicio
     *   Deslizar abajo y luego izquierda -> Atrás
     *   Deslizar abajo y luego derecha   -> Recientes
     *   Deslizar hacia abajo             -> libre (reservado para P7)
     *
     * Siguiente/anterior van en el gesto más fácil y en el mismo sentido que
     * en el resto de lectores de pantalla. Atrás y Recientes pasan a gestos en
     * ángulo para dejar libres los deslizamientos horizontales.
     *
     * El doble toque (P4) activa el elemento enfocado con un ACTION_CLICK
     * explícito sobre el ancestro clickeable más cercano. GESTURE_DOUBLE_TAP
     * solo existe desde API 30; en versiones anteriores se conserva el
     * comportamiento por defecto del sistema. Los botones del sistema viven en
     * com.android.systemui y no responden a ACTION_CLICK: la vía correcta es
     * performGlobalAction.
     */
    override fun onGesture(gestureId: Int): Boolean {
        if (tutorialStep != TutorialStep.NONE) return handleTutorialGesture(gestureId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && gestureId == GESTURE_DOUBLE_TAP) {
            return activateFocusedElement()
        }

        when (gestureId) {
            GESTURE_SWIPE_RIGHT -> return moveAccessibilityFocus(forward = true)
            GESTURE_SWIPE_LEFT -> return moveAccessibilityFocus(forward = false)
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
            tutorialStep = TutorialStep.NONE
            practicedGestures.clear()
            VidentePreferences.setTutorialDone(this, true)
            speakTutorial("$ok $TUTORIAL_DONE")
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
        private const val TUTORIAL_UTTERANCE_ID = "vidente_tutorial"
        private const val FLOATING_BUTTON_MARGIN_PX = 24
        private const val MAX_DEPTH = 12
        private const val MAX_LABEL_DEPTH = 3
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 6
        private const val MAX_LINES = 60
        private const val MAX_SUMMARY_CHARS = 4000
        private const val MAX_NAV_NODES = 200

        private const val BOUNDARY_START = "Principio de la pantalla"
        private const val BOUNDARY_END = "Final de la pantalla"

        // ---- Textos del tutorial de bienvenida (P21) ----
        private const val TUTORIAL_INTRO =
            "Bienvenido a Vidente. Vamos a practicar los cuatro gestos básicos. " +
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
        private const val TUTORIAL_DONE =
            "El tutorial terminó. Ya conoces los cuatro gestos: explorar con un toque, " +
                "activar con dos toques, moverte con un dedo a la derecha o a la izquierda, " +
                "y la barra del sistema deslizando hacia arriba para Inicio o hacia abajo " +
                "en ángulo para Atrás y Recientes. " +
                "Puedes repetirlo cuando quieras desde Ajustes, con el botón Repetir tutorial."
    }
}
