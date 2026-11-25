package com.aero.voice

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Developer Mode: clear AI memory + screen lock (Device Admin only). */
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

        // ---- Device Admin (lock only) ----
        refreshAdminStatus()
        findViewById<MaterialButton>(R.id.enableAdminButton).setOnClickListener {
            ScreenControl.openAdminSettings(this)
        }
        findViewById<MaterialButton>(R.id.testLockButton).setOnClickListener {
            findViewById<TextView>(R.id.devStatus).text = ScreenControl.lock(this)
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
        status.text = if (ScreenControl.isAdmin(this)) {
            "✓ Device Admin ACTIVE — lock ready hai."
        } else {
            "✗ Device Admin nahi hai — Enable dabao."
        }
    }
}
