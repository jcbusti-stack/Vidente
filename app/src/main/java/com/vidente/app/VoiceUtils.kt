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

    /**
     * Una etiqueta por voz, en el mismo orden que [voices], ya diferenciadas
     * entre sí. La API pública de Android (android.speech.tts.Voice) no
     * expone el género de la voz -- no existe ningún Voice.getGender() ni
     * campo equivalente -- así que cuando el motor instalado ofrece varias
     * voces para el mismo idioma y calidad (que displayName() por sí solo
     * mostraría con el mismo texto repetido, sin forma de distinguirlas),
     * se les agrega el nombre técnico propio de cada una (Voice.getName()).
     * Ese nombre no está pensado para mostrarse tal cual -- así lo aclara la
     * documentación de Android -- pero es el único dato que el sistema sí
     * garantiza distinto entre voces, y de todos modos se puede leer en voz
     * alta letra por letra: alcanza para notar "esta es distinta de esa",
     * aunque no diga de qué se trata cada una.
     */
    fun displayNames(voices: List<Voice>): List<String> {
        val base = voices.map { displayName(it) }
        val counts = base.groupingBy { it }.eachCount()
        return base.mapIndexed { i, label ->
            if ((counts[label] ?: 0) > 1) "$label, ${voices[i].name}" else label
        }
    }
}
