package com.vidente.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONObject

object VidentePreferences {
    const val PREFS_NAME = "vidente_prefs"
    const val KEY_RATE = "speech_rate"
    const val KEY_PITCH = "speech_pitch"
    const val KEY_VOICE_NAME = "voice_name"
    const val KEY_ENGINE_PACKAGE = "engine_package"
    const val KEY_SECONDARY_ENGINE_PACKAGE = "secondary_engine_package"
    const val KEY_SECONDARY_VOICE_NAME = "secondary_voice_name"
    const val KEY_BACKEND_URL = "backend_url"
    const val KEY_BACKEND_ACCESS_KEY = "backend_access_key"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_TUTORIAL_DONE = "tutorial_done"
    const val KEY_TUTORIAL_REQUESTED = "tutorial_requested"
    // Xiaomi no deja consultar por código si "Inicio automático en segundo
    // plano" quedó activado: el propio usuario lo marca a mano en el
    // asistente de configuración después de activarlo.
    const val KEY_XIAOMI_AUTOSTART_CONFIRMED = "xiaomi_autostart_confirmed"
    // P9 avisos puntuales: hora al desbloquear, por la voz secundaria.
    const val KEY_ANNOUNCE_TIME_ON_UNLOCK = "announce_time_on_unlock"
    const val DEFAULT_ANNOUNCE_TIME_ON_UNLOCK = true
    const val KEY_ANNOUNCE_LOW_BATTERY = "announce_low_battery"
    const val DEFAULT_ANNOUNCE_LOW_BATTERY = true
    const val KEY_ANNOUNCE_NOTIFICATIONS = "announce_notifications"
    const val DEFAULT_ANNOUNCE_NOTIFICATIONS = true
    const val KEY_ANNOUNCE_CHARGER_CONNECTED = "announce_charger_connected"
    const val DEFAULT_ANNOUNCE_CHARGER_CONNECTED = true
    const val KEY_ANNOUNCE_CHARGER_DISCONNECTED = "announce_charger_disconnected"
    const val DEFAULT_ANNOUNCE_CHARGER_DISCONNECTED = true
    const val KEY_ANNOUNCE_FULL_BATTERY = "announce_full_battery"
    const val DEFAULT_ANNOUNCE_FULL_BATTERY = true
    const val KEY_ANNOUNCE_AUDIO_DEVICE_CONNECTED = "announce_audio_device_connected"
    const val DEFAULT_ANNOUNCE_AUDIO_DEVICE_CONNECTED = true
    const val KEY_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED = "announce_audio_device_disconnected"
    const val DEFAULT_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED = true
    const val KEY_ANNOUNCE_AIRPLANE_MODE = "announce_airplane_mode"
    const val DEFAULT_ANNOUNCE_AIRPLANE_MODE = true
    const val KEY_AUDIO_OUTPUT = "audio_output"

    // Ruta de audio del TTS. "media" usa USAGE_MEDIA, que sigue la salida
    // Bluetooth igual que la música; "accessibility" usa
    // USAGE_ASSISTANCE_ACCESSIBILITY (volumen independiente, suena sobre
    // llamadas), pero en varios teléfonos no se enruta al Bluetooth.
    const val AUDIO_OUTPUT_MEDIA = "media"
    const val AUDIO_OUTPUT_ACCESSIBILITY = "accessibility"
    const val DEFAULT_AUDIO_OUTPUT = AUDIO_OUTPUT_MEDIA

    // Aviso de posición al hacer scroll (P8c): tono variable o porcentaje hablado.
    const val KEY_SCROLL_FEEDBACK = "scroll_feedback"
    const val SCROLL_FEEDBACK_TONE = "tone"
    const val SCROLL_FEEDBACK_VOICE = "voice"
    const val DEFAULT_SCROLL_FEEDBACK = SCROLL_FEEDBACK_TONE

    // Eco de escritura al teclear (P8c parte 2): qué se dice al escribir en un
    // campo de texto. Nunca se leen los caracteres de un campo de contraseña.
    const val KEY_TYPING_ECHO = "typing_echo"
    const val TYPING_ECHO_NONE = "none"
    const val TYPING_ECHO_CHARS = "chars"
    const val TYPING_ECHO_WORDS = "words"
    const val TYPING_ECHO_CHARS_WORDS = "chars_words"
    const val DEFAULT_TYPING_ECHO = TYPING_ECHO_CHARS_WORDS

