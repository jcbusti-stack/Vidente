package com.vidente.app

import android.content.Context
import android.content.SharedPreferences

object VidentePreferences {
    const val PREFS_NAME = "vidente_prefs"
    const val KEY_RATE = "speech_rate"
    const val KEY_PITCH = "speech_pitch"
    const val KEY_VOICE_NAME = "voice_name"
    const val KEY_GEMINI_API_KEY = "gemini_api_key"

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

    fun getGeminiApiKey(context: Context): String? =
        prefs(context).getString(KEY_GEMINI_API_KEY, null)

    fun setGeminiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }
}
