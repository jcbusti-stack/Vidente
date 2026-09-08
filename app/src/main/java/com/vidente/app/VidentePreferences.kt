package com.vidente.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object VidentePreferences {
    const val PREFS_NAME = "vidente_prefs"
    const val KEY_RATE = "speech_rate"
    const val KEY_PITCH = "speech_pitch"
    const val KEY_VOICE_NAME = "voice_name"
    const val KEY_BACKEND_URL = "backend_url"
    const val KEY_BACKEND_ACCESS_KEY = "backend_access_key"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_TUTORIAL_DONE = "tutorial_done"
    const val KEY_TUTORIAL_REQUESTED = "tutorial_requested"
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