    // Idioma de la app y de la voz. "system" = seguir el idioma del teléfono;
    // si no, una etiqueta BCP-47 ("es", "en", "fr", "de", "pt", "it").
    const val KEY_APP_LANGUAGE = "app_language"
    const val APP_LANGUAGE_SYSTEM = "system"
    const val DEFAULT_APP_LANGUAGE = APP_LANGUAGE_SYSTEM

    // Modo de escritura en teclado: doble toque (el de siempre) o deslizar y
    // soltar (estilo Jieshuo/TalkBack: se recorre el teclado con el dedo y al
    // levantarlo sobre una tecla, esa tecla se escribe). Por defecto queda el
    // comportamiento de siempre.
    const val KEY_KEYBOARD_WRITE_MODE = "keyboard_write_mode"
    const val WRITE_MODE_DOUBLE_TAP = "double_tap"
    const val WRITE_MODE_SLIDE_RELEASE = "slide_release"
    const val DEFAULT_KEYBOARD_WRITE_MODE = WRITE_MODE_DOUBLE_TAP

    // Anuncio de posición del cursor: al moverlo dentro de un campo de texto,
    // decir "Principio/Final del texto" en los extremos, leer el carácter
    // recorrido, y leer la selección si hay texto seleccionado. Por defecto
    // activado (pedido explícito), pero apagable de un toque si algo falla.
    const val KEY_CURSOR_ANNOUNCE = "cursor_announce"
    const val CURSOR_ANNOUNCE_ON = "on"
    const val CURSOR_ANNOUNCE_OFF = "off"
    const val DEFAULT_CURSOR_ANNOUNCE = CURSOR_ANNOUNCE_ON

    // Aviso de "Mayúscula" antes de una letra mayúscula sola, al teclear un
    // carácter suelto, al explorar carácter por carácter (P7) o al leer el
    // carácter que el cursor acaba de pasar. Activado por defecto, como
    // TalkBack.
    const val KEY_ANNOUNCE_UPPERCASE = "announce_uppercase"
    const val DEFAULT_ANNOUNCE_UPPERCASE = true

    // Palabra de ejemplo al deletrear ("A, de Antonio"), al teclear un
    // carácter suelto o al explorar carácter por carácter (P7); no al mover
    // el cursor con flechas, ahí sería demasiado seguido. Desactivado por
    // defecto: no hay un lector de pantalla de referencia que lo traiga de
    // fábrica, y no queremos sumar verbosidad sin que el usuario la pida.
    const val KEY_ANNOUNCE_SPELLING_EXAMPLE = "announce_spelling_example"
    const val DEFAULT_ANNOUNCE_SPELLING_EXAMPLE = false

    // Anuncio de cada tecla al explorar el teclado en pantalla con el dedo
    // (antes de tocarla para escribir). Activado por defecto: es el
    // comportamiento de siempre.
    const val KEY_ANNOUNCE_KEYBOARD_EXPLORATION = "announce_keyboard_exploration"
    const val DEFAULT_ANNOUNCE_KEYBOARD_EXPLORATION = true

    // Configuración de gestos (paso 2 de "gestos personalizables"): qué
    // acción dispara cada gesto. Se guarda como texto (JSON de
    // gesto -> nombre de la acción) porque SharedPreferences no admite
    // mapas directamente. Sin pantalla propia todavía: por ahora solo
    // guarda los valores por defecto.
    const val KEY_GESTURE_ACTION_MAP = "gesture_action_map"

    const val DEFAULT_RATE = 1.15f
    const val DEFAULT_PITCH = 1.0f
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.5f
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRate(context: Context): Float =
        prefs(context).getFloat(KEY_RATE, DEFAULT_RATE)

