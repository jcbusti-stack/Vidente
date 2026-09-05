package com.vidente.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Calls the Vidente backend (Node/Express, deployed separately) which in
 * turn holds the real Gemini API key server-side. The app never sees that
 * key; it only knows the backend's URL and, optionally, a shared access
 * key used to keep the endpoint from being called by anyone else.
 */
class BackendConversationalAssistant(private val context: Context) : ConversationalAssistant {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun answer(question: String, screenSummary: String, callback: (String) -> Unit) {
        val backendUrl = VidentePreferences.getBackendUrl(context)
        if (backendUrl.isNullOrBlank()) {
            deliver(callback, "No hay un servidor configurado. Revisa Ajustes de voz.")
            return
        }

        executor.execute {
            val result = try {
                requestAnswer(backendUrl, question, screenSummary)
            } catch (error: Exception) {
                Log.e(TAG, "Error consultando el backend de Vidente", error)
                "No se pudo conectar con el servidor de Vidente. Revisa tu conexión a internet."
            }
            deliver(callback, result)
        }
    }

    private fun deliver(callback: (String) -> Unit, text: String) {
        mainHandler.post { callback(text) }
    }

    private fun requestAnswer(backendUrl: String, question: String, screenSummary: String): String {
        val connection = URL("$backendUrl/ask").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")

        val accessKey = VidentePreferences.getBackendAccessKey(context)
        if (!accessKey.isNullOrBlank()) {
            connection.setRequestProperty("x-vidente-key", accessKey)
        }

        val payload = JSONObject().apply {
            put("question", question)
            put("screenSummary", screenSummary)
        }

        connection.outputStream.use { stream ->
            OutputStreamWriter(stream).use { it.write(payload.toString()) }
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode !in 200..299) {
            Log.e(TAG, "El backend respondió $responseCode: $body")
            return "El servidor de Vidente respondió con un error."
        }

        val answer = JSONObject(body).optString("answer")
        return answer.ifBlank { "El servidor no envió una respuesta." }
    }

    companion object {
        private const val TAG = "BackendAssistant"
        private const val TIMEOUT_MS = 15000
    }
}
