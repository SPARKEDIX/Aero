package com.aero.voice

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * First-launch onboarding:
 *  0. Typewriter welcome → auto-advance
 *  1. API key input + Google AI Studio shortcut + live test (red zigzag on fail)
 *  2. Ask name → 3. hobbies → enter the app
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private val handler = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already onboarded → straight to the app.
        if (Prefs.onboarded(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_onboarding)
        flipper = findViewById(R.id.flipper)

        // Prefill existing key if they already had one.
        Prefs.apiKey(this)?.takeIf { it.isNotBlank() }?.let {
            findViewById<EditText>(R.id.apiInput).setText(it)
        }

        startTypewriter()

        findViewById<MaterialButton>(R.id.openAIStudioBtn).setOnClickListener {
            openAIStudio()
        }
        findViewById<MaterialButton>(R.id.testApiBtn).setOnClickListener {
            testApiKey()
        }
        findViewById<MaterialButton>(R.id.nextNameBtn).setOnClickListener { onNameNext() }
        findViewById<MaterialButton>(R.id.skipNameBtn).setOnClickListener { flipper.showNext() }
        findViewById<MaterialButton>(R.id.finishBtn).setOnClickListener { onFinish() }
        findViewById<MaterialButton>(R.id.skipHobbiesBtn).setOnClickListener { onFinish() }
    }

    // ---------------- step 0: typewriter ----------------

    private fun startTypewriter() {
        val tv = findViewById<TextView>(R.id.typingText)
        val full = "Welcome to Aero ✨"
        val timer = object : Runnable {
            var i = 0
            override fun run() {
                i++
                tv.text = full.take(i)
                if (i < full.length) {
                    handler.postDelayed(this, 45L)
                } else {
                    handler.postDelayed({
                        flipper.showNext()
                    }, 1100L)
                }
            }
        }
        handler.post(timer)
    }

    // ---------------- step 1: API key ----------------

    private fun openAIStudio() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
            )
        } catch (_: Exception) {
        }
    }

    private fun testApiKey() {
        val input = findViewById<EditText>(R.id.apiInput)
        val key = input.text.toString().trim()
        if (key.isEmpty()) {
            failEntry("Pehle API key daalo — upar wale button se free key banao.")
            return
        }
        val status = findViewById<TextView>(R.id.apiStatus)
        val btn = findViewById<MaterialButton>(R.id.testApiBtn)
        btn.isEnabled = false
        status.setTextColor(resources.getColor(R.color.text_secondary, null))
        status.text = "Checking… ek second"

        Thread {
            val ok = try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    URLEncoder.encode(Prefs.modelId(this), "UTF-8") +
                    ":generateContent?key=" + URLEncoder.encode(key, "UTF-8")
                val payload = """{"contents":[{"parts":[{"text":"ping"}]}]}"""
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    resp.isSuccessful && body.contains("candidates")
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                btn.isEnabled = true
                if (ok) {
                    Prefs.save(this, key, Prefs.voice(this))
                    status.setTextColor(resources.getColor(R.color.accent, null))
                    status.text = "✅ API key sahi hai! Shuru karte hain…"
                    handler.postDelayed({ flipper.showNext() }, 700L)
                } else {
                    failEntry("API key kaam nahi kar rahi. Dobara check karo ya nayi key banao.")
                }
            }
        }.start()
    }

    private fun failEntry(message: String) {
        val status = findViewById<TextView>(R.id.apiStatus)
        status.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
        status.text = message

        val zig = findViewById<ZigzagView>(R.id.zigzag)
        zig.visibility = View.VISIBLE
        zig.startZigzag()
        handler.postDelayed({ zig.stopZigzag() }, 4000L)

        val input = findViewById<EditText>(R.id.apiInput)
        ObjectAnimator.ofFloat(input, "translationX", 0f, -14f, 14f, -10f, 10f, -6f, 6f, 0f)
            .setDuration(500L).start()
    }

    // ---------------- profile steps ----------------

    private fun onNameNext() {
        val name = findViewById<EditText>(R.id.nameInput).text.toString().trim()
        if (name.isNotEmpty()) {
            Prefs.setUserName(this, name)
            MemoryStore.add(this, "Mera naam $name hai.")
        }
        flipper.showNext()
    }

    private fun onFinish() {
        val name = Prefs.userName(this)
        val hobbies = findViewById<EditText>(R.id.hobbiesInput).text.toString().trim()
        if (hobbies.isNotEmpty()) {
            Prefs.setUserHobbies(this, hobbies)
            if (name.isNotEmpty()) {
                MemoryStore.add(this, "$name ko $hobbies pasand hain.")
            } else {
                MemoryStore.add(this, "Mujhe $hobbies pasand hain.")
            }
        }
        Prefs.setOnboarded(this, true)
        goToMain()
    }

    private fun goToMain() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        } catch (_: Exception) {
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
