package com.vidente.app

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object VoiceUtils {

    fun availableVoicesForLocale(engine: TextToSpeech, locale: Locale): List<Voice> =
        engine.voices
            ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
            ?.sortedByDescending { it.quality }
            ?: emptyList()

    fun bestVoiceForLocale(engine: TextToSpeech, locale: Locale): Voice? =
        availableVoicesForLocale(engine, locale).firstOrNull()

    fun displayName(voice: Voice): String {
        val qualityLabel = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "muy alta"
            voice.quality >= Voice.QUALITY_HIGH -> "alta"
            voice.quality >= Voice.QUALITY_NORMAL -> "normal"
            else -> "baja"
        }
        return "${voice.locale.displayName} (calidad $qualityLabel)"
    }
}
