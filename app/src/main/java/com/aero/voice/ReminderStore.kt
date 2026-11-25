package com.aero.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local on-device reminder store (no server). Lightweight JSON in
 * SharedPreferences — safe for hundreds of reminders.
 */
object ReminderStore {

    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"

    data class Reminder(
        val id: Long,
        val message: String,
        val atMillis: Long,
        val retryCount: Int = 0
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    o.getLong("id"),
                    o.getString("message"),
                    o.getLong("at"),
                    o.optInt("retry", 0)
                )
            }.sortedBy { it.atMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, message: String, atMillis: Long): Reminder {
        val id = System.currentTimeMillis()
        val r = Reminder(id, message, atMillis)
        val list = all(ctx).toMutableList().apply { add(r) }
        save(ctx, list)
        return r
    }

    fun update(ctx: Context, r: Reminder) {
        val list = all(ctx).map { if (it.id == r.id) r else it }
        save(ctx, list)
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("message", r.message)
                    .put("at", r.atMillis)
                    .put("retry", r.retryCount)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
