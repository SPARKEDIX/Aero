package com.aero.voice

import android.Manifest
import android.app.role.RoleManager
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

        // ---- Accessibility & Permissions section ----

        val permStatus = findViewById<TextView>(R.id.permStatus)

        fun refreshPermStatus() {
            val a11y = AeroAccessibilityService.isEnabled()
            val overlay = Settings.canDrawOverlays(this)
            val write = Settings.System.canWrite(this)
            val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            permStatus.text = buildString {
                appendLine("Accessibility: " + if (a11y) "✓ ON" else "✗ OFF")
                appendLine("Overlay: " + if (overlay) "✓ ON" else "✗ OFF")
                appendLine("Modify settings: " + if (write) "✓ ON" else "✗ OFF")
                appendLine("Microphone: " + if (mic) "✓" else "✗")
                appendLine("Camera: " + if (cam) "✓" else "✗")
                appendLine("Contacts: " + if (contacts) "✓" else "✗")
                appendLine("Phone: " + if (phone) "✓" else "✗")
            }.trim()
        }

        findViewById<MaterialButton>(R.id.permAccessibility).setOnClickListener {
            ScreenShareHelper.openAccessibilitySettings(this)
        }
        findViewById<MaterialButton>(R.id.permOverlay).setOnClickListener {
            ScreenShareHelper.openOverlaySettings(this)
        }
        findViewById<MaterialButton>(R.id.permWriteSettings).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            } catch (_: Exception) {
            }
        }
        findViewById<MaterialButton>(R.id.permBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
        findViewById<MaterialButton>(R.id.permRuntime).setOnClickListener {
            runtimePermLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE
                )
            )
        }
        findViewById<MaterialButton>(R.id.permScreen).setOnClickListener {
            // Launch the system screen-capture consent via the transparent activity.
            try {
                startActivity(
                    Intent(this, ScreenShareConsentActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }

        // Refresh status when returning from system settings/permission dialogs.
        permStatus.post { refreshPermStatus() }

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
    }

    // True while we've asked the system to switch the assistant role.
    private var assistantRequestPending = false

    private val runtimePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Status refreshes on resume.
        }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            findViewById<TextView>(R.id.settingsStatus).text =
                "Assistant role not available on this device."
            return
        }
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        assistantRequestPending = true
        // Fire-and-forget: some OEM permission dialogs don't return a proper
        // Activity result, so we verify on resume by querying the role holders.
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.permStatus)?.let { status ->
            val a11y = AeroAccessibilityService.isEnabled()
            val overlay = Settings.canDrawOverlays(this)
            val write = Settings.System.canWrite(this)
            val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            status.text = buildString {
                appendLine("Accessibility: " + if (a11y) "✓ ON" else "✗ OFF")
                appendLine("Overlay: " + if (overlay) "✓ ON" else "✗ OFF")
                appendLine("Modify settings: " + if (write) "✓ ON" else "✗ OFF")
                appendLine("Microphone: " + if (mic) "✓" else "✗")
                appendLine("Camera: " + if (cam) "✓" else "✗")
                appendLine("Contacts: " + if (contacts) "✓" else "✗")
                appendLine("Phone: " + if (phone) "✓" else "✗")
            }.trim()
        }
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
