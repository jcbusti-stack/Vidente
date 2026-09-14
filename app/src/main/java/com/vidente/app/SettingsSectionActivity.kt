package com.vidente.app

import android.Manifest
import android.content.Context
import android.content.Intent
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.RadioGroup
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
    private var enginePackages: List<String?> = listOf(null)

    // Motor y voz secundarios (P9: motor de voz dual): instancia y estado
    // propios, separados de la vista previa del motor principal de arriba.
    private var ttsSecondary: TextToSpeech? = null
    private var ttsSecondaryReady = false
    private var secondaryAvailableVoices: List<Voice> = emptyList()
    private var secondaryEnginePackages: List<String?> = listOf(null)

    private var seekRate: SeekBar? = null
    private var textRateValue: TextView? = null
    private var seekPitch: SeekBar? = null
    private var textPitchValue: TextView? = null
    private var spinnerLanguage: Spinner? = null
    private var spinnerEngine: Spinner? = null
    private var spinnerVoice: Spinner? = null
    private var spinnerEngineSecondary: Spinner? = null
    private var spinnerVoiceSecondary: Spinner? = null
    private var radioGroupAudioOutput: RadioGroup? = null
    private var radioGroupScrollFeedback: RadioGroup? = null
    private var radioGroupTypingEcho: RadioGroup? = null
    private var radioGroupKeyboardWriteMode: RadioGroup? = null

    private val languageValues = listOf(
        VidentePreferences.APP_LANGUAGE_SYSTEM, "es", "en", "fr", "de", "pt", "it"
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
            SECTION_VOICE_SECONDARY -> {
                titleRes = R.string.settings_section_voice_secondary
                layoutRes = R.layout.section_voice_secondary
            }
            SECTION_ALERTS -> { titleRes = R.string.settings_section_alerts; layoutRes = R.layout.section_alerts }
            SECTION_TYPING -> { titleRes = R.string.settings_section_typing; layoutRes = R.layout.section_typing }
            SECTION_SOUND -> { titleRes = R.string.settings_section_sound; layoutRes = R.layout.section_sound }
            SECTION_TUTORIAL -> { titleRes = R.string.settings_section_tutorial; layoutRes = R.layout.section_tutorial }
            SECTION_CONVERSATIONAL -> { titleRes = R.string.settings_section_conversational; layoutRes = R.layout.section_conversational }
            SECTION_GESTURES -> { titleRes = R.string.settings_section_gestures; layoutRes = R.layout.section_gestures }
            SECTION_GENERAL -> { titleRes = R.string.settings_section_general; layoutRes = R.layout.section_general }
            else -> { titleRes = R.string.settings_section_voice; layoutRes = R.layout.section_voice }
        }
        findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        layoutInflater.inflate(layoutRes, container, true)

        when (section) {
            SECTION_VOICE -> setUpVoiceSection()
            SECTION_VOICE_SECONDARY -> setUpVoiceSecondarySection()
            SECTION_ALERTS -> setUpAlertsSection()
            SECTION_TYPING -> setUpTypingSection()
            SECTION_SOUND -> setUpSoundSection()
            SECTION_TUTORIAL -> setUpTutorialSection()
            SECTION_CONVERSATIONAL -> setUpConversationalSection()
            SECTION_GESTURES -> setUpGesturesSection()
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
        spinnerEngine = findViewById(R.id.spinnerEngine)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        radioGroupAudioOutput = findViewById(R.id.radioGroupAudioOutput)

        currentRate = VidentePreferences.getRate(this)
        currentPitch = VidentePreferences.getPitch(this)

        setUpLanguageSpinner()
        setUpRateSeekBar()
        setUpPitchSeekBar()
        setUpAudioOutputSpinner()

        findViewById<Button>(R.id.buttonPreview).setOnClickListener { previewVoice() }

        val enginePackage = VidentePreferences.getEnginePackage(this)
        tts = if (enginePackage != null) TextToSpeech(this, this, enginePackage) else TextToSpeech(this, this)
    }

    /**
     * Adaptador para TODOS los Spinner de Ajustes, con vistas de texto plano.
     *
     * android.R.layout.simple_spinner_dropdown_item es un CheckedTextView
     * (verificado en el código del SDK de Android), así que usarlo como vista
     * principal del adaptador convertía cada Spinner cerrado en algo
     * "marcable": Vidente lo leía como "casilla, activado/desactivado", y en
     * la lista abierta cada opción sumaba además "seleccionado". Nada de eso
     * describe lo que el control hace realmente.
     *
     * simple_spinner_item (vista cerrada) y simple_list_item_1 (opciones de
     * la lista) son TextView planos: sin estado de marcado que anunciar.
     */
    private fun plainSpinnerAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_list_item_1)
        }

    /** Etiquetas y paquetes de los motores de TTS instalados, con "Predeterminado del sistema" primero. */
    private fun engineLabelsAndPackages(engine: TextToSpeech): Pair<List<String>, List<String?>> {
        val labels = mutableListOf(getString(R.string.settings_engine_system_default))
        val packages = mutableListOf<String?>(null)
        engine.engines?.forEach { info ->
            labels += info.label
            packages += info.name
        }
        return labels to packages
    }

    private fun setUpEngineSpinner() {
        val spinner = spinnerEngine ?: return
        val engine = tts ?: return
        val (labels, packages) = engineLabelsAndPackages(engine)
        enginePackages = packages
        spinner.adapter = plainSpinnerAdapter(labels)
        val saved = VidentePreferences.getEnginePackage(this)
        spinner.setSelection(packages.indexOf(saved).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chosen = enginePackages.getOrNull(position)
                if (chosen == VidentePreferences.getEnginePackage(this@SettingsSectionActivity)) return
                VidentePreferences.setEnginePackage(this@SettingsSectionActivity, chosen)
                tts?.shutdown()
                ttsReady = false
                tts = if (chosen != null) {
                    TextToSpeech(this@SettingsSectionActivity, this@SettingsSectionActivity, chosen)
                } else {
                    TextToSpeech(this@SettingsSectionActivity, this@SettingsSectionActivity)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
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
        spinner.adapter = plainSpinnerAdapter(labels)
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
        val group = radioGroupAudioOutput ?: return
        val idsByValue = mapOf(
            VidentePreferences.AUDIO_OUTPUT_MEDIA to R.id.radioAudioOutputMedia,
            VidentePreferences.AUDIO_OUTPUT_ACCESSIBILITY to R.id.radioAudioOutputAccessibility
        )
        val valuesById = idsByValue.entries.associate { (value, id) -> id to value }
        group.check(idsByValue[VidentePreferences.getAudioOutput(this)] ?: R.id.radioAudioOutputMedia)
        group.setOnCheckedChangeListener { _, checkedId ->
            valuesById[checkedId]?.let {
                VidentePreferences.setAudioOutput(this@SettingsSectionActivity, it)
                applyAudioOutputToTts()
            }
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
        setUpEngineSpinner()
        availableVoices = VoiceUtils.availableVoicesForLocale(engine, LocaleHelper.currentLocale(this))
        setUpVoiceSpinner()
    }

    private fun setUpVoiceSpinner() {
        val spinner = spinnerVoice ?: return
        val labels = mutableListOf(getString(R.string.settings_voice_auto))
        labels += availableVoices.map { VoiceUtils.displayName(it) }

        spinner.adapter = plainSpinnerAdapter(labels)

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

    // ---- Voz secundaria (P9: motor de voz dual, avanzado) ----

    private fun setUpVoiceSecondarySection() {
        spinnerEngineSecondary = findViewById(R.id.spinnerEngineSecondary)
        spinnerVoiceSecondary = findViewById(R.id.spinnerVoiceSecondary)
        findViewById<Button>(R.id.buttonPreviewSecondary).setOnClickListener { previewSecondaryVoice() }

        val enginePackage = VidentePreferences.getSecondaryEnginePackage(this)
        createSecondaryPreviewTts(enginePackage)
    }

    private fun createSecondaryPreviewTts(enginePackage: String?) {
        ttsSecondaryReady = false
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = ttsSecondary
            if (status != TextToSpeech.SUCCESS || engine == null) return@OnInitListener
            engine.language = LocaleHelper.currentLocale(this)
            ttsSecondaryReady = true
            setUpEngineSecondarySpinner()
            secondaryAvailableVoices = VoiceUtils.availableVoicesForLocale(engine, LocaleHelper.currentLocale(this))
            setUpVoiceSecondarySpinner()
        }
        ttsSecondary = if (enginePackage != null) {
            TextToSpeech(this, listener, enginePackage)
        } else {
            TextToSpeech(this, listener)
        }
    }

    private fun setUpEngineSecondarySpinner() {
        val spinner = spinnerEngineSecondary ?: return
        val engine = ttsSecondary ?: return
        val (labels, packages) = engineLabelsAndPackages(engine)
        secondaryEnginePackages = packages
        spinner.adapter = plainSpinnerAdapter(labels)
        val saved = VidentePreferences.getSecondaryEnginePackage(this)
        spinner.setSelection(packages.indexOf(saved).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chosen = secondaryEnginePackages.getOrNull(position)
                if (chosen == VidentePreferences.getSecondaryEnginePackage(this@SettingsSectionActivity)) return
                VidentePreferences.setSecondaryEnginePackage(this@SettingsSectionActivity, chosen)
                ttsSecondary?.shutdown()
                createSecondaryPreviewTts(chosen)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setUpVoiceSecondarySpinner() {
        val spinner = spinnerVoiceSecondary ?: return
        val labels = mutableListOf(getString(R.string.settings_voice_auto))
        labels += secondaryAvailableVoices.map { VoiceUtils.displayName(it) }
        spinner.adapter = plainSpinnerAdapter(labels)

        val savedVoiceName = VidentePreferences.getSecondaryVoiceName(this)
        val savedIndex = secondaryAvailableVoices.indexOfFirst { it.name == savedVoiceName }
        spinner.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val voiceName = if (position == 0) null else secondaryAvailableVoices[position - 1].name
                VidentePreferences.setSecondaryVoiceName(this@SettingsSectionActivity, voiceName)
                val engine = ttsSecondary ?: return
                val voice = secondaryAvailableVoices.firstOrNull { it.name == voiceName }
                engine.voice = voice ?: VoiceUtils.bestVoiceForLocale(engine, LocaleHelper.currentLocale(this@SettingsSectionActivity))
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun previewSecondaryVoice() {
        val engine = ttsSecondary ?: return
        if (!ttsSecondaryReady) return
        engine.setSpeechRate(currentRate)
        engine.setPitch(currentPitch)
        engine.speak(getString(R.string.settings_preview_text), TextToSpeech.QUEUE_FLUSH, null, PREVIEW_SECONDARY_UTTERANCE_ID)
    }

    // ---- Avisos puntuales ----

    private fun setUpAlertsSection() {
        setUpBooleanCheckbox(R.id.checkboxAnnounceTimeOnUnlock, VidentePreferences.getAnnounceTimeOnUnlock(this)) {
            VidentePreferences.setAnnounceTimeOnUnlock(this, it)
        }
        setUpBooleanCheckbox(R.id.checkboxAnnounceLowBattery, VidentePreferences.getAnnounceLowBattery(this)) {
            VidentePreferences.setAnnounceLowBattery(this, it)
        }
        setUpBooleanCheckbox(R.id.checkboxAnnounceNotifications, VidentePreferences.getAnnounceNotifications(this)) {
            VidentePreferences.setAnnounceNotifications(this, it)
        }
        setUpBooleanCheckbox(
            R.id.checkboxAnnounceChargerConnected,
            VidentePreferences.getAnnounceChargerConnected(this)
        ) {
            VidentePreferences.setAnnounceChargerConnected(this, it)
        }
        setUpBooleanCheckbox(
            R.id.checkboxAnnounceChargerDisconnected,
            VidentePreferences.getAnnounceChargerDisconnected(this)
        ) {
            VidentePreferences.setAnnounceChargerDisconnected(this, it)
        }
        setUpBooleanCheckbox(R.id.checkboxAnnounceFullBattery, VidentePreferences.getAnnounceFullBattery(this)) {
            VidentePreferences.setAnnounceFullBattery(this, it)
        }
        setUpBooleanCheckbox(
            R.id.checkboxAnnounceAudioDeviceConnected,
            VidentePreferences.getAnnounceAudioDeviceConnected(this)
        ) {
            VidentePreferences.setAnnounceAudioDeviceConnected(this, it)
        }
        setUpBooleanCheckbox(
            R.id.checkboxAnnounceAudioDeviceDisconnected,
            VidentePreferences.getAnnounceAudioDeviceDisconnected(this)
        ) {
            VidentePreferences.setAnnounceAudioDeviceDisconnected(this, it)
        }
        setUpBooleanCheckbox(R.id.checkboxAnnounceAirplaneMode, VidentePreferences.getAnnounceAirplaneMode(this)) {
            VidentePreferences.setAnnounceAirplaneMode(this, it)
        }
    }

    /**
     * Helper para cualquier opción sí/no de Ajustes: una sola casilla
     * (CheckBox), marcada = activado / sin marcar = desactivado (regla del
     * proyecto: ni Switch ni RadioGroup de dos opciones para esto).
     */
    private fun setUpBooleanCheckbox(checkboxId: Int, currentValue: Boolean, onChange: (Boolean) -> Unit) {
        val checkbox = findViewById<CheckBox>(checkboxId)
        checkbox.isChecked = currentValue
        checkbox.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
    }

    // ---- Escritura y teclado ----

    private fun setUpTypingSection() {
        radioGroupTypingEcho = findViewById(R.id.radioGroupTypingEcho)
        val group = radioGroupTypingEcho ?: return
        val idsByValue = mapOf(
            VidentePreferences.TYPING_ECHO_CHARS_WORDS to R.id.radioTypingEchoCharsWords,
            VidentePreferences.TYPING_ECHO_CHARS to R.id.radioTypingEchoChars,
            VidentePreferences.TYPING_ECHO_WORDS to R.id.radioTypingEchoWords,
            VidentePreferences.TYPING_ECHO_NONE to R.id.radioTypingEchoNone
        )
        val valuesById = idsByValue.entries.associate { (value, id) -> id to value }
        group.check(idsByValue[VidentePreferences.getTypingEcho(this)] ?: R.id.radioTypingEchoCharsWords)
        group.setOnCheckedChangeListener { _, checkedId ->
            valuesById[checkedId]?.let {
                VidentePreferences.setTypingEcho(this@SettingsSectionActivity, it)
            }
        }

        setUpKeyboardWriteModeSpinner()
        setUpCursorAnnounceSpinner()
        setUpBooleanCheckbox(R.id.checkboxAnnounceUppercase, VidentePreferences.getAnnounceUppercase(this)) {
            VidentePreferences.setAnnounceUppercase(this, it)
        }
    }

    private fun setUpKeyboardWriteModeSpinner() {
        radioGroupKeyboardWriteMode = findViewById(R.id.radioGroupKeyboardWriteMode)
        val group = radioGroupKeyboardWriteMode ?: return
        val idsByValue = mapOf(
            VidentePreferences.WRITE_MODE_DOUBLE_TAP to R.id.radioKeyboardWriteModeDoubleTap,
            VidentePreferences.WRITE_MODE_SLIDE_RELEASE to R.id.radioKeyboardWriteModeSlideRelease
        )
        val valuesById = idsByValue.entries.associate { (value, id) -> id to value }
        group.check(idsByValue[VidentePreferences.getKeyboardWriteMode(this)] ?: R.id.radioKeyboardWriteModeDoubleTap)
        group.setOnCheckedChangeListener { _, checkedId ->
            valuesById[checkedId]?.let {
                VidentePreferences.setKeyboardWriteMode(this@SettingsSectionActivity, it)
            }
        }
    }

    private fun setUpCursorAnnounceSpinner() {
        setUpBooleanCheckbox(
            R.id.checkboxCursorAnnounce,
            VidentePreferences.getCursorAnnounce(this) == VidentePreferences.CURSOR_ANNOUNCE_ON
        ) { isChecked ->
            VidentePreferences.setCursorAnnounce(
                this,
                if (isChecked) VidentePreferences.CURSOR_ANNOUNCE_ON else VidentePreferences.CURSOR_ANNOUNCE_OFF
            )
        }
    }

    // ---- Sonidos y vibración ----

    private fun setUpSoundSection() {
        radioGroupScrollFeedback = findViewById(R.id.radioGroupScrollFeedback)
        val group = radioGroupScrollFeedback ?: return
        val idsByValue = mapOf(
            VidentePreferences.SCROLL_FEEDBACK_TONE to R.id.radioScrollFeedbackTone,
            VidentePreferences.SCROLL_FEEDBACK_VOICE to R.id.radioScrollFeedbackVoice
        )
        val valuesById = idsByValue.entries.associate { (value, id) -> id to value }
        group.check(idsByValue[VidentePreferences.getScrollFeedback(this)] ?: R.id.radioScrollFeedbackTone)
        group.setOnCheckedChangeListener { _, checkedId ->
            valuesById[checkedId]?.let {
                VidentePreferences.setScrollFeedback(this@SettingsSectionActivity, it)
            }
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

    // ---- Gestos ----

    /**
     * Una fila por GESTO (etiqueta + Spinner con las acciones disponibles),
     * armadas a partir de GestureConfig.GESTURES en vez de estar escritas en
     * el XML: así la pantalla no puede quedar desincronizada de la lista real
     * de gestos si en el futuro se agrega o saca alguno.
     *
     * Al estilo del lector chino TalkMan/Jieshuo: cada gesto es la fila, y se
     * elige qué acción dispara. Varios gestos pueden compartir la misma
     * acción sin problema; lo único que no puede pasar (que un gesto dispare
     * dos acciones) ya es imposible porque cada gesto es una sola fila.
     *
     * Se usa Spinner (y no casillas ni RadioGroup) porque cada gesto elige
     * entre una lista larga -- 11 acciones más "Sin acción asignada".
     */
    private fun setUpGesturesSection() {
        renderGestureRows()
        findViewById<Button>(R.id.buttonResetGestures).setOnClickListener {
            VidentePreferences.setGestureActionMap(this, GestureConfig.DEFAULT_MAP)
            renderGestureRows()
            Toast.makeText(this, R.string.settings_gestures_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Mapa actual para mostrar: si el servicio nunca llegó a guardarlo
     * (Ajustes abierto antes de activar Vidente por primera vez), se muestran
     * los valores por defecto en vez de una pantalla con todo sin asignar.
     */
    private fun currentGestureMap(): Map<Int, String> =
        VidentePreferences.getGestureActionMap(this) ?: GestureConfig.DEFAULT_MAP

    private fun renderGestureRows() {
        val container = findViewById<LinearLayout>(R.id.containerGestures) ?: return
        container.removeAllViews()

        // "Sin acción asignada" va primero: es la opción que deja ese gesto
        // sin disparar nada.
        val actionLabels = listOf(getString(R.string.gesture_action_none)) +
            GestureConfig.ACTIONS.map { getString(it.labelRes) }
        val actionNames: List<String?> = listOf(null) + GestureConfig.ACTIONS.map { it.name }

        val map = currentGestureMap()

        GestureConfig.GESTURES.forEach { gesture ->
            val gestureLabel = getString(gesture.labelRes)

            val label = TextView(this).apply {
                text = gestureLabel
                textSize = 16f
                // Visible para quien ve la pantalla, pero fuera del árbol de
                // accesibilidad: el nombre del gesto ya va en la descripción
                // del Spinner de abajo, y si no habría que pasar por dos
                // elementos que dicen casi lo mismo.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            container.addView(label)

            val currentIndex = actionNames.indexOf(map[gesture.id]).coerceAtLeast(0)

            val spinner = Spinner(this).apply {
                // El anuncio dice el gesto Y la acción que tiene asignada
                // ("Deslizar arriba y volver abajo, repetir la última
                // frase"): poner solo el nombre del gesto dejaba al usuario
                // sin saber qué acción disparaba, porque una descripción en
                // el Spinner tapa el texto de su contenido.
                contentDescription = getString(
                    R.string.settings_gesture_row_description,
                    gestureLabel,
                    actionLabels[currentIndex]
                )
                adapter = plainSpinnerAdapter(actionLabels)
                setSelection(currentIndex)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        val chosen = actionNames.getOrNull(position)
                        val saved = currentGestureMap()
                        // El propio setSelection de arriba dispara este
                        // callback: sin esta comparación, abrir la pantalla
                        // reescribiría el mapa sin que el usuario haya
                        // tocado nada.
                        if (chosen == saved[gesture.id]) return
                        val updated = GestureConfig.withGestureAssignment(saved, gesture.id, chosen)
                        VidentePreferences.setGestureActionMap(this@SettingsSectionActivity, updated)
                        // Cada gesto es su propia fila: asignarle una acción
                        // nunca le cambia el gesto a ninguna otra fila, así
                        // que alcanza con actualizar la descripción de esta
                        // misma, sin redibujar toda la pantalla.
                        contentDescription = getString(
                            R.string.settings_gesture_row_description,
                            gestureLabel,
                            actionLabels[position]
                        )
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
            container.addView(
                spinner,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (24 * resources.displayMetrics.density).toInt() }
            )
        }
    }

    // ---- Ajustes generales ----

    private fun setUpGeneralSection() {
        findViewById<Button>(R.id.buttonReset).setOnClickListener { resetToDefaults() }
        findViewById<Button>(R.id.buttonReopenWizard).setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
    }

    private fun resetToDefaults() {
        VidentePreferences.setRate(this, VidentePreferences.DEFAULT_RATE)
        VidentePreferences.setPitch(this, VidentePreferences.DEFAULT_PITCH)
        VidentePreferences.setVoiceName(this, null)
        VidentePreferences.setEnginePackage(this, null)
        VidentePreferences.setSecondaryEnginePackage(this, null)
        VidentePreferences.setSecondaryVoiceName(this, null)
        VidentePreferences.setAnnounceTimeOnUnlock(this, VidentePreferences.DEFAULT_ANNOUNCE_TIME_ON_UNLOCK)
        VidentePreferences.setAnnounceLowBattery(this, VidentePreferences.DEFAULT_ANNOUNCE_LOW_BATTERY)
        VidentePreferences.setAnnounceNotifications(this, VidentePreferences.DEFAULT_ANNOUNCE_NOTIFICATIONS)
        VidentePreferences.setAnnounceChargerConnected(this, VidentePreferences.DEFAULT_ANNOUNCE_CHARGER_CONNECTED)
        VidentePreferences.setAnnounceChargerDisconnected(
            this,
            VidentePreferences.DEFAULT_ANNOUNCE_CHARGER_DISCONNECTED
        )
        VidentePreferences.setAnnounceFullBattery(this, VidentePreferences.DEFAULT_ANNOUNCE_FULL_BATTERY)
        VidentePreferences.setAnnounceAudioDeviceConnected(
            this,
            VidentePreferences.DEFAULT_ANNOUNCE_AUDIO_DEVICE_CONNECTED
        )
        VidentePreferences.setAnnounceAudioDeviceDisconnected(
            this,
            VidentePreferences.DEFAULT_ANNOUNCE_AUDIO_DEVICE_DISCONNECTED
        )
        VidentePreferences.setAnnounceAirplaneMode(this, VidentePreferences.DEFAULT_ANNOUNCE_AIRPLANE_MODE)
        VidentePreferences.setAudioOutput(this, VidentePreferences.DEFAULT_AUDIO_OUTPUT)
        VidentePreferences.setScrollFeedback(this, VidentePreferences.DEFAULT_SCROLL_FEEDBACK)
        VidentePreferences.setTypingEcho(this, VidentePreferences.DEFAULT_TYPING_ECHO)
        VidentePreferences.setKeyboardWriteMode(this, VidentePreferences.DEFAULT_KEYBOARD_WRITE_MODE)
        VidentePreferences.setCursorAnnounce(this, VidentePreferences.DEFAULT_CURSOR_ANNOUNCE)
        VidentePreferences.setAnnounceUppercase(this, VidentePreferences.DEFAULT_ANNOUNCE_UPPERCASE)
        VidentePreferences.setGestureActionMap(this, GestureConfig.DEFAULT_MAP)
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        ttsSecondary?.stop()
        ttsSecondary?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_REQUEST_MIC = "request_mic"
        const val SECTION_VOICE = "voice"
        const val SECTION_VOICE_SECONDARY = "voice_secondary"
        const val SECTION_ALERTS = "alerts"
        const val SECTION_TYPING = "typing"
        const val SECTION_SOUND = "sound"
        const val SECTION_TUTORIAL = "tutorial"
        const val SECTION_CONVERSATIONAL = "conversational"
        const val SECTION_GESTURES = "gestures"
        const val SECTION_GENERAL = "general"

        private const val SEEK_STEPS = 100
        private const val PREVIEW_UTTERANCE_ID = "vidente_preview"
        private const val PREVIEW_SECONDARY_UTTERANCE_ID = "vidente_preview_secondary"
    }
}
