package com.vidente.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Menú de Ajustes: solo la lista de categorías. Cada una abre su propia
 * pantalla (SettingsSectionActivity); desde ahí se vuelve aquí.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        openSection(R.id.buttonSectionVoice, SettingsSectionActivity.SECTION_VOICE)
        openSection(R.id.buttonSectionTyping, SettingsSectionActivity.SECTION_TYPING)
        openSection(R.id.buttonSectionSound, SettingsSectionActivity.SECTION_SOUND)
        openSection(R.id.buttonSectionTutorial, SettingsSectionActivity.SECTION_TUTORIAL)
        openSection(R.id.buttonSectionConversational, SettingsSectionActivity.SECTION_CONVERSATIONAL)
        openSection(R.id.buttonSectionGeneral, SettingsSectionActivity.SECTION_GENERAL)
    }

    private fun openSection(buttonId: Int, section: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            startActivity(
                Intent(this, SettingsSectionActivity::class.java)
                    .putExtra(SettingsSectionActivity.EXTRA_SECTION, section)
            )
        }
    }
}