    fun setRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_RATE, rate).apply()
    }

    fun getPitch(context: Context): Float =
        prefs(context).getFloat(KEY_PITCH, DEFAULT_PITCH)

    fun setPitch(context: Context, pitch: Float) {
        prefs(context).edit().putFloat(KEY_PITCH, pitch).apply()
    }

    fun getVoiceName(context: Context): String? =
        prefs(context).getString(KEY_VOICE_NAME, null)

    fun setVoiceName(context: Context, voiceName: String?) {
        prefs(context).edit().putString(KEY_VOICE_NAME, voiceName).apply()
    }

    // Motor de TTS: paquete del motor instalado (Google, BookFusion Voice,
    // SherpaTTS, etc.). null = motor predeterminado del sistema.
    fun getEnginePackage(context: Context): String? =
        prefs(context).getString(KEY_ENGINE_PACKAGE, null)

    fun setEnginePackage(context: Context, enginePackage: String?) {
        prefs(context).edit().putString(KEY_ENGINE_PACKAGE, enginePackage).apply()
    }

    // Motor y voz secundarios (P9: motor de voz dual, para avisos puntuales
    // que todavía no existen -- por ahora solo configurable y de prueba).
    fun getSecondaryEnginePackage(context: Context): String? =
        prefs(context).getString(KEY_SECONDARY_ENGINE_PACKAGE, null)

    fun setSecondaryEnginePackage(context: Context, enginePackage: String?) {
        prefs(context).edit().putString(KEY_SECONDARY_ENGINE_PACKAGE, enginePackage).apply()
    }

    fun getSecondaryVoiceName(context: Context): String? =
        prefs(context).getString(KEY_SECONDARY_VOICE_NAME, null)

    fun setSecondaryVoiceName(context: Context, voiceName: String?) {
        prefs(context).edit().putString(KEY_SECONDARY_VOICE_NAME, voiceName).apply()
    }

    fun getBackendUrl(context: Context): String? =
        prefs(context).getString(KEY_BACKEND_URL, null)?.trimEnd('/')?.takeIf { it.isNotBlank() }

    fun setBackendUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BACKEND_URL, url.trim().trimEnd('/')).apply()
    }

    fun getBackendAccessKey(context: Context): String? =
        prefs(context).getString(KEY_BACKEND_ACCESS_KEY, null)

    fun setBackendAccessKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_BACKEND_ACCESS_KEY, key.trim()).apply()
    }

    /** Se marca cuando el tutorial de bienvenida se muestra por primera vez. */
    fun isTutorialDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TUTORIAL_DONE, false)

    fun setTutorialDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_TUTORIAL_DONE, done).apply()
    }

    fun getAnnounceTimeOnUnlock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_TIME_ON_UNLOCK, DEFAULT_ANNOUNCE_TIME_ON_UNLOCK)

    fun setAnnounceTimeOnUnlock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_TIME_ON_UNLOCK, enabled).apply()
    }

    fun getAnnounceLowBattery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_LOW_BATTERY, DEFAULT_ANNOUNCE_LOW_BATTERY)

    fun setAnnounceLowBattery(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_LOW_BATTERY, enabled).apply()
    }

    fun getAnnounceNotifications(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_NOTIFICATIONS, DEFAULT_ANNOUNCE_NOTIFICATIONS)

    fun setAnnounceNotifications(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_NOTIFICATIONS, enabled).apply()
    }

    fun getAnnounceChargerConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_CHARGER_CONNECTED, DEFAULT_ANNOUNCE_CHARGER_CONNECTED)

    fun setAnnounceChargerConnected(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_CHARGER_CONNECTED, enabled).apply()
    }

    fun getAnnounceChargerDisconnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_CHARGER_DISCONNECTED, DEFAULT_ANNOUNCE_CHARGER_DISCONNECTED)

    fun setAnnounceChargerDisconnected(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_CHARGER_DISCONNECTED, enabled).apply()
    }

    fun getAnnounceFullBattery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_FULL_BATTERY, DEFAULT_ANNOUNCE_FULL_BATTERY)

    fun setAnnounceFullBattery(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_FULL_BATTERY, enabled).apply()
    }

    fun getAnnounceAudioDeviceConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_AUDIO_DEVICE_CONNECTED, DEFAULT_ANNOUNCE_AUDIO_DEVICE_CONNECTED)

    fun setAnnounceAudioDeviceConnected(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_AUDIO_DEVICE_CONNECTED, enabled).apply()
    }

    fun getAnnounceAudioDeviceDisconnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED, DEFAULT_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED)

    fun setAnnounceAudioDeviceDisconnected(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED, enabled).apply()
    }

    fun getAnnounceAirplaneMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_AIRPLANE_MODE, DEFAULT_ANNOUNCE_AIRPLANE_MODE)

    fun setAnnounceAirplaneMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_AIRPLANE_MODE, enabled).apply()
    }

    fun isXiaomiAutostartConfirmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_XIAOMI_AUTOSTART_CONFIRMED, false)

    fun setXiaomiAutostartConfirmed(context: Context, confirmed: Boolean) {
        prefs(context).edit().putBoolean(KEY_XIAOMI_AUTOSTART_CONFIRMED, confirmed).apply()
    }

    /**
     * Bandera que Ajustes pone a true para pedirle al servicio que repita el
     * tutorial; el servicio la vuelve a false en cuanto lo arranca.
     */
    fun isTutorialRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TUTORIAL_REQUESTED, false)

    fun setTutorialRequested(context: Context, requested: Boolean) {
        prefs(context).edit().putBoolean(KEY_TUTORIAL_REQUESTED, requested).apply()
    }

    fun getAudioOutput(context: Context): String =
        prefs(context).getString(KEY_AUDIO_OUTPUT, DEFAULT_AUDIO_OUTPUT) ?: DEFAULT_AUDIO_OUTPUT

    fun setAudioOutput(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AUDIO_OUTPUT, value).apply()
    }

    fun getScrollFeedback(context: Context): String =
        prefs(context).getString(KEY_SCROLL_FEEDBACK, DEFAULT_SCROLL_FEEDBACK) ?: DEFAULT_SCROLL_FEEDBACK

    fun setScrollFeedback(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SCROLL_FEEDBACK, value).apply()
    }

    fun getTypingEcho(context: Context): String =
        prefs(context).getString(KEY_TYPING_ECHO, DEFAULT_TYPING_ECHO) ?: DEFAULT_TYPING_ECHO

    fun setTypingEcho(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TYPING_ECHO, value).apply()
    }

    fun getAppLanguage(context: Context): String =
        prefs(context).getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE) ?: DEFAULT_APP_LANGUAGE

    fun setAppLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_APP_LANGUAGE, value).apply()
    }

    fun getKeyboardWriteMode(context: Context): String =
        prefs(context).getString(KEY_KEYBOARD_WRITE_MODE, DEFAULT_KEYBOARD_WRITE_MODE)
            ?: DEFAULT_KEYBOARD_WRITE_MODE

    fun setKeyboardWriteMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_KEYBOARD_WRITE_MODE, value).apply()
    }

    fun getCursorAnnounce(context: Context): String =
        prefs(context).getString(KEY_CURSOR_ANNOUNCE, DEFAULT_CURSOR_ANNOUNCE) ?: DEFAULT_CURSOR_ANNOUNCE

    fun setCursorAnnounce(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CURSOR_ANNOUNCE, value).apply()
    }

    fun getAnnounceUppercase(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_UPPERCASE, DEFAULT_ANNOUNCE_UPPERCASE)

    fun setAnnounceUppercase(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_UPPERCASE, enabled).apply()
    }

    fun getAnnounceSpellingExample(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_SPELLING_EXAMPLE, DEFAULT_ANNOUNCE_SPELLING_EXAMPLE)

    fun setAnnounceSpellingExample(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_SPELLING_EXAMPLE, enabled).apply()
    }

    fun getAnnounceKeyboardExploration(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCE_KEYBOARD_EXPLORATION, DEFAULT_ANNOUNCE_KEYBOARD_EXPLORATION)

    fun setAnnounceKeyboardExploration(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCE_KEYBOARD_EXPLORATION, enabled).apply()
    }

    /**
     * Mapa gesto -> nombre de acción guardado, o null si todavía no se guardó
     * nada (primera vez) o si lo guardado no se pudo interpretar (dato
     * corrupto, o de una versión vieja/nueva incompatible). Devuelve null y
     * no un mapa vacío a propósito: un mapa vacío es un estado válido y
     * distinto (el usuario dejó TODAS las acciones sin gesto asignado), y
     * confundirlo con "no hay nada guardado" haría que los valores por
     * defecto volvieran solos en el próximo arranque.
     *
     * Este objeto no conoce el enum de acciones de
     * VidenteAccessibilityService (igual que no conoce NavMode ni el resto):
     * trabaja solo con texto, y es quien llama el que convierte a su propio
     * tipo.
     */
    fun getGestureActionMap(context: Context): Map<Int, String>? {
        val raw = prefs(context).getString(KEY_GESTURE_ACTION_MAP, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<Int, String>()
            json.keys().forEach { key ->
                val gestureId = key.toIntOrNull()
                if (gestureId != null) result[gestureId] = json.getString(key)
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    fun setGestureActionMap(context: Context, map: Map<Int, String>) {
        val json = JSONObject()
        map.forEach { (gestureId, actionName) -> json.put(gestureId.toString(), actionName) }
        prefs(context).edit().putString(KEY_GESTURE_ACTION_MAP, json.toString()).apply()
    }

    /**
     * Random ID generated once per install (no login/email required) so the
     * backend can track the free monthly question limit per device.
     */
    fun getDeviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}
