package com.vidente.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Pantalla de una sola categoría de Ajustes. Infla dentro de un contenedor
 * común el bloque de controles de la sección pedida y engancha su lógica.
 * "Volver" (o el gesto Atrás) regresa al menú de categorías.
 */
class SettingsSectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var section: String = SECTION_VOICE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val messageRes = if (granted) R.string.mic_permission_granted else R.string.mic_permission_denied
            Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var availableVoices: List<Voice> = emptyList()

    private var seekRate: SeekBar? = null
    private var textRateValue: TextView? = null
    private var seekPitch: SeekBar? = null
    private var textPitchValue: TextView? = null
    private var spinnerLanguage: Spinner? = null
    private var spinnerVoice: Spinner? = null
    private var spinnerAudioOutput: Spinner? = null
    private var spinnerScrollFeedback: Spinner? = null
    private var spinnerTypingEcho: Spinner? = null
    private var spinnerKeyboardWriteMode: Spinner? = null
    private var spinnerCursorAnnounce: Spinner? = null

    private val audioOutputValues = listOf(
        VidentePreferences.AUDIO_OUTPUT_MEDIA,
        VidentePreferences.AUDIO_OUTPUT_ACCESSIBILITY
    )
    private val scrollFeedbackValues = listOf(
        VidentePreferences.SCROLL_FEEDBACK_TONE,
        VidentePreferences.SCROLL_FEEDBACK_VOICE
    )
    private val typingEchoValues = listOf(
        VidentePreferences.TYPING_ECHO_CHARS_WORDS,
        VidentePreferences.TYPING_ECHO_CHARS,
        VidentePreferences.TYPING_ECHO_WORDS,
        VidentePreferences.TYPING_ECHO_NONE
    )
    private val languageValues = listOf(
        VidentePreferences.APP_LANGUAGE_SYSTEM, "es", "en", "fr", "de", "pt", "it"
    )
    private val keyboardWriteModeValues = listOf(
        VidentePreferences.WRITE_MODE_DOUBLE_TAP,
        VidentePreferences.WRITE_MODE_SLIDE_RELEASE
    )
    private val cursorAnnounceValues = listOf(
        VidentePreferences.CURSOR_ANNOUNCE_ON,
        VidentePreferences.CURSOR_ANNOUNCE_OFF
    )

    private var currentRate = VidentePreferences.DEFAULT_RATE
    private var currentPitch = VidentePreferences.DEFAULT_PITCH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION) ?: SECTION_VOICE

        setContentView(R.layout.activity_settings_section)
        findViewById<Button>(R.id.buttonBack).setOnClickListener { finish() }

        val container = findViewById<ViewGroup>(R.id.sectionContent)
        val titleRes: Int
        val layoutRes: Int
        when (section) {
            SECTION_TYPING -> { titleRes = R.string.settings_section_typing; layoutRes = R.layout.section_typing }
            SECTION_SOUND -> { titleRes = R.string.settings_section_sound; layoutRes = R.layout.section_sound }
            SECTION_TUTORIAL -> { titleRes = R.string.settings_section_tutorial; layoutRes = R.layout.section_tutorial }
            SECTION_CONVERSATIONAL -> { titleRes = R.string.settings_section_conversational; layoutRes = R.layout.section_conversational }
            SECTION_GENERAL -> { titleRes = R.string.settings_section_general; layoutRes = R.layout.section_general }
            else -> { titleRes = R.string.settings_section_voice; layoutRes = R.layout.section_voice }
        }
        findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        layoutInflater.inflate(layoutRes, container, true)

        when (section) {
            SECTION_VOICE -> setUpVoiceSection()
            SECTION_TYPING -> setUpTypingSection()
            SECTION_SOUND -> setUpSoundSection()
            SECTION_TUTORIAL -> setUpTutorialSection()
            SECTION_CONVERSATIONAL -> setUpConversationalSection()
            SECTION_GENERAL -> setUpGeneralSection()
        }
    }

    // ---- Voz y lectura ----

    private fun setUpVoiceSection() {
        seekRate = findViewById(R.id.seekRate)
        textRateValue = findViewById(R.id.textRateValue)
        seekPitch = findViewById(R.id.seekPitch)
        textPitchValue = findViewById(R.id.textPitchValue)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        spinnerAudioOutput = findViewById(R.id.spinnerAudioOutput)

        currentRate = VidentePreferences.getRate(this)
        currentPitch = VidentePreferences.getPitch(this)

        setUpLanguageSpinner()
        setUpRateSeekBar()
        setUpPitchSeekBar()
        setUpAudioOutputSpinner()

        findViewById<Button>(R.id.buttonPreview).setOnClickListener { previewVoice() }

        tts = TextToSpeech(this, this)
    }

    private fun setUpLanguageSpinner() {
        val spinner = spinnerLanguage ?: return
        val labels = listOf(
            getString(R.string.settings_language_system),
            getString(R.string.language_name_es),
            getString(R.string.language_name_en),
            getString(R.string.language_name_fr),
            getString(R.string.language_name_de),
            getString(R.string.language_name_pt),
            getString(R.string.language_name_it)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(languageValues.indexOf(VidentePreferences.getAppLanguage(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chosen = languageValues[position]
                if (chosen == VidentePreferences.getAppLanguage(this@SettingsSectionActivity)) return
                VidentePreferences.setAppLanguage(this@SettingsSectionActivity, chosen)
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setUpRateSeekBar() {
        val bar = seekRate ?: return
        bar.max = SEEK_STEPS
        bar.progress = rateToProgress(currentRate)
        updateRateLabel(currentRate)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentRate = progressToRate(progress)
                updateRateLabel(currentRate)
                VidentePreferences.setRate(this@SettingsSectionActivity, currentRate)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setUpPitchSeekBar() {
        val bar = seekPitch ?: return
        bar.max = SEEK_STEPS
        bar.progress = pitchToProgress(currentPitch)
        updatePitchLabel(currentPitch)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentPitch = progressToPitch(progress)
                updatePitchLabel(currentPitch)
                VidentePreferences.setPitch(this@SettingsSectionActivity, currentPitch)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setUpAudioOutputSpinner() {
        val spinner = spinnerAudioOutput ?: return
        val labels = listOf(
            getString(R.string.settings_audio_output_media),
            getString(R.string.settings_audio_output_accessibility)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(audioOutputValues.indexOf(VidentePreferences.getAudioOutput(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                VidentePreferences.setAudioOutput(this@SettingsSectionActivity, audioOutputValues[position])
                applyAudioOutputToTts()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun applyAudioOutputToTts() {
        val engine = tts ?: return
        val usage = if (VidentePreferences.getAudioOutput(this) == VidentePreferences.AUDIO_OUTPUT_ACCESSIBILITY) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    private fun updateRateLabel(rate: Float) {
        textRateValue?.text = getString(R.string.settings_rate_value, rate)
    }

    private fun updatePitchLabel(pitch: Float) {
        textPitchValue?.text = getString(R.string.settings_pitch_value, pitch)
    }

    private fun rateToProgress(rate: Float): Int = (
        (rate - VidentePreferences.MIN_RATE) /
            (VidentePreferences.MAX_RATE - VidentePreferences.MIN_RATE) * SEEK_STEPS
        ).toInt()

    private fun progressToRate(progress: Int): Float =
        VidentePreferences.MIN_RATE +
            (VidentePreferences.MAX_RATE - VidentePreferences.MIN_RATE) * (progress / SEEK_STEPS.toFloat())

    private fun pitchToProgress(pitch: Float): Int = (
        (pitch - VidentePreferences.MIN_PITCH) /
            (VidentePreferences.MAX_PITCH - VidentePreferences.MIN_PITCH) * SEEK_STEPS
        ).toInt()

    private fun progressToPitch(progress: Int): Float =
        VidentePreferences.MIN_PITCH +
            (VidentePreferences.MAX_PITCH - VidentePreferences.MIN_PITCH) * (progress / SEEK_STEPS.toFloat())

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return

        engine.language = LocaleHelper.currentLocale(this)
        ttsReady = true

        applyAudioOutputToTts()
        availableVoices = VoiceUtils.availableVoicesForLocale(engine, LocaleHelper.currentLocale(this))
        setUpVoiceSpinner()
    }

    private fun setUpVoiceSpinner() {
        val spinner = spinnerVoice ?: return
        val labels = mutableListOf(getString(R.string.settings_voice_auto))
        labels += availableVoices.map { VoiceUtils.displayName(it) }

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val savedVoiceName = VidentePreferences.getVoiceName(this)
        val savedIndex = availableVoices.indexOfFirst { it.name == savedVoiceName }
        spinner.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val voiceName = if (position == 0) null else availableVoices[position - 1].name
                VidentePreferences.setVoiceName(this@SettingsSectionActivity, voiceName)
                applyVoiceToTts(voiceName)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun applyVoiceToTts(voiceName: String?) {
        val engine = tts ?: return
        val voice = availableVoices.firstOrNull { it.name == voiceName }
        engine.voice = voice ?: VoiceUtils.bestVoiceForLocale(engine, LocaleHelper.currentLocale(this))
    }

    private fun previewVoice() {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.setSpeechRate(currentRate)
        engine.setPitch(currentPitch)
        applyVoiceToTts(VidentePreferences.getVoiceName(this))
        engine.speak(getString(R.string.settings_preview_text), TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_ID)
    }

    // ---- Escritura y teclado ----

    private fun setUpTypingSection() {
        spinnerTypingEcho = findViewById(R.id.spinnerTypingEcho)
        val spinner = spinnerTypingEcho ?: return
        val labels = listOf(
            getString(R.string.settings_typing_echo_chars_words),
            getString(R.string.settings_typing_echo_chars),
            getString(R.string.settings_typing_echo_words),
            getString(R.string.settings_typing_echo_none)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(typingEchoValues.indexOf(VidentePreferences.getTypingEcho(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                VidentePreferences.setTypingEcho(this@SettingsSectionActivity, typingEchoValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        setUpKeyboardWriteModeSpinner()
        setUpCursorAnnounceSpinner()
    }

    private fun setUpKeyboardWriteModeSpinner() {
        spinnerKeyboardWriteMode = findViewById(R.id.spinnerKeyboardWriteMode)
        val spinner = spinnerKeyboardWriteMode ?: return
        val labels = listOf(
            getString(R.string.settings_keyboard_write_mode_double_tap),
            getString(R.string.settings_keyboard_write_mode_slide_release)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(
            keyboardWriteModeValues.indexOf(VidentePreferences.getKeyboardWriteMode(this)).coerceAtLeast(0)
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                VidentePreferences.setKeyboardWriteMode(
                    this@SettingsSectionActivity, keyboardWriteModeValues[position]
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setUpCursorAnnounceSpinner() {
        spinnerCursorAnnounce = findViewById(R.id.spinnerCursorAnnounce)
        val spinner = spinnerCursorAnnounce ?: return
        val labels = listOf(
            getString(R.string.settings_cursor_announce_on),
            getString(R.string.settings_cursor_announce_off)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(
            cursorAnnounceValues.indexOf(VidentePreferences.getCursorAnnounce(this)).coerceAtLeast(0)
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                VidentePreferences.setCursorAnnounce(this@SettingsSectionActivity, cursorAnnounceValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ---- Sonidos y vibración ----

    private fun setUpSoundSection() {
        spinnerScrollFeedback = findViewById(R.id.spinnerScrollFeedback)
        val spinner = spinnerScrollFeedback ?: return
        val labels = listOf(
            getString(R.string.settings_scroll_feedback_tone),
            getString(R.string.settings_scroll_feedback_voice)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(scrollFeedbackValues.indexOf(VidentePreferences.getScrollFeedback(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                VidentePreferences.setScrollFeedback(this@SettingsSectionActivity, scrollFeedbackValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ---- Tutorial ----

    private fun setUpTutorialSection() {
        findViewById<Button>(R.id.buttonReplayTutorial).setOnClickListener {
            VidentePreferences.setTutorialRequested(this, true)
            Toast.makeText(this, R.string.settings_replay_tutorial_started, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Asistente conversacional ----

    private fun setUpConversationalSection() {
        val editBackendUrl = findViewById<EditText>(R.id.editBackendUrl)
        val editBackendAccessKey = findViewById<EditText>(R.id.editBackendAccessKey)
        editBackendUrl.setText(VidentePreferences.getBackendUrl(this).orEmpty())
        editBackendAccessKey.setText(VidentePreferences.getBackendAccessKey(this).orEmpty())
        findViewById<Button>(R.id.buttonSaveBackendConfig).setOnClickListener {
            VidentePreferences.setBackendUrl(this, editBackendUrl.text.toString())
            VidentePreferences.setBackendAccessKey(this, editBackendAccessKey.text.toString())
            Toast.makeText(this, R.string.settings_backend_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.buttonRequestMic).setOnClickListener { requestMicPermissionIfNeeded() }

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) requestMicPermissionIfNeeded()
    }

    private fun requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.mic_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---- Ajustes generales ----

    private fun setUpGeneralSection() {
        findViewById<Button>(R.id.buttonReset).setOnClickListener { resetToDefaults() }
    }

    private fun resetToDefaults() {
        VidentePreferences.setRate(this, VidentePreferences.DEFAULT_RATE)
        VidentePreferences.setPitch(this, VidentePreferences.DEFAULT_PITCH)
        VidentePreferences.setVoiceName(this, null)
        VidentePreferences.setAudioOutput(this, VidentePreferences.DEFAULT_AUDIO_OUTPUT)
        VidentePreferences.setScrollFeedback(this, VidentePreferences.DEFAULT_SCROLL_FEEDBACK)
        VidentePreferences.setTypingEcho(this, VidentePreferences.DEFAULT_TYPING_ECHO)
        VidentePreferences.setKeyboardWriteMode(this, VidentePreferences.DEFAULT_KEYBOARD_WRITE_MODE)
        VidentePreferences.setCursorAnnounce(this, VidentePreferences.DEFAULT_CURSOR_ANNOUNCE)
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_REQUEST_MIC = "request_mic"
        const val SECTION_VOICE = "voice"
        const val SECTION_TYPING = "typing"
        const val SECTION_SOUND = "sound"
        const val SECTION_TUTORIAL = "tutorial"
        const val SECTION_CONVERSATIONAL = "conversational"
        const val SECTION_GENERAL = "general"

        private const val SEEK_STEPS = 100
        private const val PREVIEW_UTTERANCE_ID = "vidente_preview"
    }
}
