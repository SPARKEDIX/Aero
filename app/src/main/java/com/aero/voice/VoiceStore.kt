package com.aero.voice

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Stores enrolled voiceprints on-device (JSON in SharedPreferences).
 */
object VoiceStore {

    private const val PREFS = "voice_prints"
    private const val KEY_LIST = "list"

    data class Voice(
        val id: String,
        val name: String,
        val role: String,
        val profile: FloatArray,
        val createdAt: Long
    ) {
        val label: String get() = if (role.isBlank()) name else "$name ($role)"
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Voice> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val profile = decodeProfile(o.optString("sig"))
                if (profile == null) null
                else Voice(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("role"),
                    profile,
                    o.optLong("created")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, name: String, role: String, profile: FloatArray): Voice {
        val v = Voice(
            System.currentTimeMillis().toString(),
            name.trim().ifBlank { "Unknown" },
            role.trim(),
            profile,
            System.currentTimeMillis()
        )
        save(ctx, all(ctx) + v)
        return v
    }

    fun remove(ctx: Context, id: String): Boolean {
        val before = all(ctx).size
        save(ctx, all(ctx).filter { it.id != id })
        return before != all(ctx).size
    }

    private fun save(ctx: Context, list: List<Voice>) {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(
                JSONObject()
                    .put("id", v.id)
                    .put("name", v.name)
                    .put("role", v.role)
                    .put("sig", encodeProfile(v.profile))
                    .put("created", v.createdAt)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun encodeProfile(p: FloatArray): String {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            dos.writeInt(p.size)
            p.forEach { dos.writeFloat(it) }
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeProfile(s: String): FloatArray? {
        return try {
            val bytes = Base64.decode(s, Base64.DEFAULT)
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            val n = dis.readInt()
            if (n <= 0 || n > 64) return null
            FloatArray(n) { dis.readFloat() }
        } catch (_: Exception) {
            null
        }
    }

    /** Best match for a live profile; returns (voice, score) or null if none. */
    fun identify(ctx: Context, profile: FloatArray, threshold: Float = VoiceFingerprint.DEFAULT_THRESHOLD): Pair<Voice, Float>? {
        var best: Voice? = null
        var bestScore = 0f
        all(ctx).forEach { v ->
            val s = VoiceFingerprint.similarity(profile, v.profile)
            if (s > bestScore) {
                bestScore = s
                best = v
            }
        }
        val b = best ?: return null
        return if (bestScore >= threshold) b to bestScore else null
    }
}
