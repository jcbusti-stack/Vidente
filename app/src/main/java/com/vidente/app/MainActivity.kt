package com.vidente.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Punto de entrada del launcher: no muestra nada propio, solo decide a dónde
 * redirigir y se cierra. Apertura normal -> el asistente de configuración.
 * Si el servicio la abrió porque falta el permiso de micrófono del modo
 * conversacional -> directo a esa sección de Ajustes.
 */
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) {
            startActivity(
                Intent(this, SettingsSectionActivity::class.java)
                    .putExtra(SettingsSectionActivity.EXTRA_SECTION, SettingsSectionActivity.SECTION_CONVERSATIONAL)
                    .putExtra(SettingsSectionActivity.EXTRA_REQUEST_MIC, true)
            )
        } else {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
    }
}
