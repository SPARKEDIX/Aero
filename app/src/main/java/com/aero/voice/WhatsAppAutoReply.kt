package com.aero.voice

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WhatsApp auto-reply brain.
 *
 * When enabled, incoming WhatsApp notifications are picked up (by the
 * accessibility service) and a reply is generated — asking the Gemini API for
 * a short reply (or a template if offline) — then sent automatically through
 * the accessibility automation pipeline.
 */
object WhatsAppAutoReply {

    private const val PREFS = "whatsapp_autoreply"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOW = "allowlist"
    private const val TAG = "AeroWhatsApp"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
    }

    fun allowlist(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ALLOW, emptySet()) ?: emptySet()

    fun addTarget(ctx: Context, name: String) {
        val s = allowlist(ctx).toMutableSet().apply { add(name.lowercase()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ALLOW, s).apply()
    }

    fun removeTarget(ctx: Context, name: String) {
        val s = allowlist(ctx).toMutableSet().apply { remove(name.lowercase()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ALLOW, s).apply()
    }

    /** Returns true if the notification text should trigger an auto reply. */
    fun shouldHandle(ctx: Context, notificationText: String): Boolean {
        if (!isEnabled(ctx)) return false
        val allow = allowlist(ctx)
        if (allow.isEmpty()) return true
        return allow.any { notificationText.lowercase().contains(it) }
    }

    /** Full flow: generate reply → send via accessibility. */
    fun handleIncoming(ctx: Context, notificationText: String) {
        val sender = extractSender(notificationText)
        val reply = generateReply(ctx, sender, notificationText)
        android.util.Log.d(TAG, "auto-reply to ${sender ?: "?"}: $reply")
        AeroAccessibilityService.requestAutoReply(sender ?: "", reply)
    }

    private fun extractSender(text: String): String? {
        // Common shapes: "Sender: message", "Sender · message", "Sender"
        val t = text.trim()
        val colon = t.indexOf(':')
        return when {
            colon in 1..40 -> t.substring(0, colon).trim().takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun generateReply(ctx: Context, sender: String?, message: String): String {
        val key = Prefs.apiKey(ctx)
        val fallback = "Namaste! Main abhi busy hoon, thodi der baad baat karta hoon."
        if (key.isNullOrBlank()) return fallback
        return try {
            val prompt =
                "You are Aero, replying on behalf of the phone owner in WhatsApp. " +
                    "Craft a short, natural reply (max 18 words) in Hinglish for this incoming " +
                    "message from ${sender ?: "someone"}: \"${message.take(200)}\". " +
                    "Reply with ONLY the message text."
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(Prefs.modelId(ctx), "UTF-8") +
                ":generateContent?key=" + URLEncoder.encode(key, "UTF-8")
            val payload = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            )
            val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val text = try {
                    val json = JSONObject(body)
                    json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                } catch (_: Exception) {
                    null
                }
                text?.trim()?.takeIf { it.isNotBlank() && it.length <= 200 } ?: fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
