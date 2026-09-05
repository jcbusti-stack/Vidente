package com.vidente.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var availableVoices: List<Voice> = emptyList()

    private lateinit var seekRate: SeekBar
    private lateinit var textRateValue: TextView
    private lateinit var seekPitch: SeekBar
    private lateinit var textPitchValue: TextView
    private lateinit var spinnerVoice: Spinner

    private var currentRate = VidentePreferences.DEFAULT_RATE
    private var currentPitch = VidentePreferences.DEFAULT_PITCH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        seekRate = findViewById(R.id.seekRate)
        textRateValue = findViewById(R.id.textRateValue)
        seekPitch = findViewById(R.id.seekPitch)
        textPitchValue = findViewById(R.id.textPitchValue)
        spinnerVoice = findViewById(R.id.spinnerVoice)

        currentRate = VidentePreferences.getRate(this)
        currentPitch = VidentePreferences.getPitch(this)

        setUpRateSeekBar()
        setUpPitchSeekBar()

        findViewById<Button>(R.id.buttonPreview).setOnClickListener { previewVoice() }
        findViewById<Button>(R.id.buttonReset).setOnClickListener { resetToDefaults() }

        tts = TextToSpeech(this, this)
    }

    private fun setUpRateSeekBar() {
        seekRate.max = SEEK_STEPS
        seekRate.progress = rateToProgress(currentRate)
        updateRateLabel(currentRate)
        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentRate = progressToRate(progress)
                updateRateLabel(currentRate)
                VidentePreferences.setRate(this@SettingsActivity, currentRate)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setUpPitchSeekBar() {
        seekPitch.max = SEEK_STEPS
        seekPitch.progress = pitchToProgress(currentPitch)
        updatePitchLabel(currentPitch)
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentPitch = progressToPitch(progress)
                updatePitchLabel(currentPitch)
                VidentePreferences.setPitch(this@SettingsActivity, currentPitch)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateRateLabel(rate: Float) {
        textRateValue.text = getString(R.string.settings_rate_value, rate)
    }

    private fun updatePitchLabel(pitch: Float) {
        textPitchValue.text = getString(R.string.settings_pitch_value, pitch)
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

        engine.language = Locale.getDefault()
        ttsReady = true

        availableVoices = VoiceUtils.availableVoicesForLocale(engine, Locale.getDefault())
        setUpVoiceSpinner()
    }

    private fun setUpVoiceSpinner() {
        val labels = mutableListOf(getString(R.string.settings_voice_auto))
        labels += availableVoices.map { VoiceUtils.displayName(it) }

        spinnerVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val savedVoiceName = VidentePreferences.getVoiceName(this)
        val savedIndex = availableVoices.indexOfFirst { it.name == savedVoiceName }
        spinnerVoice.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val voiceName = if (position == 0) null else availableVoices[position - 1].name
                VidentePreferences.setVoiceName(this@SettingsActivity, voiceName)
                applyVoiceToTts(voiceName)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun applyVoiceToTts(voiceName: String?) {
        val engine = tts ?: return
        val voice = availableVoices.firstOrNull { it.name == voiceName }
        engine.voice = voice ?: VoiceUtils.bestVoiceForLocale(engine, Locale.getDefault())
    }

    private fun previewVoice() {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.setSpeechRate(currentRate)
        engine.setPitch(currentPitch)
        applyVoiceToTts(VidentePreferences.getVoiceName(this))
        engine.speak(getString(R.string.settings_preview_text), TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_ID)
    }

    private fun resetToDefaults() {
        currentRate = VidentePreferences.DEFAULT_RATE
        currentPitch = VidentePreferences.DEFAULT_PITCH
        VidentePreferences.setRate(this, currentRate)
        VidentePreferences.setPitch(this, currentPitch)
        VidentePreferences.setVoiceName(this, null)

        seekRate.progress = rateToProgress(currentRate)
        seekPitch.progress = pitchToProgress(currentPitch)
        updateRateLabel(currentRate)
        updatePitchLabel(currentPitch)
        spinnerVoice.setSelection(0)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SEEK_STEPS = 100
        private const val PREVIEW_UTTERANCE_ID = "vidente_preview"
    }
}
