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
