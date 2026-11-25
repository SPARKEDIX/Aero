package com.aero.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Dedicated screen for every special permission/access Aero needs.
 * One tap per item — either a runtime popup here, or the exact system
 * settings page for things Android only allows from Settings.
 */
class AccessibilityPermissionsActivity : AppCompatActivity() {

    private val runtimePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_permissions)

        findViewById<TextView>(R.id.permBackButton).setOnClickListener { finish() }

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
            try {
                startActivity(
                    Intent(this, ScreenShareConsentActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
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
        findViewById<TextView>(R.id.permStatus).text = buildString {
            appendLine("Accessibility: " + if (a11y) "✓ ON" else "✗ OFF")
            appendLine("Overlay: " + if (overlay) "✓ ON" else "✗ OFF")
            appendLine("Modify settings: " + if (write) "✓ ON" else "✗ OFF")
            appendLine("Microphone: " + if (mic) "✓" else "✗")
            appendLine("Camera: " + if (cam) "✓" else "✗")
            appendLine("Contacts: " + if (contacts) "✓" else "✗")
            appendLine("Phone: " + if (phone) "✓" else "✗")
        }.trim()
    }
}
