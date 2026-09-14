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
     * campo equivalente -- así que no hay forma confiable de decir "voz de
     * hombre"/"voz de mujer" para cualquier motor instalado.
     *
     * El nombre técnico propio de cada voz (Voice.getName(), usado en un
     * intento anterior) tampoco sirve: son códigos como "es-es-x-eee-local"
     * que no dicen nada para alguien que no conoce esa nomenclatura. En vez
     * de eso, cuando el motor ofrece varias voces para el mismo idioma y
     * calidad (que displayName() por sí solo mostraría con el mismo texto
     * repetido, sin forma de distinguirlas), se numeran en el orden en que
     * el motor las entrega: "Español (España) (calidad alta), voz 1",
     * "...voz 2". No dice de qué se trata cada una, pero es simple,
     * entendible sin conocimiento técnico, y alcanza para elegir una
     * distinta si la primera no convence y comparar cuál es cuál.
     */
    fun displayNames(voices: List<Voice>): List<String> {
        val base = voices.map { displayName(it) }
        val counts = base.groupingBy { it }.eachCount()
        val seenSoFar = mutableMapOf<String, Int>()
        return base.map { label ->
            if ((counts[label] ?: 0) <= 1) return@map label
            val number = (seenSoFar[label] ?: 0) + 1
            seenSoFar[label] = number
            "$label, voz $number"
        }
    }
}
