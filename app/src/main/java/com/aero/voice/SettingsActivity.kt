package com.aero.voice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val voices = arrayOf("Kore", "Puck", "Charon", "Fenrir", "Aoede")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val keyInput = findViewById<EditText>(R.id.keyInput)
        val modelInput = findViewById<EditText>(R.id.modelInput)
        val voiceSpinner = findViewById<Spinner>(R.id.voiceSpinner)
        val promptInput = findViewById<EditText>(R.id.promptInput)
        val status = findViewById<TextView>(R.id.settingsStatus)

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

        keyInput.setText(Prefs.apiKey(this) ?: "")
        modelInput.setText(Prefs.modelId(this))
        promptInput.setText(Prefs.systemPrompt(this))
        val savedVoice = Prefs.voice(this)
        val idx = voices.indexOfFirst { it == savedVoice }.coerceAtLeast(0)
        voiceSpinner.setSelection(idx)

        findViewById<TextView>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val key = keyInput.text.toString().trim()
            val model = modelInput.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL }
            if (key.isEmpty()) {
                status.text = "Please enter your Google AI Studio API key."
                return@setOnClickListener
            }
            val voice = voices[voiceSpinner.selectedItemPosition]
            val prompt = promptInput.text.toString().trim().ifEmpty { Prefs.DEFAULT_PROMPT }
            Prefs.save(this, key, model, voice, prompt)
            status.text = "Saved. Testing key…"
            testKey(key, status)
        }
    }

    private fun testKey(key: String, status: TextView) {
        Thread {
            val result = try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                when (conn.responseCode) {
                    200 -> "API key OK ✓  You're ready to talk."
                    else -> "API key rejected (HTTP ${conn.responseCode}). Check the key."
                }
            } catch (e: Exception) {
                "Network error: ${e.message}"
            }
            handler.post { status.text = result }
        }.start()
    }
}
