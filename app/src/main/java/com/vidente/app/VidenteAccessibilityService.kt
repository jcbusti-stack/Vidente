package com.vidente.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.SoundPool
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale

class VidenteAccessibilityService :
    AccessibilityService(),
    TextToSpeech.OnInitListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Motor secundario (P9: motor de voz dual). Queda configurado y listo
    // para usarse, pero por ahora ningún aviso automático lo dispara todavía
    // (no existen avisos puntuales como hora/batería/notificaciones en
    // Vidente) -- solo lo usa el botón de prueba en Ajustes.
    private var ttsSecondary: TextToSpeech? = null
    private var ttsSecondaryReady = false
    // Estado de habla de cada voz: para no pisar la lectura principal con un
    // aviso puntual (se encola y se dice recién cuando termina la frase
    // actual) y para pedir/soltar el ducking de audio de otras apps mientras
    // cualquiera de las dos voces esté hablando.
    private var primarySpeaking = false
    private var secondarySpeaking = false
    private val pendingSecondaryQueue = mutableListOf<String>()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var duckingReleaseRunnable: Runnable? = null
    // Momento del último ACTION_USER_PRESENT; ver el comentario en speak().
    private var lastUnlockAt = 0L
    // Control de ruido del aviso de notificaciones; ver handleNotification().
    private var lastNotifiedPackage: String? = null
    private var lastNotifiedAt = 0L
    private var lastSpoken: String? = null
    private var lastSpokenAt = 0L
    private var pendingText: String? = null
    // Copia del último elemento que Vidente leyó; ancla de respaldo para P5.
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var lastFocusedNodeAt = 0L
    // El gesto que se está ejecutando viene justo después de un toque directo
    // en la pantalla. Lo anota onGesture antes de limpiar lastHoverAt; lo usa
    // moveAccessibilityFocus para anclarse en el elemento que el usuario acaba
    // de escuchar en vez de en el foco del sistema, que recién después de un
    // toque todavía no es de fiar.
    private var gestureFollowsRecentHover = false
    // Penúltimo elemento leído. El segundo toque del doble toque vuelve a
    // generar exploración: si cae un pelo desviado, el sistema anuncia la tecla
    // vecina y esa pasaría a ser "la actual" justo antes de activar. Cuando el
    // elemento actual se registró hace muy poco (es del propio doble toque) se
    // activa este, que es el que el usuario escuchó y quiso pulsar.
    private var prevFocusedNode: AccessibilityNodeInfo? = null
    private var prevFocusedNodeAt = 0L
    private var treeWalkBudget = 0

    // Aviso ("Principio/Final de la pantalla") pendiente de anteponer a la
    // próxima lectura de elemento tras envolver en la navegación lineal (P5).
    private var boundaryAnnouncement: String? = null

    // Momento del último cambio de pantalla. Sirve para descartar el evento de
    // scroll que dispara un contenedor recién abierto (p. ej. una carpeta del
    // launcher), que llega "al final" sin que el usuario haya desplazado nada.
    private var lastScreenChangeAt = 0L
    // Se pone a true en cuanto el usuario toca o gesticula sobre la pantalla
    // nueva. Mientras sea false, un scroll es de la propia app (p. ej. WhatsApp
    // baja al último mensaje al abrir un chat) y no se comenta.
    private var interactedSinceScreenChange = false
    // Momento del último TYPE_VIEW_HOVER_ENTER (exploración con el dedo).
    // Mientras el dedo manda, se ignora el foco de entrada (TYPE_VIEW_FOCUSED)
    // que disparan algunas apps y teclados en un elemento vecino, que hacía
    // saltar la lectura a otra tecla o a otro icono.
    private var lastHoverAt = 0L

    // Vibración corta al posar el dedo sobre un elemento: confirmación táctil
    // de que hay algo debajo, además del anuncio hablado.
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "No hay vibrador disponible", e)
            null
        }
    }
    // Momento del último evento de scroll "de en medio" (ni principio ni fin).
    // Solo se anuncia un borde si hubo uno reciente: así el salto que hace una
    // app al abrir (Telegram al último mensaje) no dispara "principio/final".
    private var lastMidScrollAt = 0L

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

    /**
     * Configuración de gestos (Paso 1: solo reorganización interna, sin
     * pantalla propia todavía). Cada acción que Vidente sabe ejecutar por un
     * gesto de un dedo, listada una sola vez acá en vez de quedar repartida
     * (y "quemada") dentro de onGesture(). El mapa de abajo dice qué gesto la
     * dispara hoy; en un paso futuro ese mapa va a poder cargarse desde
     * Ajustes en vez de quedar fijo en los valores por defecto.
     *
     * El doble toque conserva su regla histórica de "consumir siempre" (ver
     * el comentario de la tecla de borrar trabada en onGesture), pero QUÉ
     * hace ya sale de este mapa como cualquier otro gesto: esa regla es del
     * gesto en sí, no de la acción que tenga asignada.
     */
    private enum class GestureAction {
        ACTIVATE,
        NEXT,
        PREVIOUS,
        CYCLE_NAV_MODE,
        START_CONTINUOUS_READING,
        REPEAT_LAST_PHRASE,
        GO_HOME,
        GO_BACK,
        GO_RECENTS,
        CURSOR_TO_FIELD_START,
        CURSOR_TO_FIELD_END,
        CYCLE_TTS_ENGINE
    }

    // Los valores por defecto viven en GestureConfig, compartidos con la
    // pantalla de Ajustes, para que las dos listas no se desincronicen.
    private fun defaultGestureActionMap(): Map<Int, GestureAction> =
        GestureConfig.DEFAULT_MAP.mapNotNull { (gestureId, actionName) ->
            gestureActionOf(actionName)?.let { gestureId to it }
        }.toMap()

    private fun gestureActionOf(actionName: String): GestureAction? = try {
        GestureAction.valueOf(actionName)
    } catch (e: Exception) {
        null
    }

    // Var (no val): se reemplaza por loadGestureActionMap() apenas arranca
    // el servicio (ver onServiceConnected); este valor inicial es solo por
    // si algo llega a leerla antes de esa primera carga.
    private var gestureActionMap: Map<Int, GestureAction> = defaultGestureActionMap()

    /**
     * Configuración de gestos (paso 2): lee el mapa guardado en
     * VidentePreferences y lo convierte de texto a GestureAction. Si no hay
     * nada guardado todavía (primera vez), o si lo guardado no se pudo
     * interpretar (corrupto, o nombres de una versión vieja/nueva
     * incompatible), usa y GUARDA los valores por defecto -- así siempre
     * queda algo concreto en el disco, listo para que el paso 3 (pantalla de
     * Ajustes) lo lea y modifique en vez de partir de cero.
     */
    private fun loadGestureActionMap(): Map<Int, GestureAction> {
        val saved = VidentePreferences.getGestureActionMap(this)
        if (saved != null) {
            // Un mapa vacío es válido (el usuario dejó todas las acciones sin
            // gesto): se respeta tal cual, no se reemplaza por los valores
            // por defecto. Solo se descartan entradas sueltas con un nombre
            // de acción que esta versión no conoce.
            val parsed = mutableMapOf<Int, GestureAction>()
            saved.forEach { (gestureId, actionName) ->
                gestureActionOf(actionName)?.let { parsed[gestureId] = it }
            }
            return parsed
        }
        val defaults = defaultGestureActionMap()
        saveGestureActionMap(defaults)
        return defaults
    }

    private fun saveGestureActionMap(map: Map<Int, GestureAction>) {
        VidentePreferences.setGestureActionMap(this, map.mapValues { it.value.name })
    }

    // Acciones que hoy tienen antirebote de 350 ms (ver isDebounced): las que
    // se encadenan o alternan estado. Las demás no lo tenían y siguen sin
    // tenerlo.
    private val DEBOUNCED_ACTIONS = setOf(
        GestureAction.NEXT,
        GestureAction.PREVIOUS,
        GestureAction.CYCLE_NAV_MODE,
        GestureAction.START_CONTINUOUS_READING,
        GestureAction.REPEAT_LAST_PHRASE
    )

    // ---- Lectura continua (P6) ----
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var continuousReading = false
    @Volatile private var continuousPaused = false
    private var continuousLines: List<String> = emptyList()
    private var continuousIndex = 0
    private var continuousStartedAt = 0L

    // ---- Navegación granular y por tipo (P7) ----
    private enum class NavMode(val labelRes: Int) {
        ELEMENT(R.string.nav_mode_element),
        CHARACTER(R.string.nav_mode_character),
        WORD(R.string.nav_mode_word),
        LINE(R.string.nav_mode_line),
        PARAGRAPH(R.string.nav_mode_paragraph),
        HEADING(R.string.nav_mode_heading),
        LINK(R.string.nav_mode_link),
        CONTROL(R.string.nav_mode_control),
        FIELD(R.string.nav_mode_field)
    }
    private var navMode = NavMode.ELEMENT

    // Antirebote solo para los gestos que alternan estado o ciclan (no para
    // deslizar derecha/izquierda, que se encadenan a propósito). Reduce que un
    // único trazo tembloroso cuente dos veces.
    private var lastDebouncedGestureId = -1
    private var lastDebouncedGestureAt = 0L

    // ---- Anuncio de título de pantalla (P8a) y de contexto (P8b, P8c) ----
    private var lastWindowTitle: String? = null
    private var pendingTitleRunnable: Runnable? = null
    private var pendingKeyboardRunnable: Runnable? = null
    private var pendingHoverSpeakRunnable: Runnable? = null
    // Eco de borrado agrupado por pausa: mientras se mantiene la tecla de
    // borrar presionada, se acumula lo borrado en vez de anunciarlo carácter
    // por carácter; se anuncia recién cuando pasa DELETE_ECHO_DEBOUNCE_MS sin
    // un nuevo borrado (tecla soltada, o una pausa natural del repetido).
    private var pendingDeleteText: String = ""
    private var pendingDeleteRunnable: Runnable? = null
    private var pendingScrollRunnable: Runnable? = null
    private var lastScrollBoundary: String? = null      // "fin" | "inicio" | null
    private var lastSpokenScrollPos: String? = null     // dedupe del porcentaje hablado
    // P8c: aviso de posición al hacer scroll como tono continuo (grave =
    // principio, agudo = final) que sigue al dedo en tiempo real, o como
    // porcentaje hablado. Configurable en Ajustes.
    private var scrollFeedbackTone = true
    private var soundPool: SoundPool? = null
    private var scrollToneSoundId = 0
    private var scrollToneLoaded = false
    private var scrollBlipRate = 1f          // velocidad de reproducción actual (posición)
    private var scrollBlipRunnable: Runnable? = null
    // Visibilidad del teclado por heurística (sin getWindows(), que rompía el
    // despacho de gestos): se marca visible al ver eventos de un método de
    // entrada o al enfocar un campo, y oculto al cambiar de pantalla o pulsar
    // Atrás. imePackages son los paquetes de los teclados instalados.
    private var keyboardVisible = false
    private val imePackages = mutableSetOf<String>()

    // P8c parte 2: eco de escritura. Qué se dice al teclear en un campo de
    // texto (nada / caracteres / palabras / ambos). Configurable en Ajustes.
    private var typingEcho = VidentePreferences.DEFAULT_TYPING_ECHO

    // Modo de escritura en teclado: doble toque (de siempre) o deslizar y
    // soltar. Configurable en Ajustes; por defecto queda el de siempre.
    private var keyboardWriteMode = VidentePreferences.DEFAULT_KEYBOARD_WRITE_MODE

    // Anuncio de posición del cursor: al moverlo en un campo de texto, decir
    // "Principio/Final del texto" en los extremos, leer el carácter recorrido,
    // y leer la selección si hay texto seleccionado. Configurable en Ajustes;
    // por defecto activado.
    private var cursorAnnounceEnabled = true
    private var announceUppercase = true
    private var announceSpellingExample = false
    private var announceKeyboardExploration = true
    private var lastCursorIndex = -1
    // Identifica el campo al que corresponde lastCursorIndex (id de vista, o
    // el propio texto leído si no tiene id), para no reiniciar la referencia
    // cuando se vuelve a tocar el MISMO campo ya enfocado: la exploración
    // táctil re-enfoca y re-anuncia el campo en cada toque, y si eso también
    // reiniciara la referencia, el toque para reposicionar el cursor caería
    // siempre en "primer movimiento tras enfocar" y nunca se anunciaría.
    private var lastCursorFieldKey: String? = null
    // Tras un movimiento de P7 (recorrer por carácter/palabra/línea/párrafo,
    // que ya lee el fragmento y ya anuncia el borde) o al escribir/borrar (que
    // ya tiene su propio eco) llega un cambio de selección: se ignora hasta
    // este instante para no leer todo dos veces.
    private var suppressCursorEchoUntil = 0L

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private val backendAssistant: ConversationalAssistant by lazy { BackendConversationalAssistant(this) }
    private val unconfiguredAssistant: ConversationalAssistant by lazy { UnconfiguredConversationalAssistant() }

    private fun currentAssistant(): ConversationalAssistant =
        if (VidentePreferences.getBackendUrl(this).isNullOrBlank()) unconfiguredAssistant else backendAssistant

    override fun onCreate() {
        super.onCreate()
        createPrimaryTts(VidentePreferences.getEnginePackage(this))
        createSecondaryTts(VidentePreferences.getSecondaryEnginePackage(this))
        VidentePreferences.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    /**
     * Motor principal (P9: motor de voz dual). Si el motor elegido en
     * Ajustes falla al iniciar (p. ej. no está instalado o no soporta el
     * idioma), vuelve sola al motor predeterminado del sistema en vez de
     * dejar a Vidente sin voz.
     */
    private fun createPrimaryTts(enginePackage: String?) {
        ttsReady = false
        tts = if (enginePackage != null) TextToSpeech(this, this, enginePackage) else TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(TAG, "No se pudo inicializar TextToSpeech (status=$status)")
            if (VidentePreferences.getEnginePackage(this) != null) {
                Log.w(TAG, "El motor de voz principal elegido falló: vuelve al del sistema")
                VidentePreferences.setEnginePackage(this, null)
                createPrimaryTts(null)
            }
            return
        }

        engine.language = LocaleHelper.currentLocale(this)
        applyPreferences(engine)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                primarySpeaking = true
                onSpeechStateChanged()
            }

            override fun onDone(utteranceId: String?) {
                primarySpeaking = false
                onSpeechStateChanged()
                flushPendingSecondary()
                if (utteranceId == TUTORIAL_UTTERANCE_ID) tutorialInputEnabled = true
                if (utteranceId == CONTINUOUS_UTTERANCE_ID) onContinuousUtteranceDone()
            }

            // Una locución flushada por la siguiente (QUEUE_FLUSH, lo normal
            // al leer rápido) para acá, no a onDone/onError: sin esto, un
            // aviso puntual que quedó esperando (ver speakSecondary) podía
            // quedar esperando de más si la locución en curso nunca llegaba a
            // terminar por su cuenta.
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                primarySpeaking = false
                onSpeechStateChanged()
                flushPendingSecondary()
            }

            // Firma obligatoria de la clase abstracta. Reactivamos la práctica
            // igual que en onDone para no dejar el tutorial bloqueado si una
            // locución falla, y cortamos la lectura continua si su locución
            // falla para no quedar en un estado a medias.
            override fun onError(utteranceId: String?) {
                primarySpeaking = false
                onSpeechStateChanged()
                flushPendingSecondary()
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

    /**
     * Ruta de audio. Por defecto USAGE_MEDIA, que sale por el Bluetooth activo
     * igual que la música; algunos teléfonos no enrutan el canal de
     * accesibilidad al Bluetooth. La usan tanto el TTS como el tono de scroll.
     */
    private fun buildAudioAttributes(): AudioAttributes {
        val usage = if (VidentePreferences.getAudioOutput(this) == VidentePreferences.AUDIO_OUTPUT_ACCESSIBILITY) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun applyPreferences(engine: TextToSpeech) {
        engine.setAudioAttributes(buildAudioAttributes())

        engine.setSpeechRate(VidentePreferences.getRate(this))
        engine.setPitch(VidentePreferences.getPitch(this))

        engine.language = LocaleHelper.currentLocale(this)
        val savedVoiceName = VidentePreferences.getVoiceName(this)
        val voices = VoiceUtils.availableVoicesForLocale(engine, LocaleHelper.currentLocale(this))
        val voice = voices.firstOrNull { it.name == savedVoiceName }
            ?: VoiceUtils.bestVoiceForLocale(engine, LocaleHelper.currentLocale(this))
        voice?.let { engine.voice = it }
    }

    /**
     * Motor secundario (P9: motor de voz dual), con el mismo resguardo que
     * el principal: si el motor elegido falla al iniciar, vuelve sola al
     * predeterminado del sistema en vez de quedar sin motor secundario.
     */
    private fun createSecondaryTts(enginePackage: String?) {
        ttsSecondaryReady = false
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = ttsSecondary
            if (status != TextToSpeech.SUCCESS || engine == null) {
                Log.e(TAG, "No se pudo inicializar el TTS secundario (status=$status)")
                if (VidentePreferences.getSecondaryEnginePackage(this) != null) {
                    Log.w(TAG, "El motor de voz secundario elegido falló: vuelve al del sistema")
                    VidentePreferences.setSecondaryEnginePackage(this, null)
                    createSecondaryTts(null)
                }
                return@OnInitListener
            }
            applySecondaryPreferences(engine)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    secondarySpeaking = true
                    onSpeechStateChanged()
                }

                override fun onDone(utteranceId: String?) {
                    secondarySpeaking = false
                    onSpeechStateChanged()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    secondarySpeaking = false
                    onSpeechStateChanged()
                }

                override fun onError(utteranceId: String?) {
                    secondarySpeaking = false
                    onSpeechStateChanged()
                }
            })
            ttsSecondaryReady = true
        }
        ttsSecondary = if (enginePackage != null) {
            TextToSpeech(this, listener, enginePackage)
        } else {
            TextToSpeech(this, listener)
        }
    }

    private fun applySecondaryPreferences(engine: TextToSpeech) {
        engine.setAudioAttributes(buildAudioAttributes())
        engine.setSpeechRate(VidentePreferences.getRate(this))
        engine.setPitch(VidentePreferences.getPitch(this))
        engine.language = LocaleHelper.currentLocale(this)
        val savedVoiceName = VidentePreferences.getSecondaryVoiceName(this)
        val voices = VoiceUtils.availableVoicesForLocale(engine, LocaleHelper.currentLocale(this))
        val voice = voices.firstOrNull { it.name == savedVoiceName }
            ?: VoiceUtils.bestVoiceForLocale(engine, LocaleHelper.currentLocale(this))
        voice?.let { engine.voice = it }
    }

    /**
     * Habla por el motor secundario (avisos puntuales: hora al desbloquear,
     * batería baja, etc.). QUEUE_ADD, no QUEUE_FLUSH: si dos avisos puntuales
     * llegan casi juntos (p. ej. la hora al desbloquear justo cuando la
     * batería está baja), se escuchan uno después del otro completos, sin
     * que uno corte al otro a la mitad.
     *
     * Si la voz principal está hablando en este momento, el aviso NO se dice
     * ya mismo (al ser motores independientes, sonarían los dos a la vez,
     * pisándose): se guarda en pendingSecondaryQueue y se dice recién cuando
     * la locución principal en curso termine (ver flushPendingSecondary,
     * llamada desde el UtteranceProgressListener de la voz principal).
     */
    private fun speakSecondary(text: String) {
        if (!ttsSecondaryReady) return
        if (primarySpeaking) {
            pendingSecondaryQueue.add(text)
            return
        }
        ttsSecondary?.speak(text, TextToSpeech.QUEUE_ADD, null, SECONDARY_UTTERANCE_ID)
    }

    private fun flushPendingSecondary() {
        if (pendingSecondaryQueue.isEmpty()) return
        val texts = pendingSecondaryQueue.toList()
        pendingSecondaryQueue.clear()
        texts.forEach { speakSecondary(it) }
    }

    /**
     * Ducking de audio (P... nuevo): mientras cualquiera de las dos voces
     * esté hablando, se pide audio focus transitorio con "puede bajar el
     * volumen" (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) -- Android baja solo el
     * volumen de música/otros audios mientras se sostiene, y lo devuelve al
     * soltarlo. No hacía falta pedirlo hasta ahora porque Vidente no pedía
     * ningún audio focus.
     */
    private fun onSpeechStateChanged() {
        updateAudioDucking(primarySpeaking || secondarySpeaking)
    }

    private fun updateAudioDucking(speaking: Boolean) {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (speaking) {
            // Si había un soltado pendiente (ver rama else) de una locución
            // anterior muy cercana, se cancela: seguimos sosteniendo el mismo
            // focus en vez de soltarlo y volver a pedirlo enseguida.
            duckingReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
            duckingReleaseRunnable = null
            if (audioFocusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                // USAGE_ASSISTANCE_ACCESSIBILITY siempre para este pedido,
                // sin importar qué salida eligió el usuario para el TTS
                // (buildAudioAttributes() puede ser USAGE_MEDIA, elegido por
                // temas de Bluetooth): Android trata el ducking pedido por
                // este uso de forma más confiable, pensado justo para que un
                // lector de pantalla baje el volumen de otros audios.
                .setAudioAttributes(duckingAudioAttributes())
                // Sin manejo especial: si algo de más prioridad (una llamada)
                // se queda con el focus, Vidente sigue hablando igual y
                // simplemente vuelve a pedirlo en la próxima locución.
                .setOnAudioFocusChangeListener { }
                .build()
            try {
                audioManager.requestAudioFocus(request)
                audioFocusRequest = request
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo pedir audio focus para el ducking", e)
            }
        } else {
            // Se suelta con un pequeño margen, no al instante: una seguidilla
            // de locuciones cortas (explorar tocando varios elementos
            // seguidos) no debe soltar y volver a pedir el focus en cada una
            // -- eso no le daba tiempo a Android de aplicar la baja de
            // volumen antes de devolverla.
            duckingReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                audioFocusRequest?.let {
                    try {
                        audioManager.abandonAudioFocusRequest(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo soltar el audio focus", e)
                    }
                }
                audioFocusRequest = null
            }
            duckingReleaseRunnable = r
            mainHandler.postDelayed(r, DUCKING_RELEASE_DELAY_MS)
        }
    }

    private fun duckingAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    // P9 aviso de carga completa: solo la primera vez que se llega a 100%,
    // no cada vez que llega ACTION_BATTERY_CHANGED (que es muy seguido).
    // Se vuelve a habilitar recién cuando el nivel baja de 100% de nuevo.
    private var batteryWasFull = false

    /**
     * P9 avisos puntuales, por la voz secundaria. Cada uno usa un evento
     * oficial de Android (documentación pública, no algo deducido de otra
     * app):
     * - ACTION_USER_PRESENT: el usuario acaba de desbloquear el teléfono. El
     *   formato de hora usa la configuración del propio teléfono (12/24
     *   horas, idioma), con la función de Android para eso.
     * - ACTION_BATTERY_LOW: el propio Android cruzó su umbral de batería
     *   baja (el mismo que dispara su diálogo nativo) -- un solo aviso por
     *   cada vez que se cruza, sin que Vidente tenga que vigilar el
     *   porcentaje ni evitar avisos repetidos por su cuenta.
     * - ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED: se enchufó o
     *   desenchufó el cargador (cualquier tipo: cable, inalámbrico).
     * - ACTION_BATTERY_CHANGED: llega seguido con el nivel actual; se usa
     *   solo para calcular el porcentaje y avisar carga completa exacta.
     * - ACTION_AIRPLANE_MODE_CHANGED: se activó o desactivó el modo avión.
     */
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    // Se anota el momento SIEMPRE (aunque el aviso de hora
                    // esté desactivado): es lo que usa speak() para no pisar
                    // el aviso de hora si estuviera activado. Sin costo si
                    // no se usa.
                    lastUnlockAt = SystemClock.uptimeMillis()
                    if (!VidentePreferences.getAnnounceTimeOnUnlock(this@VidenteAccessibilityService)) return
                    val formatted =
                        android.text.format.DateFormat.getTimeFormat(this@VidenteAccessibilityService)
                            .format(Date())
                    speakSecondary(formatted)
                }
                Intent.ACTION_BATTERY_LOW -> {
                    if (!VidentePreferences.getAnnounceLowBattery(this@VidenteAccessibilityService)) return
                    speakSecondary(getString(R.string.spoken_battery_low))
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    if (!VidentePreferences.getAnnounceChargerConnected(this@VidenteAccessibilityService)) return
                    speakSecondary(getString(R.string.spoken_charger_connected))
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (!VidentePreferences.getAnnounceChargerDisconnected(this@VidenteAccessibilityService)) return
                    speakSecondary(getString(R.string.spoken_charger_disconnected))
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level < 0 || scale <= 0) return
                    val full = level * 100 / scale >= 100
                    if (full && !batteryWasFull) {
                        batteryWasFull = true
                        if (VidentePreferences.getAnnounceFullBattery(this@VidenteAccessibilityService)) {
                            speakSecondary(getString(R.string.spoken_battery_full))
                        }
                    } else if (!full) {
                        batteryWasFull = false
                    }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    if (!VidentePreferences.getAnnounceAirplaneMode(this@VidenteAccessibilityService)) return
                    val on = intent.getBooleanExtra("state", false)
                    speakSecondary(
                        getString(if (on) R.string.spoken_airplane_mode_on else R.string.spoken_airplane_mode_off)
                    )
                }
            }
        }
    }

    /**
     * P9 aviso puntual: audífonos o Bluetooth conectados/desconectados. Se
     * usa AudioDeviceCallback (la forma moderna recomendada por Android,
     * disponible desde API 23; ACTION_HEADSET_PLUG quedó obsoleto e
     * inconsistente) en vez de escuchar Bluetooth directamente: reporta por
     * igual auriculares por cable y por Bluetooth, sin depender de una app o
     * librería de terceros.
     */
    private fun isHeadphoneType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return true
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.none { isHeadphoneType(it.type) }) return
            if (!VidentePreferences.getAnnounceAudioDeviceConnected(this@VidenteAccessibilityService)) return
            speakSecondary(getString(R.string.spoken_audio_device_connected))
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.none { isHeadphoneType(it.type) }) return
            if (!VidentePreferences.getAnnounceAudioDeviceDisconnected(this@VidenteAccessibilityService)) return
            speakSecondary(getString(R.string.spoken_audio_device_disconnected))
        }
    }

    /**
     * P9 tercer aviso puntual: notificación entrante, por la voz secundaria.
     * Solo dice qué app la mandó, nunca el contenido del mensaje (elegido a
     * propósito: un aviso automático e inesperado no es lo mismo que leer la
     * pantalla a propósito, y decir el contenido en voz alta podría exponer
     * un mensaje privado si hay alguien cerca). Se ignoran las notificaciones
     * de Vidente mismo, y no se repite el mismo aviso (misma app) si ya se
     * dijo hace muy poco, para no saturar de avisos con apps que actualizan
     * su notificación seguido (música, descargas, etc.).
     */
    private fun handleNotification(event: AccessibilityEvent) {
        if (!VidentePreferences.getAnnounceNotifications(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val now = SystemClock.uptimeMillis()
        if (pkg == lastNotifiedPackage && now - lastNotifiedAt < NOTIFICATION_REPEAT_GUARD_MS) return
        lastNotifiedPackage = pkg
        lastNotifiedAt = now

        val label = appLabel(pkg) ?: pkg
        speakSecondary(getString(R.string.spoken_notification, label))
    }

    // Recursos con el idioma elegido en Ajustes; null = seguir el del sistema.
    // Sobrescribir getResources() hace que todos los getString del servicio
    // usen ese idioma sin tocarlos uno a uno.
    private var localeResources: Resources? = null

    override fun getResources(): Resources = localeResources ?: super.getResources()

    private fun refreshLocale() {
        val tag = VidentePreferences.getAppLanguage(this)
        localeResources = if (tag == VidentePreferences.APP_LANGUAGE_SYSTEM) {
            null
        } else {
            try {
                val config = Configuration(super.getResources().configuration)
                config.setLocale(Locale.forLanguageTag(tag))
                createConfigurationContext(config).resources
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo aplicar el idioma '$tag'", e)
                null
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == VidentePreferences.KEY_APP_LANGUAGE || key == null) refreshLocale()

        // Cambiar de MOTOR no alcanza con reconfigurar el TextToSpeech ya
        // creado (un motor queda fijo desde que se construye la instancia):
        // hay que rehacerla. El resto de los ajustes (voz, velocidad, tono)
        // sí se pueden reaplicar sobre la instancia actual.
        if (key == VidentePreferences.KEY_ENGINE_PACKAGE) {
            tts?.shutdown()
            createPrimaryTts(VidentePreferences.getEnginePackage(this))
        } else {
            tts?.let { applyPreferences(it) }
        }
        if (key == VidentePreferences.KEY_SECONDARY_ENGINE_PACKAGE) {
            ttsSecondary?.shutdown()
            createSecondaryTts(VidentePreferences.getSecondaryEnginePackage(this))
        } else {
            ttsSecondary?.let { applySecondaryPreferences(it) }
        }
        refreshScrollFeedback()

        if (key == VidentePreferences.KEY_AUDIO_OUTPUT) {
            // El SoundPool fija su ruta de audio al crearse; se rehace.
            releaseSoundPool()
            ensureSoundPool()
        }

        if (key == VidentePreferences.KEY_TUTORIAL_REQUESTED &&
            VidentePreferences.isTutorialRequested(this)
        ) {
            VidentePreferences.setTutorialRequested(this, false)
            startTutorial()
        }

        // Paso 2 de gestos personalizables: todavía nada escribe esta clave
        // (eso es el paso 3), pero queda lista la recarga para cuando
        // empiece a hacerlo, sin reiniciar el servicio.
        if (key == VidentePreferences.KEY_GESTURE_ACTION_MAP || key == null) {
            gestureActionMap = loadGestureActionMap()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Vidente conectado")
        refreshLocale()
        refreshImePackages()
        refreshScrollFeedback()
        gestureActionMap = loadGestureActionMap()
        ensureSoundPool()
        showFloatingButton()
        // onServiceConnected puede volver a llamarse si el sistema reconecta
        // el servicio; se desregistra primero (sin fallar si no lo estaba)
        // para no quedar registrado dos veces.
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (e: Exception) {
            // No estaba registrado todavía: es lo esperado la primera vez.
        }
        registerReceiver(
            systemEventReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
        )

        // Mismo motivo que el receiver de arriba: puede reconectar el
        // servicio, así que se desregistra primero por si ya estaba.
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
            try {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (e: Exception) {
                // No estaba registrado todavía: es lo esperado la primera vez.
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar AudioDeviceCallback", e)
        }

        // Tutorial de bienvenida la primera vez que se activa el servicio.
        // Se marca como visto al arrancarlo para no repetirlo en cada
        // reconexión; se puede repasar desde Ajustes.
        if (!VidentePreferences.isTutorialDone(this)) {
            VidentePreferences.setTutorialDone(this, true)
            startTutorial()
        }
    }

    /** Paquetes de los teclados instalados; para saber que el teclado está en pantalla sin getWindows(). */
    private fun refreshImePackages() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            imm.enabledInputMethodList.forEach { it.packageName?.let(imePackages::add) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo listar los teclados instalados", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // P8b: cualquier evento de un teclado indica que está en pantalla.
        // Se excluyen los dos tipos agregados para el cursor y el deslizar-y-
        // soltar: el propio teclado los dispara en cada toque de tecla, y si
        // entraran acá reiniciarían sin parar el debounce de "apareció",
        // dejando keyboardVisible en false mientras se escribe.
        val evtPkg = event.packageName?.toString()
        if (tutorialStep == TutorialStep.NONE && evtPkg != null && evtPkg in imePackages &&
            event.eventType != AccessibilityEvent.TYPE_TOUCH_INTERACTION_END &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            onKeyboardEventSeen()
        }

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
                if (tutorialStep == TutorialStep.DOUBLE_TAP) {
                    onDoubleTapPracticed()
                } else if (tutorialStep == TutorialStep.NONE) {
                    announceToggledState(event)
                }

            // Pantalla o diálogo nuevo: se reinicia el estado dependiente de la
            // pantalla y se anuncia el título (P8a) o el contenido del diálogo
            // (P8b).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onScreenChanged()
                if (tutorialStep == TutorialStep.NONE) handleWindowStateChanged(event)
            }

            // P8c: posición al desplazarse (solo al detenerse el scroll).
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (tutorialStep == TutorialStep.NONE) handleScrolled(event)

            // P8c parte 2: eco de escritura al teclear en un campo de texto.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                if (tutorialStep == TutorialStep.NONE) handleTextChanged(event)

            // Anuncio de posición del cursor (configurable). Se envuelve en su
            // propio try/catch: si algo de esto falla, no debe tumbar el resto
            // del servicio (fue justo este tipo de cambio el que en su momento
            // dejó a Vidente sin leer nada).
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                if (tutorialStep == TutorialStep.NONE && cursorAnnounceEnabled) {
                    try {
                        handleTextSelectionChanged(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallo anunciando la posición del cursor", e)
                    }
                }

            // Modo de escritura "deslizar y soltar": al levantar el dedo tras
            // explorar el teclado, escribe la tecla que se estaba explorando.
            // Mismo cuidado: en su propio try/catch.
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ->
                if (tutorialStep == TutorialStep.NONE) {
                    try {
                        handleTouchInteractionEnd()
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallo activando por deslizar y soltar", e)
                    }
                }

            // P9 tercer aviso puntual: notificación entrante, por la voz
            // secundaria. Tipo de evento normal de accesibilidad (sin pedir
            // ningún permiso aparte); en su propio try/catch por tratarse de
            // un tipo de evento nuevo en la configuración del servicio.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (tutorialStep == TutorialStep.NONE) {
                    try {
                        handleNotification(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallo anunciando una notificación", e)
                    }
                }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> Unit

            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun rememberFocusedNode(node: AccessibilityNodeInfo) {
        prevFocusedNode?.recycle()
        prevFocusedNode = lastFocusedNode
        prevFocusedNodeAt = lastFocusedNodeAt
        lastFocusedNode = try { AccessibilityNodeInfo.obtain(node) } catch (e: Exception) { null }
        lastFocusedNodeAt = SystemClock.uptimeMillis()
    }

    private fun onScreenChanged() {
        stopContinuousReading()
        navMode = NavMode.ELEMENT
        stopScrollTone()
        lastScrollBoundary = null
        lastSpokenScrollPos = null
        lastScreenChangeAt = SystemClock.uptimeMillis()
        interactedSinceScreenChange = false
        lastMidScrollAt = 0L
        // Un aviso de borde pendiente pertenece a la pantalla anterior.
        boundaryAnnouncement = null
        lastFocusedNode?.recycle()
        lastFocusedNode = null
        prevFocusedNode?.recycle()
        prevFocusedNode = null
        lastFocusedNodeAt = 0L
        prevFocusedNodeAt = 0L
        // El cursor de la pantalla anterior no tiene nada que ver con la nueva.
        lastCursorIndex = -1
        lastCursorFieldKey = null
        // Un anuncio de exploración pendiente de la pantalla anterior no
        // debe sonar en la nueva.
        pendingHoverSpeakRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingHoverSpeakRunnable = null
        // Un borrado agrupado pendiente de la pantalla anterior no debe
        // anunciarse (ni el texto acumulado tiene sentido) en la nueva.
        pendingDeleteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDeleteRunnable = null
        pendingDeleteText = ""
    }

    /**
     * P8c: al desplazarse por una lista se acompaña el movimiento en tiempo
     * real. En modo tono, un pitido CONTINUO en bucle cuya frecuencia sigue a
     * la posición mientras el dedo arrastra (grave arriba, agudo abajo), y se
     * apaga poco después de dejar de desplazar. En modo voz, se dice el
     * porcentaje al detenerse. El principio y el final se dicen siempre en voz.
     */
    private fun handleScrolled(event: AccessibilityEvent) {
        if (continuousReading && !continuousPaused) return

        // Al abrir una pantalla o un contenedor (una carpeta del launcher, un
        // chat de WhatsApp que baja solo al último mensaje) el sistema emite un
        // scroll que suele venir "al final" aunque el usuario no haya movido
        // nada: eso hacía que Vidente dijera "Final de la lista" en vez de
        // anunciar lo que se abrió. Se ignora el scroll hasta que el usuario
        // toca o gesticula sobre la pantalla nueva, y también durante un breve
        // margen tras el cambio.
        if (!interactedSinceScreenChange) return
        if (SystemClock.uptimeMillis() - lastScreenChangeAt < SCROLL_AFTER_SCREEN_CHANGE_GUARD_MS) return

        val fromIndex = event.fromIndex
        val toIndex = event.toIndex
        val itemCount = event.itemCount
        val scrollY = event.scrollY
        val maxScrollY = event.maxScrollY
        val scrollX = event.scrollX
        val maxScrollX = event.maxScrollX

        // La lista solo tiene "principio" y "final" reales si de verdad se
        // puede desplazar. Si cabe entera en pantalla (primer y último
        // elemento visibles a la vez, y sin recorrido en píxeles) no es un
        // borde: es lo que pasa al abrir una carpeta del launcher, que emite
        // un scroll con la rejilla completa a la vista.
        val pixelScrollable = maxScrollY > 0 || maxScrollX > 0
        val wholeListVisible = itemCount > 0 && fromIndex == 0 && toIndex == itemCount - 1
        if (wholeListVisible && !pixelScrollable) {
            lastScrollBoundary = null
            return
        }

        val atStart = (itemCount > 0 && fromIndex == 0 && toIndex in 0 until itemCount - 1) ||
            (maxScrollY > 0 && scrollY == 0) || (maxScrollX > 0 && scrollX == 0)
        val atEnd = (itemCount > 0 && toIndex == itemCount - 1 && fromIndex > 0) ||
            (maxScrollY > 0 && scrollY >= maxScrollY) || (maxScrollX > 0 && scrollX >= maxScrollX)

        if (atEnd || atStart) {
            val which = if (atEnd) "fin" else "inicio"
            // Un borde real siempre viene después de haber recorrido la lista.
            // Si no hubo scroll intermedio reciente, este evento es la app
            // colocándose sola (Telegram baja al último mensaje al abrir el
            // chat, y llegaba a decir "Principio de la lista"): se registra el
            // borde pero no se anuncia.
            val recentMidScroll =
                SystemClock.uptimeMillis() - lastMidScrollAt <= BOUNDARY_NEEDS_RECENT_MID_MS
            if (which != lastScrollBoundary && recentMidScroll) {
                lastScrollBoundary = which
                stopScrollTone()
                speak(getString(if (atEnd) R.string.spoken_list_end else R.string.spoken_list_start))
            }
            // Si se suprime por no haber scroll intermedio reciente no se fija
            // lastScrollBoundary: una llegada real posterior al mismo borde sí
            // se anunciará.
            return
        }
        lastScrollBoundary = null
        lastMidScrollAt = SystemClock.uptimeMillis()

        // Se prefiere el desplazamiento en píxeles (continuo y monótono) al
        // índice de elemento, que en algunas apps (Contactos) no cambia por
        // evento y dejaba el tono clavado.
        val fraction: Float = when {
            maxScrollY > 0 && scrollY >= 0 -> scrollY.toFloat() / maxScrollY
            maxScrollX > 0 && scrollX >= 0 -> scrollX.toFloat() / maxScrollX
            itemCount > 1 && fromIndex >= 0 -> fromIndex.toFloat() / (itemCount - 1)
            else -> null
        } ?: return

        if (scrollFeedbackTone && scrollToneLoaded) {
            startOrUpdateScrollTone(fraction)
            pendingScrollRunnable?.let { mainHandler.removeCallbacks(it) }
            val stop = Runnable { stopScrollTone() }
            pendingScrollRunnable = stop
            mainHandler.postDelayed(stop, SCROLL_TONE_STOP_MS)
        } else {
            val pos = if (itemCount > 0 && fromIndex >= 0) {
                getString(R.string.spoken_scroll_item, fromIndex + 1, itemCount)
            } else {
                getString(R.string.spoken_scroll_percent, (fraction * 100).toInt())
            }
            pendingScrollRunnable?.let { mainHandler.removeCallbacks(it) }
            val speakPos = Runnable {
                if (pos != lastSpokenScrollPos) {
                    lastSpokenScrollPos = pos
                    speak(pos)
                }
            }
            pendingScrollRunnable = speakPos
            mainHandler.postDelayed(speakPos, SCROLL_SETTLE_MS)
        }
    }

    /**
     * SoundPool con un tono base en bucle (res/raw/scroll_tone.wav, 700 Hz,
     * longitud de ciclos exactos para que el bucle no chasquee). La posición
     * se traslada a la velocidad de reproducción: 0.5x (grave, principio) a
     * 2x (agudo, final). SoundPool es fiable y de baja latencia.
     */
    private fun ensureSoundPool() {
        if (soundPool != null) return
        val sp = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(buildAudioAttributes())
            .build()
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == scrollToneSoundId && status == 0) scrollToneLoaded = true
        }
        scrollToneSoundId = sp.load(this, R.raw.scroll_tone, 1)
        soundPool = sp
    }

    private fun releaseSoundPool() {
        stopScrollTone()
        soundPool?.release()
        soundPool = null
        scrollToneLoaded = false
        scrollToneSoundId = 0
    }

    private fun rateForFraction(fraction: Float): Float =
        (0.5f + fraction.coerceIn(0f, 1f) * 1.5f).coerceIn(0.5f, 2.0f)

    /**
     * Mientras se arrastra por la lista se repite un blip corto cada
     * BLIP_INTERVAL_MS; su tono (velocidad de reproducción) sigue a la
     * posición en vivo. Da una sensación de "ticking" electrónico que sube y
     * baja, en vez de un tono sostenido.
     */
    private fun startOrUpdateScrollTone(fraction: Float) {
        scrollBlipRate = rateForFraction(fraction)
        if (scrollBlipRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                soundPool?.play(scrollToneSoundId, SCROLL_TONE_VOL, SCROLL_TONE_VOL, 1, 0, scrollBlipRate)
                mainHandler.postDelayed(this, BLIP_INTERVAL_MS)
            }
        }
        scrollBlipRunnable = r
        r.run()
    }

    private fun stopScrollTone() {
        scrollBlipRunnable?.let { mainHandler.removeCallbacks(it) }
        scrollBlipRunnable = null
    }

    private fun refreshScrollFeedback() {
        scrollFeedbackTone =
            VidentePreferences.getScrollFeedback(this) == VidentePreferences.SCROLL_FEEDBACK_TONE
        typingEcho = VidentePreferences.getTypingEcho(this)
        keyboardWriteMode = VidentePreferences.getKeyboardWriteMode(this)
        cursorAnnounceEnabled =
            VidentePreferences.getCursorAnnounce(this) == VidentePreferences.CURSOR_ANNOUNCE_ON
        announceUppercase = VidentePreferences.getAnnounceUppercase(this)
        announceSpellingExample = VidentePreferences.getAnnounceSpellingExample(this)
        announceKeyboardExploration = VidentePreferences.getAnnounceKeyboardExploration(this)
    }

    /**
     * P8a/P8b: al cambiar de ventana, un único Runnable con retardo mira
     * rootInActiveWindow (operación de nodo, la misma que el doble toque,
     * NUNCA getWindows()) y decide: si parece un diálogo, lee su contenido;
     * si no, anuncia el título de la pantalla nueva. También, si el teclado
     * estaba en pantalla y la ventana nueva no es del teclado ni un diálogo,
     * lo marca oculto.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg.isNullOrBlank() || pkg == packageName ||
            pkg == "com.android.systemui" || pkg == "android"
        ) {
            return
        }
        if (pkg in imePackages) return  // el teclado en sí lo maneja onKeyboardEventSeen

        val eventText = event.text?.joinToString(" ")?.trim()?.takeIf {
            it.isNotBlank() && it != pkg && !it.startsWith("$pkg/") && !it.contains('.')
        }
        val eventCls = event.className?.toString().orEmpty()

        pendingTitleRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            try {
                classifyAndAnnounceWindow(pkg, eventText, eventCls)
            } catch (e: Exception) {
                Log.e(TAG, "P8a/P8b: fallo procesando el cambio de ventana", e)
            }
        }
        pendingTitleRunnable = r
        mainHandler.postDelayed(r, WINDOW_TITLE_DEBOUNCE_MS)
    }

    private fun classifyAndAnnounceWindow(pkg: String, eventText: String?, eventCls: String) {
        val root = rootInActiveWindow

        if (root != null && looksLikeDialog(root, eventCls)) {
            val parts = mutableListOf<String>()
            collectDialogText(root, parts, depth = 0)
            root.recycle()
            val body = parts.joinToString(". ").take(DIALOG_MAX_CHARS)
            if (body.isNotBlank() && body != lastWindowTitle) {
                lastWindowTitle = body
                speak(body)
            }
            return
        }

        root?.recycle()
        setKeyboardVisible(false)

        val title = eventText ?: appLabel(pkg)
        if (!title.isNullOrBlank() && title != lastWindowTitle) {
            lastWindowTitle = title
            speak(title)
        }
    }

    /**
     * Heurística de diálogo sin getWindows(): la clase de la ventana o del
     * árbol contiene "Dialog", o es una ventana pequeña (pocos nodos) con uno
     * a tres botones, que es la firma de un cuadro de confirmación.
     */
    private fun looksLikeDialog(root: AccessibilityNodeInfo, eventCls: String): Boolean {
        val rootCls = root.className?.toString().orEmpty()
        if (eventCls.contains("Dialog") || rootCls.contains("Dialog")) return true

        var nodes = 0
        var buttons = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > DIALOG_SCAN_DEPTH || nodes > DIALOG_SCAN_MAX_NODES) return
            nodes++
            if (n.className?.toString()?.endsWith("Button") == true) buttons++
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
                c.recycle()
            }
        }
        walk(root, 0)
        return buttons in 1..3 && nodes <= DIALOG_MAX_TREE_NODES
    }

    private fun collectDialogText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= DIALOG_MAX_PARTS) return
        val cn = node.className?.toString().orEmpty()
        if (!cn.endsWith("Button")) {
            ownLabel(node)?.let { if (it !in out) out.add(it) }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDialogText(child, out, depth + 1)
            child.recycle()
        }
    }

    /**
     * P8b: el teclado se detecta viendo eventos cuyo paquete es un método de
     * entrada (SwiftKey, Gboard, etc.), sin getWindows(). Un Runnable con
     * retardo dice "Teclado en pantalla" una vez, ya asentado el teclado y su
     * barra, para que no lo pise la lectura de esos elementos.
     */
    private fun onKeyboardEventSeen() {
        if (keyboardVisible) return
        pendingKeyboardRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!keyboardVisible) {
                keyboardVisible = true
                tts?.speak(getString(R.string.spoken_keyboard_shown), TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
            }
        }
        pendingKeyboardRunnable = r
        mainHandler.postDelayed(r, KEYBOARD_DEBOUNCE_MS)
    }

    private fun setKeyboardVisible(visible: Boolean) {
        // Ocultar cancela siempre un "en pantalla" pendiente, aunque el estado
        // ya figure como oculto (el aviso pendiente sería para la pantalla que
        // ya dejamos).
        if (!visible) pendingKeyboardRunnable?.let { mainHandler.removeCallbacks(it) }
        if (visible == keyboardVisible) return
        keyboardVisible = visible
        tts?.speak(
            getString(if (visible) R.string.spoken_keyboard_shown else R.string.spoken_keyboard_hidden),
            TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID
        )
    }

    /**
     * Nombre visible de la app a partir del paquete. En Android 11+ necesita
     * QUERY_ALL_PACKAGES en el manifiesto; además MIUI/HyperOS lo condiciona a
     * un permiso aparte ("Obtener lista de aplicaciones instaladas") que el
     * usuario tiene que conceder a mano. Si falla, devuelve null y P8a se
     * queda callado en esa pantalla.
     */
    private fun appLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "P8a: appLabel('$pkg') falló", e)
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

        val now = SystemClock.uptimeMillis()

        // Mientras el usuario explora con el dedo, el toque manda: solo los
        // eventos de hover deciden qué elemento es el actual.
        //
        // Los de foco (de entrada y de accesibilidad) que llegan justo después
        // vienen de la app o del teclado moviendo el foco a un elemento vecino,
        // y hacían que la lectura y, sobre todo, la activación saltaran a otra
        // tecla (tocar "V" y escribir la de arriba). El evento de foco de
        // accesibilidad que provoca el propio Vidente sobre el elemento
        // correcto también cae aquí, pero es redundante: ese elemento ya se
        // anunció desde el hover.
        val isFocusEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        if (isFocusEvent && now - lastHoverAt < HOVER_OWNS_FOCUS_MS) return

        val node = event.source ?: return

        // Explorar al tacto cuenta como interacción con la pantalla nueva: a
        // partir de aquí un scroll ya puede ser del usuario.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
            lastHoverAt = now
            interactedSinceScreenChange = true
            vibrateTick()
        }

        // Vidente gestiona el foco de accesibilidad: al leer un elemento por
        // toque o al recibir el foco de entrada, se lo asigna. Sin esto,
        // "siguiente/anterior" (P5) no sabía dónde estabas (índice siempre
        // -1) y saltaba al primer elemento, y "activar" (P4) dependía del
        // toque por coordenadas del sistema. TalkBack hace lo mismo.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }

        val text = describeForSpeech(node)
        val editable = node.isEditable ||
            node.className?.toString()?.endsWith("EditText") == true
        val fieldKey = node.viewIdResourceName ?: text

        // Copia del último elemento leído: ancla de respaldo para
        // "siguiente/anterior" cuando la app no acepta el foco de accesibilidad.
        rememberFocusedNode(node)

        node.recycle()

        if (text.isNullOrBlank()) {
            // P8b: enfocar un campo de texto implica teclado en pantalla.
            if (editable) onKeyboardEventSeen()
            return
        }

        // Un aviso de borde de pantalla se antepone y salta la deduplicación,
        // para que "Final de la pantalla" no se pierda si el elemento repite
        // texto con el anterior.
        val boundary = boundaryAnnouncement
        boundaryAnnouncement = null
        // La deduplicación solo vale un instante: al escribir rápido se tocan
        // dos veces seguidas la misma tecla y ambas deben oírse.
        if (text == lastSpoken && boundary == null &&
            now - lastSpokenAt < REPEAT_SPEECH_AFTER_MS
        ) {
            if (editable) onKeyboardEventSeen()
            return
        }

        lastSpoken = text
        lastSpokenAt = now
        // Nuevo elemento enfocado: el índice de cursor de lo anterior no
        // aplica. Pero si es el mismo campo que ya estaba enfocado (un toque
        // de exploración táctil que re-anuncia el mismo campo), se conserva:
        // ver el comentario de lastCursorFieldKey.
        if (fieldKey != lastCursorFieldKey) {
            lastCursorFieldKey = fieldKey
            lastCursorIndex = -1
        }
        val toSpeak = if (boundary != null) "$boundary. $text" else text
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
            // Ajuste configurable: explorar el teclado en pantalla con el
            // dedo, sin escuchar cada tecla, para quien prefiera solo oír la
            // letra al confirmarla. El resto de la función (foco, cursor,
            // dedup) ya corrió arriba, sin cambios: solo se salta el habla.
            val onKeyboardKey = keyboardVisible && event.packageName?.toString() in imePackages
            if (!announceKeyboardExploration && onKeyboardKey) {
                pendingHoverSpeakRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingHoverSpeakRunnable = null
                return
            }

            // Explorar arrastrando el dedo: si se pasa rápido por varios
            // elementos, cada hover cancela el anuncio pendiente del
            // anterior y solo se dice el que el dedo alcanza a "asentarse"
            // este instante corto, en vez de anunciar (y cortar) uno por
            // cada elemento que el dedo apenas rozó de pasada. El resto de
            // la función (foco, cursor, dedup) ya corrió arriba, sin
            // cambios: solo el habla queda debounced.
            pendingHoverSpeakRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable { if (ttsReady) speak(toSpeak) else pendingText = toSpeak }
            pendingHoverSpeakRunnable = r
            mainHandler.postDelayed(r, HOVER_SPEAK_DEBOUNCE_MS)
        } else {
            if (ttsReady) speak(toSpeak) else pendingText = toSpeak
        }

        // Después de leer el campo, para que "Teclado en pantalla" (QUEUE_ADD)
        // se encole detrás y no lo pise.
        if (editable) onKeyboardEventSeen()

        if (tutorialStep == TutorialStep.EXPLORE) onExplorePracticed()
    }

    /**
     * Pulso táctil muy corto al posar el dedo sobre un elemento. Sirve de
     * confirmación de que hay algo debajo del dedo, sin esperar a la voz.
     */
    private fun vibrateTick() {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(HOVER_VIBRATION_MS, HOVER_VIBRATION_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(HOVER_VIBRATION_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al vibrar", e)
        }
    }

    /**
     * Anuncio hablado de un nodo: nombre, rol y estado.
     * Ej: "Wi-Fi, interruptor, activado".
     *
     * El rol y el estado se toman del control de verdad: si el foco cae sobre
     * una fila (contenedor solo clickable) que contiene un Switch o una
     * casilla, se usa ese hijo. Así "Usar Vidente" se anuncia como
     * "interruptor, desactivado" en vez de "botón".
     */
    private fun describeForSpeech(node: AccessibilityNodeInfo): String? {
        val control = effectiveControlNode(node)
        val label = findLabel(node)
        val parts = mutableListOf<String>()

        if (label != null) {
            val role = roleOf(control)
            // No se repite la etiqueta si el rol ya la contiene (el campo de
            // mensaje de WhatsApp/Telegram tiene la pista "Mensaje" y el rol
            // "mensaje, cuadro de edición").
            if (role == null || !role.contains(label, ignoreCase = true)) parts.add(label)
            role?.let { parts.add(it) }
        } else {
            // Control real sin etiqueta (p. ej. el botón que abre el menú
            // lateral de Grok, un ImageButton sin contentDescription):
            // TalkBack lo anuncia igualmente por su rol para que el usuario
            // sepa que hay algo ahí; Vidente se quedaba mudo. Un contenedor
            // grande sin etiqueta sigue en silencio.
            val selfIsControl = node.isClickable || node.isCheckable || node.isEditable ||
                isRecognisedControl(node) ||
                node.className?.toString()?.endsWith("Button") == true
            val role = when {
                !selfIsControl -> null
                else -> roleOf(control) ?: if (node.childCount == 0) "botón" else null
            }
            if (role == null) {
                if (control !== node) control.recycle()
                return null
            }
            parts.add(role)
        }

        parts.addAll(statesOf(control))
        if (control !== node) control.recycle()

        return parts.joinToString(", ")
    }

    private fun effectiveControlNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (isRecognisedControl(node)) return node
        return controlDescendant(node, depth = 0) ?: node
    }

    private fun isRecognisedControl(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.isCheckable) return true
        val cn = node.className?.toString().orEmpty()
        return cn.endsWith("Switch") || cn.endsWith("SwitchCompat") || cn.endsWith("SwitchMaterial") ||
            cn.endsWith("ToggleButton") || cn.endsWith("CheckBox") || cn.endsWith("RadioButton") ||
            cn.endsWith("SeekBar") || cn.endsWith("EditText")
    }

    @Suppress("DEPRECATION")
    private fun controlDescendant(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth >= MAX_LABEL_DEPTH) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isRecognisedControl(child)) return child
            val deeper = controlDescendant(child, depth + 1)
            child.recycle()
            if (deeper != null) return deeper
        }
        return null
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
    private fun findLabel(node: AccessibilityNodeInfo): String? {
        ownLabel(node)?.let { return it }

        // Solo se sintetiza una etiqueta mirando hijos o hermanos cuando el
        // nodo es una fila o un control (clickable, casilla, Switch, campo…),
        // o cuando la propia app lo marcó como parada de lector de pantalla
        // (isScreenReaderFocusable) sin ponerle texto a él mismo -- caso real:
        // el encabezado "Recientes" del menú de Claude vive en un contenedor
        // de un solo hijo marcado así, con el texto en ese hijo. Sin este
        // agregado, treeSuccessor sí llegaba al contenedor pero
        // describeForSpeech no encontraba ninguna etiqueta y se quedaba mudo.
        // Para un contenedor grande sin texto propio y sin esa marca —el área
        // de páginas del launcher, por ejemplo— no se inventa nada: al tocar
        // un hueco vacío, la búsqueda en descendientes acababa leyendo el
        // texto de un widget ("Tiempo") que vivía en otra página del mismo
        // contenedor.
        val screenReaderStop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isScreenReaderFocusable
        val synthesises = node.isClickable || node.isCheckable || node.isEditable ||
            isRecognisedControl(node) || screenReaderStop
        if (!synthesises) return null

        return labelFromDescendants(node, depth = 0) ?: labelFromSiblings(node)
    }

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
            node.isEditable || className.endsWith("EditText") -> {
                val pkg = node.packageName?.toString()
                if (pkg != null && pkg in MESSAGING_PACKAGES) {
                    getString(R.string.spoken_role_message_editbox)
                } else {
                    getString(R.string.spoken_role_editbox)
                }
            }
            className.endsWith("Switch") ||
                className.endsWith("SwitchCompat") ||
                className.endsWith("SwitchMaterial") ||
                className.endsWith("ToggleButton") -> getString(R.string.spoken_role_switch)
            className.endsWith("CheckBox") -> getString(R.string.spoken_role_checkbox)
            className.endsWith("RadioButton") -> getString(R.string.spoken_role_radio)
            node.isCheckable -> getString(R.string.spoken_role_checkbox)
            className.endsWith("SeekBar") -> getString(R.string.spoken_role_slider)
            // Solo se dice "botón" en botones de verdad. Los íconos del inicio y
            // las filas clickeables normales se leen solo con su nombre.
            className.endsWith("Button") -> getString(R.string.spoken_role_button)
            else -> null
        }
    }

    private fun statesOf(node: AccessibilityNodeInfo): List<String> {
        val states = mutableListOf<String>()

        val stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val className = node.className?.toString().orEmpty()
        when {
            // Va antes que stateDescription a propósito: Android le pone
            // solo, a CUALQUIER RadioButton/CheckBox, un stateDescription
            // automático ("seleccionado"/"no seleccionado", confirmado en el
            // código fuente de Android), y eso pisaba "marcada"/"sin marcar"
            // sin que Vidente llegara a usar su propia palabra nunca (bug
            // real desde el build 82, no algo nuevo).
            className.endsWith("RadioButton") || className.endsWith("CheckBox") -> states.add(
                getString(if (node.isChecked) R.string.spoken_state_checked else R.string.spoken_state_unchecked)
            )
            // stateDescription lo define la app y es más preciso que
            // deducirlo, para el resto de los controles.
            stateDescription != null -> states.add(stateDescription)
            node.isCheckable -> states.add(
                getString(if (node.isChecked) R.string.spoken_state_on else R.string.spoken_state_off)
            )
        }

        if (node.isSelected) states.add(getString(R.string.spoken_state_selected))
        if (!node.isEnabled) states.add(getString(R.string.spoken_state_disabled))

        return states
    }

    /**
     * Al activar una casilla (o cualquier control marcable) se anuncia su
     * estado NUEVO: "marcada" / "sin marcar". Sin esto, tras el doble toque
     * Vidente se quedaba callado y había que salir del control y volver a
     * entrar para saber si había quedado activado o no.
     *
     * Se dice solo el estado, sin repetir el nombre: el usuario acaba de oír
     * el nombre al pararse en el control, y repetirlo entero en cada toque
     * haría lento algo que se usa seguido.
     *
     * El nodo se refresca antes de leerlo porque el evento de clic puede
     * llegar con la copia anterior del nodo (el estado todavía sin cambiar);
     * si el refresco falla se usa lo que traiga el propio evento.
     */
    private fun announceToggledState(event: AccessibilityEvent) {
        val node = event.source ?: return
        try {
            val refreshed = try { node.refresh() } catch (e: Exception) { false }
            if (!node.isCheckable) return
            val checked = if (refreshed) node.isChecked else event.isChecked
            val className = node.className?.toString().orEmpty()
            // Un interruptor dice "activado"/"desactivado"; una casilla o una
            // opción, "marcada"/"sin marcar" (la regla de Ajustes de Vidente).
            val isSwitch = className.endsWith("Switch") ||
                className.endsWith("SwitchCompat") ||
                className.endsWith("SwitchMaterial") ||
                className.endsWith("ToggleButton")
            val stateRes = if (isSwitch) {
                if (checked) R.string.spoken_state_on else R.string.spoken_state_off
            } else {
                if (checked) R.string.spoken_state_checked else R.string.spoken_state_unchecked
            }
            speak(getString(stateRes))
        } finally {
            node.recycle()
        }
    }

    /**
     * Justo después de destrabar el teléfono, el aviso de hora (motor
     * secundario, disparado por ACTION_USER_PRESENT) y el primer anuncio
     * normal de pantalla (motor principal, por foco o cambio de ventana)
     * compiten: son dos motores de voz separados, y el aviso de hora puede
     * tardar más en llegar (el sistema está ocupado despertando la pantalla
     * justo en ese momento), así que a veces se escucha después en vez de
     * antes. Para no mezclar los dos, la PRIMERA locución normal tras
     * destrabar se retrasa un poco (solo esa, no las siguientes) y le da
     * tiempo a la hora a escucharse primero.
     */
    private fun speak(text: String) {
        val sinceUnlock = SystemClock.uptimeMillis() - lastUnlockAt
        if (sinceUnlock in 0 until UNLOCK_ANNOUNCE_GRACE_MS) {
            lastUnlockAt = 0L
            mainHandler.postDelayed(
                { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) },
                UNLOCK_ANNOUNCE_DELAY_MS
            )
            return
        }
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

    /** Une varias cadenas del tutorial con un espacio. */
    private fun tut(vararg res: Int): String = res.joinToString(" ") { getString(it) }

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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleHelper.currentLocale(this@VidenteAccessibilityService))
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
        val role = (roleOf(node) ?: getString(R.string.spoken_role_text)).replaceFirstChar { it.uppercase() }
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
     *   Deslizar arriba y luego izquierda -> cursor al inicio del campo
     *   Deslizar arriba y luego derecha   -> cursor al final del campo
     *   Un toque durante la lectura continua -> pausa (P6)
     *
     * La lista de arriba es solo la configuración POR DEFECTO
     * (GestureConfig.DEFAULT_MAP): el reparto real sale de gestureActionMap,
     * que el usuario puede cambiar desde Ajustes -> Gestos.
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

        // Un gesto cuenta como interacción con la pantalla actual.
        interactedSinceScreenChange = true

        // Con lectura continua en marcha, el primer gesto solo la pausa.
        if (continuousReading && !continuousPaused) {
            if (SystemClock.uptimeMillis() - continuousStartedAt >= CONTINUOUS_START_GUARD_MS) {
                pauseContinuousReading()
            }
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && gestureId == GESTURE_DOUBLE_TAP) {
            // Qué hace el doble toque sale del mapa como cualquier otro
            // gesto (puede reasignarse desde Ajustes), pero el gesto se
            // consume SIEMPRE, tenga acción asignada o no: si se devolvía
            // false, el sistema entregaba el toque en crudo a la app, y
            // sobre la tecla de borrar eso equivalía a dejarla pulsada, con
            // el borrado repetido ("tecla trabada").
            gestureActionMap[gestureId]?.let { runGestureAction(it) }
            return true
        }

        // Cualquier otro gesto releva al dedo: a partir de aquí los eventos de
        // foco vuelven a mandar (si no, tras explorar, el elemento al que salta
        // "siguiente/anterior" no se leería durante el margen del hover).
        //
        // Pero ANTES de borrar esa marca se anota si el usuario venía de tocar
        // la pantalla recién: moveAccessibilityFocus la necesita para saber
        // desde qué elemento seguir. Al borrarla acá y recién después ejecutar
        // la acción, ese dato se perdía y el primer deslizamiento después de un
        // toque se quedaba sin ancla (ver gestureFollowsRecentHover).
        gestureFollowsRecentHover = SystemClock.uptimeMillis() - lastHoverAt < HOVER_OWNS_FOCUS_MS
        lastHoverAt = 0L

        val gestureAction = gestureActionMap[gestureId] ?: return false

        // Antirebote: solo para las acciones que ya lo tenían (ver
        // DEBOUNCED_ACTIONS), atado al gesto físico recibido -- si Android
        // llega a reportar un solo trazo como dos gestos casi juntos, no se
        // repite/salta de más.
        if (gestureAction in DEBOUNCED_ACTIONS && isDebounced(gestureId)) return true

        return runGestureAction(gestureAction)
    }

    /** Ejecuta una acción de gesto, venga del gesto que venga. */
    private fun runGestureAction(gestureAction: GestureAction): Boolean {
        return when (gestureAction) {
            GestureAction.ACTIVATE -> {
                activateFocusedElement()
                true
            }
            GestureAction.NEXT -> moveInMode(forward = true)
            GestureAction.PREVIOUS -> moveInMode(forward = false)
            GestureAction.CYCLE_NAV_MODE -> {
                cycleNavMode()
                true
            }
            GestureAction.START_CONTINUOUS_READING -> {
                startOrResumeContinuousReading()
                true
            }
            GestureAction.REPEAT_LAST_PHRASE -> {
                repeatLastPhrase()
                true
            }
            GestureAction.CURSOR_TO_FIELD_START -> moveCursorToFieldBoundary(toStart = true)
            GestureAction.CURSOR_TO_FIELD_END -> moveCursorToFieldBoundary(toStart = false)
            GestureAction.GO_HOME -> performSystemGestureAction(GLOBAL_ACTION_HOME, R.string.spoken_home)
            GestureAction.GO_BACK -> {
                val done = performSystemGestureAction(GLOBAL_ACTION_BACK, R.string.spoken_back)
                // P8b: Atrás con el teclado en pantalla lo suele cerrar sin
                // cambiar de pantalla, así que no llegaría un cambio de
                // ventana que lo marcara.
                if (done && keyboardVisible) setKeyboardVisible(false)
                done
            }
            GestureAction.GO_RECENTS -> performSystemGestureAction(GLOBAL_ACTION_RECENTS, R.string.spoken_recents)
            GestureAction.CYCLE_TTS_ENGINE -> {
                cycleTtsEngine()
                true
            }
        }
    }

    /**
     * Pasa al siguiente motor de TTS instalado para la voz PRINCIPAL (la
     * secundaria de avisos puntuales no se toca: alternar cuál de las dos
     * lee normalmente haría que un aviso puntual pudiera pisarse con la
     * lectura, justo lo que el diseño de las dos voces separadas evita).
     *
     * Reutiliza el mismo mecanismo que ya usa el Spinner de motor en
     * Ajustes: guardar el paquete elegido dispara sola la reconstrucción del
     * TTS principal (ver onSharedPreferenceChanged). El aviso de qué motor
     * quedó elegido se dice por la voz SECUNDARIA a propósito: la principal
     * puede tardar un instante en reiniciar con el motor nuevo, y así el
     * aviso se escucha siempre, sin depender de ese reinicio.
     */
    private fun cycleTtsEngine() {
        val engine = tts ?: return
        val installed = engine.engines ?: return
        if (installed.isEmpty()) return
        // null representa "predeterminado del sistema", igual que en Ajustes.
        val packages: List<String?> = listOf(null) + installed.map { it.name }
        val current = VidentePreferences.getEnginePackage(this)
        val currentIndex = packages.indexOf(current).coerceAtLeast(0)
        val next = packages[(currentIndex + 1) % packages.size]
        VidentePreferences.setEnginePackage(this, next)
        val label = installed.firstOrNull { it.name == next }?.label
            ?: getString(R.string.settings_engine_system_default)
        speakSecondary(getString(R.string.spoken_tts_engine_changed, label))
    }

    /** GO_HOME/GO_BACK/GO_RECENTS: acción global del sistema + aviso hablado si se pudo. */
    private fun performSystemGestureAction(globalAction: Int, spokenRes: Int): Boolean {
        val done = performGlobalAction(globalAction)
        if (done && ttsReady) speak(getString(spokenRes))
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

        // Mismo criterio que el resto de la navegación (ver
        // currentNavigationAnchor): si el foco del sistema no está puesto
        // -- lo típico justo después de un toque directo --, se arranca
        // desde el elemento que el usuario acaba de escuchar, no desde el
        // principio de la pantalla.
        var focusedIdx = nodes.indexOfFirst { it.isAccessibilityFocused }
        if (focusedIdx < 0) {
            val fallback = currentNavigationAnchor()
            if (fallback != null) {
                focusedIdx = nodes.indexOfFirst { it == fallback }
                fallback.recycle()
            }
        }
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
        speak(getString(R.string.spoken_reading_paused))
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
        speak(getString(R.string.spoken_reading_finished))
    }

    private fun repeatLastPhrase() {
        val last = lastSpoken ?: return
        speak(last)
    }

    // ---- P7: navegación granular y por tipo ----

    private fun cycleNavMode() {
        val values = NavMode.values()
        navMode = values[(navMode.ordinal + 1) % values.size]
        speak(getString(navMode.labelRes))
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
     * Salta el cursor al inicio o al final del campo de texto con foco de
     * accesibilidad (gestos arriba-y-izquierda / arriba-y-derecha). Usa
     * ACTION_SET_SELECTION (posición 0, o el largo del texto) en vez de
     * repetir "mover por carácter" hasta el borde. El anuncio de resultado se
     * dice por la voz secundaria y suprime el eco de cursor normal (mismo
     * mecanismo que ya usa el eco de escritura) para no anunciarlo dos veces.
     */
    private fun moveCursorToFieldBoundary(toStart: Boolean): Boolean {
        val focused = currentNavigationAnchor() ?: return false
        if (!focused.isEditable) {
            focused.recycle()
            return false
        }
        val length = focused.text?.length ?: 0
        val index = if (toStart) 0 else length
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
        }
        suppressCursorEchoUntil = SystemClock.uptimeMillis() + CURSOR_ECHO_SUPPRESS_MS
        val done = focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        focused.recycle()
        if (done) {
            lastCursorIndex = index
            speakSecondary(getString(if (toStart) R.string.spoken_text_start else R.string.spoken_text_end))
        }
        return done
    }

    /**
     * Recorre el texto del elemento enfocado por carácter, palabra, línea o
     * párrafo. El fragmento recorrido lo anuncia handleTextTraversed a partir
     * del evento que dispara la acción.
     */
    private fun moveByGranularity(granularity: Int, forward: Boolean): Boolean {
        val focused = currentNavigationAnchor() ?: return false

        val supported = (focused.movementGranularities and granularity) != 0
        if (!supported) {
            focused.recycle()
            speak(getString(R.string.spoken_no_text_to_traverse))
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
        if (done) {
            // El cambio de selección que dispara esta acción ya se cubre con
            // el fragmento que lee handleTextTraversed: no leerlo de nuevo.
            suppressCursorEchoUntil = SystemClock.uptimeMillis() + CURSOR_ECHO_SUPPRESS_MS
        } else {
            speak(getString(if (forward) R.string.spoken_text_end else R.string.spoken_text_start))
        }
        return done
    }

    /**
     * Antepone "Mayúscula" a una letra mayúscula sola (ajuste configurable,
     * activado por defecto), igual que TalkBack. Character.isUpperCase() es
     * una propiedad Unicode del carácter en sí: no depende de qué teclado ni
     * layout se usó para escribirlo, así que funciona igual por eco de
     * escritura que por exploración con gestos.
     */
    private fun withUppercaseAnnounced(ch: Char): String =
        if (announceUppercase && ch.isUpperCase()) {
            getString(R.string.spoken_uppercase_letter, ch.toString())
        } else {
            ch.toString()
        }

    /**
     * Igual que withUppercaseAnnounced, pero suma además la palabra de
     * ejemplo al deletrear ("A, de Antonio" / "A, as in Alpha", ajuste
     * configurable, desactivado por defecto): alfabeto telefónico español o
     * alfabeto NATO en inglés, listas fijas embebidas -- convenciones
     * públicas de toda la vida, no de ninguna app puntual.
     *
     * Solo se usa al teclear un carácter suelto y al explorar letra por
     * letra (P7): moverse con flechas dentro de un texto puede recorrer
     * muchos caracteres seguidos rápido, y ahí sería demasiado. Por eso ese
     * camino (handleTextSelectionChanged) sigue usando withUppercaseAnnounced
     * directamente, sin pasar por acá.
     */
    private fun withSpellingAnnounced(ch: Char): String {
        val base = withUppercaseAnnounced(ch)
        if (!announceSpellingExample) return base
        val example = spellingExampleFor(ch) ?: return base
        return getString(R.string.spoken_spelling_example, base, example)
    }

    private fun spellingExampleFor(ch: Char): String? {
        val table = if (LocaleHelper.currentLocale(this).language == "es") {
            SPELLING_EXAMPLES_ES
        } else {
            SPELLING_EXAMPLES_EN
        }
        return table[ch.uppercaseChar()]
    }

    private fun handleTextTraversed(event: AccessibilityEvent) {
        val full = event.text?.joinToString("") ?: return
        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to <= from || to > full.length) return
        val piece = full.substring(from, to)
        if (piece.isNotBlank()) {
            lastSpoken = piece
            // El aviso de mayúscula solo aplica al recorrer carácter por
            // carácter (P7): no tendría sentido antes de leer una palabra,
            // línea o párrafo entero.
            val toSpeak = if (navMode == NavMode.CHARACTER && piece.length == 1) {
                withSpellingAnnounced(piece[0])
            } else {
                piece
            }
            speak(toSpeak)
        }
    }

    /**
     * P8c parte 2: eco de escritura. Al teclear en un campo de texto se lee el
     * carácter escrito y/o la palabra al terminarla, según la preferencia. Los
     * borrados se avisan ("borrado, a"). Los campos de contraseña se leen igual
     * que cualquier otro campo. Solo actúa con el teclado en pantalla, para no
     * leer los cambios de texto que hace la propia app.
     *
     * El cambio se calcula comparando el texto anterior con el nuevo (prefijo y
     * sufijo comunes), no con fromIndex/addedCount/removedCount, porque varios
     * teclados (SwiftKey con texto predictivo) rehacen la palabra entera y esos
     * campos no describían bien un borrado suelto.
     */
    private fun handleTextChanged(event: AccessibilityEvent) {
        // Escribir o borrar (con eco activado o no) también dispara un cambio
        // de selección justo después: se suprime siempre que sea del teclado
        // en pantalla, para que el anuncio de cursor no vuelva a leer lo que
        // ya se dijo (o reactive un eco que el usuario apagó a propósito).
        if (keyboardVisible) {
            suppressCursorEchoUntil = SystemClock.uptimeMillis() + CURSOR_ECHO_SUPPRESS_MS
        }

        if (typingEcho == VidentePreferences.TYPING_ECHO_NONE) return
        if (!keyboardVisible) return

        var now = event.text?.joinToString("") ?: return
        val before = event.beforeText?.toString() ?: ""
        if (now == before) return

        // Al quedar vacío, TextView reporta su hint (p. ej. "Mensaje") como si
        // fuera el texto propio -- convención de AOSP para que el lector de
        // pantalla anuncie el hint en un campo vacío. Sin este ajuste, el
        // diff de abajo tomaba ese hint como "texto agregado" y lo leía en
        // vez de anunciar la letra que se acababa de borrar. Se usa
        // isShowingHintText() (pensada justo para este caso) en vez de
        // comparar solo contra hintText: en campos de Material Design
        // (TextInputLayout) el texto que se muestra al quedar vacío no
        // siempre coincide con el hintText propio del nodo del EditText.
        val source = event.source
        val showingHint = source?.isShowingHintText == true
        val hint = source?.hintText?.toString()
        source?.recycle()
        if (before.isNotEmpty() && (showingHint || (hint != null && now == hint))) now = ""

        // Prefijo común.
        var p = 0
        val minLen = minOf(before.length, now.length)
        while (p < minLen && before[p] == now[p]) p++
        // Sufijo común (sin solaparse con el prefijo).
        var s = 0
        while (s < minLen - p &&
            before[before.length - 1 - s] == now[now.length - 1 - s]
        ) s++

        val removedText = before.substring(p, before.length - s)
        val addedText = now.substring(p, now.length - s)

        // "Apareció" todo el contenido de golpe (texto puesto por la app, o
        // beforeText que no llegó): no es tecleo, no se lee.
        if (before.isEmpty() && addedText.length == now.length && now.length > 2) return

        val echoChars = typingEcho == VidentePreferences.TYPING_ECHO_CHARS ||
            typingEcho == VidentePreferences.TYPING_ECHO_CHARS_WORDS
        val echoWords = typingEcho == VidentePreferences.TYPING_ECHO_WORDS ||
            typingEcho == VidentePreferences.TYPING_ECHO_CHARS_WORDS

        // Borrado puro: se agrupa por pausa (ver scheduleDeleteEcho) en vez
        // de anunciarse al toque, para no leer carácter por carácter
        // mientras se mantiene la tecla de borrar presionada.
        if (addedText.isEmpty() && removedText.isNotEmpty()) {
            scheduleDeleteEcho(removedText)
            return
        }
        if (addedText.isEmpty()) return

        // Se retomó la escritura: si había un borrado agrupado sin anunciar
        // todavía, se dice ya mismo para no perder el orden cronológico.
        flushPendingDeleteEcho()

        // Carácter suelto, o trozo insertado (pegado / sugerencia del teclado).
        if (echoChars) {
            when {
                addedText.length == 1 && !addedText[0].isWhitespace() ->
                    speak(withSpellingAnnounced(addedText[0]))
                addedText.length > 1 ->
                    speak(addedText.take(TYPING_ECHO_MAX_CHARS).trim().ifBlank { getString(R.string.spoken_space) })
            }
        }

        // Palabra terminada: se acaba de teclear un espacio y justo antes hay
        // una palabra. Una sugerencia entera (addedText largo) ya se leyó arriba.
        if (echoWords && addedText.length == 1 && addedText[0].isWhitespace()) {
            val end = p
            var start = end
            while (start > 0 && !now[start - 1].isWhitespace()) start--
            val word = now.substring(start, end)
            if (word.isNotBlank()) speak(word)
        }
    }

    /**
     * Acumula texto borrado y reinicia el temporizador de anuncio: al
     * mantener la tecla de borrar presionada, el sistema repite el borrado
     * varias veces por segundo, así que se agrupa por pausa en vez de
     * anunciar cada carácter suelto. Se anuncia apenas pasan
     * DELETE_ECHO_DEBOUNCE_MS sin un nuevo borrado -- sea porque se soltó la
     * tecla o por una pausa natural del repetido -- sin esperar a que
     * termine el gesto.
     */
    private fun scheduleDeleteEcho(removedText: String) {
        // El borrado avanza de derecha a izquierda: lo nuevo se antepone
        // para reconstruir la palabra en el orden en que se escribió.
        pendingDeleteText = removedText + pendingDeleteText
        pendingDeleteRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { flushPendingDeleteEcho() }
        pendingDeleteRunnable = r
        mainHandler.postDelayed(r, DELETE_ECHO_DEBOUNCE_MS)
    }

    private fun flushPendingDeleteEcho() {
        pendingDeleteRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDeleteRunnable = null
        val removedText = pendingDeleteText
        if (removedText.isEmpty()) return
        pendingDeleteText = ""
        val space = getString(R.string.spoken_space)
        val what = when {
            removedText.length > TYPING_ECHO_MAX_CHARS ->
                getString(R.string.spoken_chars_count, removedText.length)
            removedText.isBlank() -> space
            else -> removedText.trim().ifBlank { space }
        }
        speak(getString(R.string.spoken_deleted, what))
    }

    /**
     * Anuncio de posición del cursor (configurable; por defecto activado). Al
     * moverse dentro de un campo de texto por cualquier vía (tocar el texto
     * para reposicionar el cursor, las flechas del teclado, etc. — no solo
     * P7): en los extremos dice "Principio del texto" / "Final del texto"; si
     * no está en un extremo, lee el carácter que el cursor acaba de pasar; si
     * hay selección, la lee entera (o la cantidad de caracteres, si es larga).
     *
     * No repite lo que ya leyeron P7 (moveByGranularity/handleTextTraversed) o
     * el eco de escritura (handleTextChanged): ambos fijan
     * suppressCursorEchoUntil justo antes de mover el cursor por su cuenta.
     */
    private fun handleTextSelectionChanged(event: AccessibilityEvent) {
        if (SystemClock.uptimeMillis() < suppressCursorEchoUntil) return

        val text = event.text?.joinToString("") ?: return
        if (text.isEmpty()) {
            lastCursorIndex = -1
            return
        }

        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to < 0) return

        // Hay texto seleccionado (from != to): se lee la selección, no el
        // cursor. Un índice de cursor de después no tendría sentido: se
        // vuelve a establecer recién cuando la selección se cierre.
        if (from != to) {
            val a = minOf(from, to)
            val b = maxOf(from, to)
            if (a in 0..text.length && b in a..text.length) {
                val sel = text.substring(a, b)
                if (sel.length <= SELECTION_SPEAK_MAX_CHARS) {
                    speak(getString(R.string.spoken_selected_text, sel))
                } else {
                    speak(getString(R.string.spoken_selected_count, b - a))
                }
            }
            lastCursorIndex = -1
            return
        }

        val prev = lastCursorIndex
        lastCursorIndex = from
        // Primer evento tras enfocar el campo (o tras cerrar una selección):
        // solo se establece la posición de referencia, sin anunciar nada; si
        // no, cada campo enfocado diría "Principio del texto" al entrar.
        if (prev < 0 || from == prev) return

        if (from >= text.length && prev < text.length) {
            speak(getString(R.string.spoken_text_end))
            return
        }
        if (from <= 0 && prev > 0) {
            speak(getString(R.string.spoken_text_start))
            return
        }

        val idx = if (from > prev) from - 1 else from
        if (idx in text.indices) {
            val ch = text[idx]
            speak(if (ch.isWhitespace()) getString(R.string.spoken_space) else withUppercaseAnnounced(ch))
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

        // isAccessibilityFocused mira el foco del sistema, que justo después
        // de un toque directo todavía no es de fiar: en ese caso se ubica al
        // elemento que el usuario acaba de escuchar, para no empezar a buscar
        // desde el principio de la pantalla (mismo criterio que el resto de
        // los modos de navegación, ver currentNavigationAnchor).
        var anchor = all.indexOfFirst { it.isAccessibilityFocused }
        if (anchor < 0) {
            val fallback = currentNavigationAnchor()
            if (fallback != null) {
                anchor = all.indexOfFirst { it == fallback }
                fallback.recycle()
            }
        }
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
                boundaryAnnouncement = getString(
                    if (forward) R.string.spoken_screen_start else R.string.spoken_screen_end
                )
            }
            ok
        } else {
            speak(getString(R.string.spoken_no_type_on_screen, getString(mode.labelRes)))
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
     * navegable. Recorre el árbol de verdad desde el elemento actual (el que
     * tiene el foco de accesibilidad, o el último que leyó Vidente), sin lista
     * plana ni comparación de índices: primer navegable dentro del actual, si
     * no el siguiente hermano navegable, si no se sube al ancestro y se prueba
     * su siguiente hermano, y así. Al agotar el árbol se envuelve al extremo y
     * se deja pendiente el aviso de borde. Este recorrido estructural funciona
     * también en apps (React Native: Claude, Grok) donde comparar nodos por
     * identidad falla.
     */
    /** Copia utilizable del último elemento que Vidente leyó, o null si ya no vale. */
    @Suppress("DEPRECATION")
    private fun anchorFromLastFocusedNode(): AccessibilityNodeInfo? {
        val lf = lastFocusedNode ?: return null
        val ok = try { lf.refresh() } catch (e: Exception) { false }
        if (!ok) return null
        return try { AccessibilityNodeInfo.obtain(lf) } catch (e: Exception) { null }
    }

    /**
     * "Desde dónde" arranca cualquier gesto de navegación. Lo usan todos los
     * modos (elemento, granularidad de texto, saltar por tipo y cursor a los
     * bordes) para no volver a tener el mismo fallo en unos y no en otros.
     *
     * Si el gesto viene justo después de un toque directo, manda el elemento
     * que el usuario acaba de escuchar: el foco del sistema todavía no es de
     * fiar en ese instante. Si no, se busca el foco real en todas las
     * ventanas (no solo en la activa), y como último recurso se usa igual el
     * último elemento leído.
     */
    private fun currentNavigationAnchor(): AccessibilityNodeInfo? {
        if (gestureFollowsRecentHover) anchorFromLastFocusedNode()?.let { return it }
        findAccessibilityFocusedNodeAcrossWindows()?.let { return it }
        return anchorFromLastFocusedNode()
    }

    @Suppress("DEPRECATION")
    private fun moveAccessibilityFocus(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false

        // Si el usuario acaba de tocar la pantalla, el elemento que escuchó
        // manda sobre el foco del sistema: justo después de un toque ese foco
        // todavía no es de fiar, y quedarse sin ancla hacía que el recorrido
        // se fuera al principio (o al final) de la pantalla en vez de al
        // elemento contiguo. Es el mismo criterio que ya usaba
        // activateFocusedElement para el doble toque; este camino se había
        // quedado sin él.
        val anchor: AccessibilityNodeInfo? = currentNavigationAnchor()

        treeWalkBudget = TREE_WALK_BUDGET
        var wrapped = false
        var target: AccessibilityNodeInfo? = null
        try {
            val a = anchor
            if (a != null) target = treeSuccessor(a, forward)
            if (target == null) {
                target = firstNavigableInSubtree(root, includeSelf = false, forward = forward)
                wrapped = a != null
            }
        } catch (e: Exception) {
            Log.w(TAG, "moveAccessibilityFocus: fallo recorriendo el árbol", e)
        }
        anchor?.recycle()
        root.recycle()

        val t = target ?: return false
        val done = t.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (done && wrapped) {
            boundaryAnnouncement = getString(
                if (forward) R.string.spoken_screen_start else R.string.spoken_screen_end
            )
        }
        t.recycle()
        return done
    }

    /**
     * Sucesor (o predecesor) navegable de `from` en orden de lectura, o null
     * si se agota el árbol.
     *
     * NO se desciende dentro de `from`: el elemento actual es una parada
     * atómica (si además se bajara a su etiqueta o a un hijo, harían falta
     * dos deslizamientos para avanzar una fila de verdad). El sucesor es el
     * siguiente hermano navegable, o el primer navegable dentro de un hermano
     * posterior, subiendo por los ancestros si el hermano no existe.
     */
    @Suppress("DEPRECATION")
    private fun treeSuccessor(from: AccessibilityNodeInfo, forward: Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(from)
        var climbed = 0
        while (climbed < TREE_CLIMB_DEPTH) {
            val current = cur ?: break
            val parent = current.parent
            if (parent == null) {
                current.recycle()
                return null
            }
            val idx = indexInParent(parent, current)
            if (idx >= 0) {
                val range = if (forward) (idx + 1) until parent.childCount else (idx - 1) downTo 0
                for (i in range) {
                    if (treeWalkBudget-- <= 0) {
                        parent.recycle(); current.recycle(); return null
                    }
                    val sib = parent.getChild(i) ?: continue
                    if (isNavigable(sib)) {
                        parent.recycle(); current.recycle(); return sib
                    }
                    val inner = firstNavigableInSubtree(sib, includeSelf = false, forward = forward)
                    sib.recycle()
                    if (inner != null) {
                        parent.recycle(); current.recycle(); return inner
                    }
                }
            }
            current.recycle()
            cur = parent
            climbed++
        }
        cur?.recycle()
        return null
    }

    /**
     * Primer navegable dentro del subárbol de `node` en orden de lectura.
     * forward=true: se comprueba el nodo y luego los hijos 0..n. forward=false:
     * los hijos de n..0 y luego el nodo. Devuelve una copia (el llamador la
     * recicla) o null.
     */
    @Suppress("DEPRECATION")
    private fun firstNavigableInSubtree(
        node: AccessibilityNodeInfo,
        includeSelf: Boolean = true,
        forward: Boolean = true
    ): AccessibilityNodeInfo? {
        if (treeWalkBudget-- <= 0) return null

        if (forward && includeSelf && isNavigable(node)) return AccessibilityNodeInfo.obtain(node)

        val range = if (forward) 0 until node.childCount else (node.childCount - 1) downTo 0
        for (i in range) {
            val child = node.getChild(i) ?: continue
            val r = firstNavigableInSubtree(child, includeSelf = true, forward = forward)
            child.recycle()
            if (r != null) return r
        }

        if (!forward && includeSelf && isNavigable(node)) return AccessibilityNodeInfo.obtain(node)
        return null
    }

    @Suppress("DEPRECATION")
    private fun indexInParent(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            val match = c == child
            c.recycle()
            if (match) return i
        }
        return -1
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
        // La app marca explícitamente este nodo como parada de lector de
        // pantalla (React Native, Compose, algunas vistas nativas). El menú
        // lateral de la app de Claude depende de esto.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isScreenReaderFocusable) return true
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
    @Suppress("DEPRECATION")
    private fun activateFocusedElement(): Boolean {
        val nowMs = SystemClock.uptimeMillis()

        // El elemento que Vidente acaba de leer bajo el dedo manda sobre
        // findFocus(): en los teclados, el propio IME mueve el foco de
        // accesibilidad a una tecla vecina entre el toque y el doble toque, y
        // se escribía otra letra.
        //
        // Además, el segundo toque del doble toque vuelve a explorar: si cae un
        // milímetro desviado, el sistema anuncia la tecla vecina y esa quedaría
        // como "la actual" justo antes de activar. Por eso, si el elemento
        // actual se registró hace muy poco, viene de ese propio toque y se usa
        // el anterior: el que el usuario escuchó y quiso pulsar.
        val fromOwnDoubleTap = nowMs - lastFocusedNodeAt < DOUBLE_TAP_OWN_HOVER_MS &&
            prevFocusedNode != null &&
            lastFocusedNodeAt - prevFocusedNodeAt > DOUBLE_TAP_OWN_HOVER_MS
        val hovered = if (fromOwnDoubleTap) prevFocusedNode else lastFocusedNode
        val fresh = hovered != null &&
            nowMs - lastHoverAt < HOVER_OWNS_FOCUS_MS &&
            (try { hovered.refresh() } catch (e: Exception) { false })
        if (fresh && hovered != null) {
            val copy = try { AccessibilityNodeInfo.obtain(hovered) } catch (e: Exception) { null }
            if (copy != null) {
                val ok = clickNodeOrNearestAncestor(copy)
                copy.recycle()
                if (ok) return true
            }
        }

        val focused = findAccessibilityFocusedNodeAcrossWindows() ?: return false
        val done = clickNodeOrNearestAncestor(focused)
        focused.recycle()
        return done
    }

    /**
     * Intenta ACTION_CLICK sobre el propio nodo primero: algunos nodos
     * virtuales de teclado (deslizando rápido, la exploración a veces cae en
     * un nodo así) responden al clic aunque no reporten isClickable=true, así
     * que exigir eso de entrada los descartaba sin necesidad. Si el clic
     * directo no hace nada, recién ahí se busca el ancestro clickeable.
     */
    private fun clickNodeOrNearestAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val target = nearestClickable(node) ?: return false
        val done = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (target != node) target.recycle()
        return done
    }

    /**
     * Busca el nodo con foco de accesibilidad en la ventana activa y, si no
     * está ahí, en cualquier otra ventana interactiva (p. ej. el teclado en
     * pantalla, que es una ventana propia). rootInActiveWindow por sí solo no
     * incluye el teclado: por eso "deslizar y soltar" nunca encontraba la
     * tecla al soltar el dedo (confirmado con un build de diagnóstico: el
     * camino de respaldo decía "sin nodo con foco de accesibilidad" porque
     * buscaba solo en la ventana de la app, no en la del teclado).
     */
    @Suppress("DEPRECATION")
    private fun findAccessibilityFocusedNodeAcrossWindows(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            root.recycle()
            if (f != null) return f
        }
        for (window in windows) {
            val root = window.root ?: continue
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            root.recycle()
            if (f != null) return f
        }
        return null
    }

    /**
     * Modo de escritura "deslizar y soltar" (estilo Jieshuo/TalkBack): en vez
     * de doble toque, alcanza con recorrer el teclado y levantar el dedo
     * sobre la tecla deseada para escribirla. Reutiliza activateFocusedElement,
     * la misma lógica que ya usa el doble toque para encontrar y activar la
     * tecla que el usuario acaba de explorar.
     *
     * Solo actúa si el usuario eligió este modo en Ajustes y el teclado está
     * en pantalla: en ese contexto, cualquier deslizamiento sobre el teclado
     * ES la escritura que se busca, así que se activa siempre al soltar.
     *
     * (Antes también exigía que el dedo no se hubiera levantado de un gesto
     * reconocido hace poco, pensado para no escribir tras un gesto de
     * navegación ajeno al teclado. Pero el propio deslizamiento para escribir
     * -sobre todo en línea recta, p. ej. varias teclas de la fila superior-
     * puede coincidir con el patrón de un gesto de deslizar, así que esa
     * protección bloqueaba la escritura el 100% de las veces. Con el teclado
     * visible y este modo activo ya no hace falta: no hay otro gesto de
     * navegación válido que deba ganarle a la escritura ahí.)
     */
    private fun handleTouchInteractionEnd() {
        if (keyboardWriteMode != VidentePreferences.WRITE_MODE_SLIDE_RELEASE) return
        if (!keyboardVisible) return
        activateFocusedElement()
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
        speakTutorial(tut(R.string.tutorial_intro, R.string.tutorial_explore))
    }

    private fun onExplorePracticed() {
        if (!tutorialInputEnabled) return
        tutorialStep = TutorialStep.DOUBLE_TAP
        speakTutorial(tut(R.string.tutorial_explore_ok, R.string.tutorial_double_tap), flush = false)
    }

    private fun onDoubleTapPracticed() {
        if (tutorialStep != TutorialStep.DOUBLE_TAP || !tutorialInputEnabled) return
        tutorialStep = TutorialStep.NAVIGATE
        practicedGestures.clear()
        speakTutorial(
            tut(R.string.tutorial_double_tap_ok, R.string.tutorial_navigate, R.string.tutorial_navigate_first)
        )
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
        val ok = getString(
            if (gestureId == GESTURE_SWIPE_RIGHT) R.string.tutorial_nav_ok_next else R.string.tutorial_nav_ok_prev
        )

        if (practicedGestures.containsAll(NAVIGATE_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.SYSTEM
            speakTutorial("$ok " + tut(R.string.tutorial_system, R.string.tutorial_system_first))
            return
        }

        val next = getString(
            if (GESTURE_SWIPE_RIGHT !in practicedGestures) R.string.tutorial_nav_next_right
            else R.string.tutorial_nav_next_left
        )
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onSystemPracticed(gestureId: Int) {
        if (gestureId !in SYSTEM_GESTURES) return

        val firstTime = practicedGestures.add(gestureId)
        val ok = getString(
            when (gestureId) {
                GESTURE_SWIPE_UP -> R.string.tutorial_sys_ok_home
                GESTURE_SWIPE_DOWN_AND_LEFT -> R.string.tutorial_sys_ok_back
                else -> R.string.tutorial_sys_ok_recents
            }
        )

        if (practicedGestures.containsAll(SYSTEM_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.READING
            speakTutorial("$ok " + tut(R.string.tutorial_reading, R.string.tutorial_reading_first))
            return
        }

        val next = getString(
            when {
                GESTURE_SWIPE_UP !in practicedGestures -> R.string.tutorial_sys_next_home
                GESTURE_SWIPE_DOWN_AND_LEFT !in practicedGestures -> R.string.tutorial_sys_next_back
                else -> R.string.tutorial_sys_next_recents
            }
        )
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onReadingPracticed(gestureId: Int) {
        if (gestureId !in READING_GESTURES) return

        val firstTime = practicedGestures.add(gestureId)
        val ok = getString(
            if (gestureId == GESTURE_SWIPE_DOWN_AND_UP) R.string.tutorial_reading_ok_start
            else R.string.tutorial_reading_ok_repeat
        )

        if (practicedGestures.containsAll(READING_GESTURES)) {
            practicedGestures.clear()
            tutorialStep = TutorialStep.MODES
            speakTutorial("$ok " + tut(R.string.tutorial_modes, R.string.tutorial_modes_first))
            return
        }

        val next = getString(
            if (GESTURE_SWIPE_DOWN_AND_UP !in practicedGestures) R.string.tutorial_reading_next_start
            else R.string.tutorial_reading_next_repeat
        )
        speakTutorial(if (firstTime) "$ok $next" else next)
    }

    private fun onModesPracticed(gestureId: Int) {
        if (gestureId != GESTURE_SWIPE_DOWN) return

        tutorialStep = TutorialStep.NONE
        practicedGestures.clear()
        VidentePreferences.setTutorialDone(this, true)
        speakTutorial(tut(R.string.tutorial_modes_ok, R.string.tutorial_done))
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "systemEventReceiver ya no estaba registrado", e)
        }
        try {
            (getSystemService(AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "audioDeviceCallback ya no estaba registrado", e)
        }
        VidentePreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        mainHandler.removeCallbacksAndMessages(null)
        releaseSoundPool()
        lastFocusedNode?.recycle()
        lastFocusedNode = null
        prevFocusedNode?.recycle()
        prevFocusedNode = null
        hideFloatingButton()
        updateAudioDucking(speaking = false)
        tts?.stop()
        tts?.shutdown()
        ttsSecondary?.stop()
        ttsSecondary?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VidenteA11yService"
        private const val UTTERANCE_ID = "vidente_utterance"
        private const val TUTORIAL_UTTERANCE_ID = "vidente_tutorial"
        private const val CONTINUOUS_UTTERANCE_ID = "vidente_continuo"
        private const val SECONDARY_UTTERANCE_ID = "vidente_secundario"
        // Ver el comentario en speak(): solo la primera locución normal
        // dentro de este margen tras destrabar se retrasa, para darle
        // tiempo al aviso de hora (motor secundario) a escucharse primero.
        private const val UNLOCK_ANNOUNCE_GRACE_MS = 1000L
        private const val UNLOCK_ANNOUNCE_DELAY_MS = 600L
        // Ver el comentario en handleNotification(): no repetir el mismo
        // aviso (misma app) dentro de esta ventana.
        private const val NOTIFICATION_REPEAT_GUARD_MS = 8000L
        private const val FLOATING_BUTTON_MARGIN_PX = 24
        private const val MAX_DEPTH = 12
        private const val MAX_LABEL_DEPTH = 3
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 6
        private const val MAX_LINES = 60
        private const val MAX_SUMMARY_CHARS = 4000
        private const val MAX_NAV_NODES = 300
        // Recorrido del árbol para siguiente/anterior: tope de nodos visitados
        // y de niveles que se suben, para no colgarse en árboles enormes.
        private const val TREE_WALK_BUDGET = 5000
        private const val TREE_CLIMB_DEPTH = 40
        private const val MAX_ALL_NODES = 500

        // Gestos que alternan estado o ciclan: se ignora una repetición del
        // mismo gesto dentro de esta ventana.
        private const val GESTURE_DEBOUNCE_MS = 350L
        // Tras arrancar la lectura continua se ignoran gestos y toques durante
        // esta ventana, para que el final del propio gesto de arranque no la
        // pause de inmediato.
        private const val CONTINUOUS_START_GUARD_MS = 700L

        private const val WINDOW_TITLE_DEBOUNCE_MS = 300L
        private const val KEYBOARD_DEBOUNCE_MS = 350L
        private const val SCROLL_SETTLE_MS = 400L
        private const val SCROLL_TONE_STOP_MS = 220L
        private const val SCROLL_AFTER_SCREEN_CHANGE_GUARD_MS = 700L
        private const val BOUNDARY_NEEDS_RECENT_MID_MS = 1500L
        private const val HOVER_OWNS_FOCUS_MS = 1200L
        // Ver el comentario en handleFocusEvent(): tiempo que el dedo debe
        // "asentarse" en un elemento antes de anunciarlo al explorar.
        private const val HOVER_SPEAK_DEBOUNCE_MS = 90L
        private const val DELETE_ECHO_DEBOUNCE_MS = 200L
        private const val DUCKING_RELEASE_DELAY_MS = 500L

        // Alfabeto telefónico español, el de toda la vida (el mismo que usan
        // bancos y operadores por teléfono en España) -- no es propiedad de
        // ninguna app puntual.
        private val SPELLING_EXAMPLES_ES = mapOf(
            'A' to "Antonio", 'B' to "Barcelona", 'C' to "Carmen", 'D' to "Dolores",
            'E' to "Enrique", 'F' to "Francia", 'G' to "Gerona", 'H' to "Historia",
            'I' to "Inés", 'J' to "José", 'K' to "Kilo", 'L' to "Lorenzo",
            'M' to "Madrid", 'N' to "Navarra", 'Ñ' to "Ñoño", 'O' to "Oviedo",
            'P' to "París", 'Q' to "Queso", 'R' to "Ramón", 'S' to "Sábado",
            'T' to "Tarragona", 'U' to "Ulises", 'V' to "Valencia", 'W' to "Washington",
            'X' to "Xiquena", 'Y' to "Yegua", 'Z' to "Zaragoza"
        )

        // Alfabeto NATO/ICAO, el estándar público de toda la vida para
        // deletrear en inglés.
        private val SPELLING_EXAMPLES_EN = mapOf(
            'A' to "Alpha", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta",
            'E' to "Echo", 'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel",
            'I' to "India", 'J' to "Juliett", 'K' to "Kilo", 'L' to "Lima",
            'M' to "Mike", 'N' to "November", 'O' to "Oscar", 'P' to "Papa",
            'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango",
            'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray",
            'Y' to "Yankee", 'Z' to "Zulu"
        )
        // Vibración de exploración: muy corta y suave, para que no moleste al
        // recorrer la pantalla ni se solape con la voz.
        private const val HOVER_VIBRATION_MS = 28L
        private const val HOVER_VIBRATION_AMPLITUDE = 130  // 1..255
        // Un mismo texto se puede repetir pasado este tiempo: al escribir
        // rápido, tocar dos veces la misma tecla debe anunciarse dos veces.
        private const val REPEAT_SPEECH_AFTER_MS = 350L
        // Un elemento registrado hace menos de esto, cuando el anterior llevaba
        // más tiempo, viene del segundo toque del doble toque, no de una
        // exploración nueva del usuario (que se para a escuchar el anuncio).
        private const val DOUBLE_TAP_OWN_HOVER_MS = 320L

        // Anuncio de posición del cursor: por encima de esto, la selección se
        // dice como cantidad de caracteres en vez de leerla entera.
        private const val SELECTION_SPEAK_MAX_CHARS = 60
        // Tras P7 (recorrer texto) o al escribir/borrar, se ignora el cambio de
        // selección que llega justo después (ya se leyó por su propio camino).
        private const val CURSOR_ECHO_SUPPRESS_MS = 400L

        // Apps de mensajería: el campo de escribir se anuncia como "mensaje,
        // cuadro de edición" en vez de solo "cuadro de edición".
        private val MESSAGING_PACKAGES: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "org.thunderdog.challegram",
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.instagram.android",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.miui.mms",
            "com.discord",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.tencent.mm",
            "com.skype.raider",
            "org.telegram.messenger.beta"
        )
        // Eco de escritura: por encima de esto un borrado o una inserción en
        // bloque se anuncia por número de caracteres, no leyendo el texto.
        private const val TYPING_ECHO_MAX_CHARS = 30
        private const val SCROLL_TONE_VOL = 0.55f
        private const val BLIP_INTERVAL_MS = 110L
        private const val DIALOG_MAX_CHARS = 400
        private const val DIALOG_MAX_PARTS = 12
        // Firma de diálogo: 1-3 botones y árbol pequeño.
        private const val DIALOG_SCAN_DEPTH = 8
        private const val DIALOG_SCAN_MAX_NODES = 120
        private const val DIALOG_MAX_TREE_NODES = 40
    }
}
