package com.aero.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        findViewById<MaterialButton>(R.id.assistantButton).setOnClickListener {
            requestAssistantRole()
        }

        // ---- Accessibility & Permissions: entry → dedicated screen ----

        findViewById<MaterialButton>(R.id.permEntryButton).setOnClickListener {
            try {
                startActivity(
                    Intent(this, AccessibilityPermissionsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
    }

    // True while we've asked the system to switch the assistant role.
    private var assistantRequestPending = false

    private fun requestAssistantRole() {
        // Open the SYSTEM digital assistant picker directly (Settings → Apps →
        // Default apps → Digital assistant), so the user can pick Aero.
        // RoleManager's request dialog auto-cancels on many devices.
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent("android.settings.DEFAULT_APPS_SETTINGS"),
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$DefaultAppsSettingsActivity"
                )
            )
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    assistantRequestPending = true
                    startActivity(intent)
                    return
                } catch (_: Exception) {
                }
            }
        }
        // Ultimate fallback: RoleManager request.
        try {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                assistantRequestPending = true
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        } catch (_: Exception) {
        }
        findViewById<TextView>(R.id.settingsStatus).text =
            "Couldn't open assistant settings on this device."
    }

    override fun onResume() {
        super.onResume()
        if (!assistantRequestPending) return
        assistantRequestPending = false
        val roleManager = getSystemService(RoleManager::class.java)
        val held = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        val status = findViewById<TextView>(R.id.settingsStatus)
        if (held) {
            status.text = "Aero is now your default assistant."
            AeroState.assistantRoleGranted = true
        } else {
            status.text =
                "Not changed. You can also set it manually: Settings → Apps → Default apps → Digital assistant → Aero."
        }
    }

    companion object {
        private const val REQUEST_ASSISTANT_ROLE = 7701
    }
}
