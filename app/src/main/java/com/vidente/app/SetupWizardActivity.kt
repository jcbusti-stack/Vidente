package com.vidente.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Primera pantalla al abrir Vidente: guía paso a paso por los ajustes del
 * teléfono que hacen falta para que funcione bien, en vez de mandar solo a
 * Ajustes de accesibilidad. Inspirado en el asistente de configuración de
 * Jieshuo (TalkMan), adaptado a lo que Vidente realmente usa.
 *
 * De los 8 pasos de Jieshuo, solo aplican 4 a Vidente hoy: superposición no
 * hace falta (el botón flotante usa el tipo de ventana que ya dan las apps
 * de accesibilidad, sin permiso aparte), y notificaciones/archivos/alarma no
 * los usa ningún código de Vidente todavía.
 */
class SetupWizardActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val isXiaomi: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.contains("xiaomi", ignoreCase = true) ||
                it.contains("redmi", ignoreCase = true) ||
                it.contains("poco", ignoreCase = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        findViewById<Button>(R.id.buttonStepBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                openAppSettings()
            }
        }

        findViewById<Button>(R.id.buttonStepMic).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }

        if (isXiaomi) {
            findViewById<Button>(R.id.buttonStepAutostart).visibility = View.VISIBLE
            findViewById<TextView>(R.id.textStepAutostartHint).visibility = View.VISIBLE
            findViewById<Button>(R.id.buttonStepAutostart).setOnClickListener { openXiaomiAutostart() }

            val checkConfirmed = findViewById<CheckBox>(R.id.checkStepAutostartConfirmed)
            checkConfirmed.visibility = View.VISIBLE
            checkConfirmed.isChecked = VidentePreferences.isXiaomiAutostartConfirmed(this)
            checkConfirmed.setOnCheckedChangeListener { _, isChecked ->
                VidentePreferences.setXiaomiAutostartConfirmed(this, isChecked)
                refreshStepLabels()
            }
        }

        findViewById<Button>(R.id.buttonStepAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.buttonOpenAppSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStepLabels()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStepLabels()
    }

    private fun refreshStepLabels() {
        findViewById<Button>(R.id.buttonStepBattery).text =
            getString(R.string.wizard_step_battery, statusText(isIgnoringBatteryOptimizations()))
        findViewById<Button>(R.id.buttonStepMic).text =
            getString(R.string.wizard_step_mic, statusText(hasMicPermission()))
        if (isXiaomi) {
            // Xiaomi no expone forma de consultar este ajuste por código: el
            // estado viene de la casilla que el propio usuario marca a mano.
            val status = if (VidentePreferences.isXiaomiAutostartConfirmed(this)) {
                getString(R.string.wizard_status_done)
            } else {
                getString(R.string.wizard_status_unknown)
            }
            findViewById<Button>(R.id.buttonStepAutostart).text =
                getString(R.string.wizard_step_autostart, status)
        }
        findViewById<Button>(R.id.buttonStepAccessibility).text =
            getString(R.string.wizard_step_accessibility, statusText(isAccessibilityServiceEnabled()))
    }

    private fun statusText(done: Boolean): String =
        getString(if (done) R.string.wizard_status_done else R.string.wizard_status_pending)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        // "enabled_accessibility_services": no está expuesta como constante
        // pública en Settings.Secure, aunque es la clave real del sistema.
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_accessibility_services"
        ) ?: return false
        val serviceId = "$packageName/${VidenteAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(serviceId, ignoreCase = true) }
    }

    /**
     * Intent conocido (no documentado oficialmente por Xiaomi) para la
     * pantalla de "Inicio automático" de MIUI/HyperOS. Puede fallar en
     * versiones futuras del sistema; si no resuelve, se cae a la
     * información de la app como alternativa.
     */
    private fun openXiaomiAutostart() {
        val intent = Intent().apply {
            component = android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.wizard_step_autostart_hint, Toast.LENGTH_LONG).show()
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    companion object {
        private const val REQUEST_MIC = 1
    }
}
