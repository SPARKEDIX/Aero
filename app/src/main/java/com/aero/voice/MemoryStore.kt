package com.aero.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term memory stored only on this phone (SharedPreferences JSON).
 * Facts are injected into the model's system instruction on every new session,
 * so Aero actually remembers the user across app restarts.
 */
object MemoryStore {

    private const val PREFS = "aero_memory"
    private const val KEY_FACTS = "facts"

    data class Fact(
        val id: Long,
        val text: String,
        val createdAt: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Fact> {
        val raw = prefs(ctx).getString(KEY_FACTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isEmpty()) null
                else Fact(o.optLong("id"), text, o.optLong("created"))
            }.sortedBy { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Adds a fact; skips exact (case-insensitive) duplicates. */
    fun add(ctx: Context, text: String): Fact {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val existing = all(ctx).firstOrNull { it.text.equals(clean, ignoreCase = true) }
        if (existing != null) return existing
        val fact = Fact(System.currentTimeMillis(), clean, System.currentTimeMillis())
        save(ctx, all(ctx) + fact)
        return fact
    }

    /** Removes facts containing [query] (case-insensitive). Returns removed count. */
    fun forget(ctx: Context, query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val current = all(ctx)
        val kept = current.filterNot { it.text.contains(q, ignoreCase = true) }
        val removed = current.size - kept.size
        if (removed > 0) save(ctx, kept)
        return removed
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<Fact>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("text", f.text)
                    .put("created", f.createdAt)
            )
        }
        prefs(ctx).edit().putString(KEY_FACTS, arr.toString()).apply()
    }

    /** Text block injected into the system instruction ("" when empty). */
    fun promptBlock(ctx: Context): String {
        val facts = all(ctx)
        if (facts.isEmpty()) return ""
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val lines = facts.joinToString("\n") { "- ${it.text} (saved ${fmt.format(Date(it.createdAt))})" }
        return buildString {
            appendLine("=== LONG-TERM MEMORY (things the user asked you to remember) ===")
            appendLine(lines)
            appendLine("Use these facts naturally. Never say you forgot them. If the user asks")
            appendLine("\"what do you remember\", list them briefly.")
            append("================================================================")
        }
    }

    /** Short human-readable summary for voice confirmations. */
    fun summary(ctx: Context): String {
        val facts = all(ctx)
        if (facts.isEmpty()) return "Abhi main tumhare baare mein kuch yaad nahi rakhta."
        return "Mujhe yaad hai:\n" + facts.joinToString("\n") { "• ${it.text}" }
    }
}
