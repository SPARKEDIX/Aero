package com.aero.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Autonomous screen agent: looks at the current screen (via accessibility UI
 * dump + optional 2s screen feed), decides the next action with the model, and
 * executes taps/types/navigation until the user's goal is done.
 *
 * No screenshot is ever saved — the video frames live only in memory.
 */
object ScreenAgent {

    private const val TAG = "AeroAgent"
    private const val MAX_STEPS = 14
    private const val STEP_DELAY_MS = 1400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    /** Starts the agent for [goal]. Returns immediately with a status string. */
    fun start(ctx: Context, goal: String): String {
        if (running) return "Agent pehle se chal raha hai."
        if (goal.isBlank()) return "Goal batao — kya karna hai?"
        if (!AeroAccessibilityService.isEnabled()) {
            ScreenShareHelper.openAccessibilitySettings(ctx)
            return "Pehle Accessibility permission ON karo (khul raha hai)."
        }
        // Ensure screen vision is available (consent once; frames are in-memory only).
        ScreenShareHelper.ensureScreenShare(ctx)

        running = true
        scope.launch {
            try {
                val result = execute(ctx, goal)
                Log.d(TAG, "agent done: $result")
            } finally {
                running = false
            }
        }
        return "Agent shuru: \"$goal\" — screen dekh raha hoon aur kaam kar raha hoon."
    }

    private suspend fun execute(ctx: Context, goal: String) {
        var step = 0
        while (step < MAX_STEPS) {
            step++
            val dump = uiDump(ctx)
            if (dump.contains("(accessibility not enabled)")) break
            if (dump.isBlank() || dump == "(no window)") {
                delay(1200)
                continue
            }

            val action = askModel(ctx, goal, dump, step)

            when {
                action == null -> {
                    log("model failed, stopping")
                    speak(ctx, "Agent ruk gaya — screen samajh nahi paya.")
                    return
                }
                action.action == "done" -> {
                    log("goal complete: ${action.target}")
                    speak(ctx, "Kaam ho gaya: ${action.target.ifBlank { goal }}")
                    return
                }
                action.action == "fail" -> {
                    speak(ctx, "Agent kaam nahi kar paya: ${action.target}")
                    return
                }
                else -> {
                    val executed = executeAction(ctx, action)
                    log("step $step: ${action.action}(${action.target}) -> $executed")
                    delay(STEP_DELAY_MS)
                }
            }
        }
        speak(ctx, "Max steps ho gaye. Goal poora ho gaya ya rukna pada.")
    }

    private data class Action(val action: String, val target: String)

    private fun askModel(ctx: Context, goal: String, dump: String, step: Int): Action? {
        val key = Prefs.apiKey(ctx) ?: return null
        return try {
            val prompt = buildString {
                appendLine("You are Aero's phone-automation agent.")
                appendLine("Goal: $goal")
                appendLine("Current screen (accessibility text dump):")
                appendLine(dump.take(1800))
                appendLine()
                appendLine("Decide the NEXT single action to progress toward the goal.")
                appendLine("Reply with ONLY one JSON object, one of:")
                appendLine("{\"action\":\"tap\",\"target\":\"<exact visible text/button>\"}")
                appendLine("{\"action\":\"type\",\"target\":\"<text to type>\"}")
                appendLine("{\"action\":\"scroll\",\"target\":\"down\"} or \"up\"")
                appendLine("{\"action\":\"back\"} {\"action\":\"home\"}")
                appendLine("{\"action\":\"open\",\"target\":\"<app name>\"}")
                appendLine("{\"action\":\"done\",\"target\":\"<summary>\"} when the goal is achieved")
                appendLine("{\"action\":\"fail\",\"target\":\"<reason>\"} if impossible")
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(Prefs.modelId(ctx), "UTF-8") +
                ":generateContent?key=" + URLEncoder.encode(key, "UTF-8")
            val payload = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            )
            val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@use null
                val text = try {
                    JSONObject(body).optJSONArray("candidates")
                        ?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text")
                } catch (_: Exception) {
                    null
                } ?: return@use null
                parseAction(text)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "agent model call failed", t)
            null
        }
    }

    private fun parseAction(text: String): Action? {
        val t = text.trim()
        val json = try {
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(t.substring(start, end + 1)) else null
        } catch (_: Exception) {
            null
        }
        if (json != null) {
            val a = json.optString("action").lowercase()
            if (a.isNotEmpty()) return Action(a, json.optString("target"))
        }
        // Fallback: natural language
        return when {
            Regex("(?i)\\b(done|complete|finished|ho gaya)\\b").containsMatchIn(t) ->
                Action("done", t)
            Regex("(?i)\\b(fail|cannot|can't|impossible)\\b").containsMatchIn(t) ->
                Action("fail", t)
            Regex("(?i)\\b(scroll down|neeche)\\b").containsMatchIn(t) ->
                Action("scroll", "down")
            Regex("(?i)\\b(scroll up|upar)\\b").containsMatchIn(t) ->
                Action("scroll", "up")
            Regex("(?i)\\b(back|wapas)\\b").containsMatchIn(t) ->
                Action("back", "")
            Regex("(?i)\\b(home|home screen)\\b").containsMatchIn(t) ->
                Action("home", "")
            else -> null
        }
    }

    private fun executeAction(ctx: Context, a: Action): Boolean = when (a.action) {
        "tap" -> AeroAccessibilityService.requestClickByText(a.target)
        "type" -> AeroAccessibilityService.requestTypeText(a.target)
        "scroll" -> AeroAccessibilityService.requestScroll(a.target == "down")
        "back" -> AeroAccessibilityService.requestBack()
        "home" -> AeroAccessibilityService.requestHome()
        "open" -> {
            AppControl.openApp(ctx, a.target)
            true
        }
        else -> false
    }

    private fun uiDump(ctx: Context): String {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        AeroAccessibilityService.requestDump { dump ->
            holder[0] = dump
            latch.countDown()
        }
        return if (latch.await(1500, TimeUnit.MILLISECONDS)) holder[0] ?: "" else ""
    }

    private fun speak(ctx: Context, text: String) {
        val live = AeroState.liveClient
        if (live != null) {
            live.sendContextText("[Task result] $text")
        } else {
            Log.d(TAG, "speak: $text")
        }
    }

    private fun log(msg: String) = Log.d(TAG, msg)
}
