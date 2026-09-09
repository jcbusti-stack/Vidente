package com.vidente.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Aplica el idioma elegido en Ajustes ("Idioma") a un Context, sin tocar el
 * idioma de todo el teléfono. Si la preferencia es "seguir al sistema" se
 * devuelve el Context tal cual.
 */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val tag = VidentePreferences.getAppLanguage(base)
        if (tag == VidentePreferences.APP_LANGUAGE_SYSTEM) return base

        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Locale que debe usar la voz del lector. */
    fun currentLocale(context: Context): Locale {
        val tag = VidentePreferences.getAppLanguage(context)
        return if (tag == VidentePreferences.APP_LANGUAGE_SYSTEM) {
            Locale.getDefault()
        } else {
            Locale.forLanguageTag(tag)
        }
    }
}
