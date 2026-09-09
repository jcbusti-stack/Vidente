package com.vidente.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.buttonOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonOpenVoiceSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // El servicio abre esta pantalla con este extra cuando falta el permiso
        // de micrófono para el modo conversacional; se reenvía a su sección de
        // Ajustes, que es donde vive el botón de activarlo.
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) {
            startActivity(
                Intent(this, SettingsSectionActivity::class.java)
                    .putExtra(SettingsSectionActivity.EXTRA_SECTION, SettingsSectionActivity.SECTION_CONVERSATIONAL)
                    .putExtra(SettingsSectionActivity.EXTRA_REQUEST_MIC, true)
            )
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
    }
}
