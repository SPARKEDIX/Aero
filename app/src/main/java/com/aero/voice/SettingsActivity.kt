package com.aero.voice

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private val voices = arrayOf("Kore", "Puck", "Charon", "Fenrir", "Aoede")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val keyInput = findViewById<EditText>(R.id.keyInput)
        val keyEye = findViewById<ImageButton>(R.id.keyEyeButton)
        val voiceSpinner = findViewById<Spinner>(R.id.voiceSpinner)
        val moodSpinner = findViewById<Spinner>(R.id.moodSpinner)
        val moodHint = findViewById<TextView>(R.id.moodHint)
        val status = findViewById<TextView>(R.id.settingsStatus)

        // Show / hide the API key. Starts hidden (dots).
        var keyVisible = false
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyEye.setOnClickListener {
            keyVisible = !keyVisible
            if (keyVisible) {
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                keyEye.setImageResource(R.drawable.ic_eye_off)
            } else {
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                keyEye.setImageResource(R.drawable.ic_eye)
            }
            keyInput.setSelection(keyInput.text?.length ?: 0)
        }

        // Color theme: Light keeps the current look; Dark and System are dark.
        val themeGroup = findViewById<RadioGroup>(R.id.themeRadioGroup)
        themeGroup.check(
            when (Prefs.theme(this)) {
                Prefs.THEME_DARK -> R.id.radioThemeDark
                Prefs.THEME_SYSTEM -> R.id.radioThemeSystem
                else -> R.id.radioThemeLight
            }
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radioThemeDark -> Prefs.THEME_DARK
                R.id.radioThemeSystem -> Prefs.THEME_SYSTEM
                else -> Prefs.THEME_LIGHT
            }
            if (selected != Prefs.theme(this)) {
                Prefs.saveTheme(this, selected)
                Theme.apply(this)
                recreate()
            }
        }

        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        voiceSpinner.setSelection(
            voices.indexOfFirst { it == Prefs.voice(this) }.coerceAtLeast(0)
        )

        // Mood replaces the old Model ID + System instruction sections.
        val moodKeys = MoodPalette.ALL
        val moodLabels = moodKeys.map { MoodPalette.forKey(it).label }
        moodSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, moodLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        moodSpinner.setSelection(moodKeys.indexOf(Prefs.mood(this)).coerceAtLeast(0))
        moodHint.text = MoodPalette.forKey(Prefs.mood(this)).useCase
        moodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val key = moodKeys[pos]
                val hint = MoodPalette.forKey(key).useCase
                if (moodHint.text.toString() != hint) {
                    Prefs.saveMood(this@SettingsActivity, key)
                    moodHint.text = hint
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        keyInput.setText(Prefs.apiKey(this) ?: "")

        findViewById<TextView>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) {
                status.text = "Please enter your Google AI Studio API key."
                return@setOnClickListener
            }
            val voice = voices[voiceSpinner.selectedItemPosition]
            Prefs.save(this, key, voice)
            status.text = "Saved."
        }
    }
}
