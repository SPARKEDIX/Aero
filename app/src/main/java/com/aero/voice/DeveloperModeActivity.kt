package com.aero.voice

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/** Developer Mode: clear AI memory + screen lock/unlock setup (Device Admin + saved credentials). */
class DeveloperModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer_mode)

        findViewById<TextView>(R.id.devBackButton).setOnClickListener { finish() }

        // ---- Memory ----
        refreshMemoryCount()
        findViewById<MaterialButton>(R.id.clearMemoryButton).setOnClickListener {
            val count = MemoryStore.all(this).size
            if (count == 0) {
                findViewById<TextView>(R.id.devStatus).text = "Memory pehle se khali hai."
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Memory clear?")
                .setMessage("Kya pakka $count yaad rakhe hue facts clear karne hain?")
                .setPositiveButton("Ha, clear") { _, _ ->
                    MemoryStore.clear(this)
                    refreshMemoryCount()
                    findViewById<TextView>(R.id.devStatus).text = "Aero ki memory clear ho gayi."
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ---- Device Admin ----
        refreshAdminStatus()
        findViewById<MaterialButton>(R.id.enableAdminButton).setOnClickListener {
            ScreenControl.openAdminSettings(this)
        }

        // ---- Saved credentials ----
        val pinInput = findViewById<EditText>(R.id.pinInput)
        val patternInput = findViewById<EditText>(R.id.patternInput)
        val saved = loadCredentials()
        pinInput.setText(saved.optString("pin"))
        patternInput.setText(saved.optString("pattern"))

        findViewById<MaterialButton>(R.id.savePinButton).setOnClickListener {
            val pin = pinInput.text.toString().trim()
            val pattern = patternInput.text.toString().trim()
            if (pin.isEmpty() && pattern.isEmpty()) {
                findViewById<TextView>(R.id.pinStatus).text = "Kuch to daalo — PIN ya pattern."
                return@setOnClickListener
            }
            saveCredentials(pin, pattern)
            findViewById<TextView>(R.id.pinStatus).text = "Saved. Aero ab isse janta hai (sirf isi phone par)."
        }

        // ---- Test buttons ----
        findViewById<MaterialButton>(R.id.testLockButton).setOnClickListener {
            findViewById<TextView>(R.id.devStatus).text = ScreenControl.lock(this)
        }
        findViewById<MaterialButton>(R.id.testUnlockButton).setOnClickListener {
            findViewById<TextView>(R.id.devStatus).text = ScreenControl.unlockFromActivity(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminStatus()
    }

    private fun refreshMemoryCount() {
        val count = MemoryStore.all(this).size
        findViewById<TextView>(R.id.memoryCount).text =
            "Facts yaad hain: $count"
    }

    private fun refreshAdminStatus() {
        val status = findViewById<TextView>(R.id.adminStatus)
        if (ScreenControl.isAdmin(this)) {
            status.text = "✓ Device Admin ACTIVE — lock/unlock ready hai."
        } else {
            status.text = "✗ Device Admin nahi hai — Enable dabao."
        }
    }

    private fun loadCredentials(): JSONObject {
        val prefs = getSharedPreferences("dev_pin", Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("pin", prefs.getString("pin", ""))
            put("pattern", prefs.getString("pattern", ""))
        }
    }

    private fun saveCredentials(pin: String, pattern: String) {
        getSharedPreferences("dev_pin", Context.MODE_PRIVATE).edit()
            .putString("pin", pin)
            .putString("pattern", pattern)
            .apply()
    }
}
