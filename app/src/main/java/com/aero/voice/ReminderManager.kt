package com.aero.voice

import android.content.Context
import android.util.Log

/**
 * Shared reminder-management logic used by both the voice-command parser and
 * the model's `manage_reminders` tool.
 */
object ReminderManager {

    private const val TAG = "AeroReminder"

    fun list(ctx: Context): String {
        val all = ReminderStore.all(ctx).filter { it.atMillis > System.currentTimeMillis() }
        if (all.isEmpty()) return "Filhaal koi reminder set nahi hai."
        val fmt = java.text.SimpleDateFormat("EEE, d MMM h:mm a", java.util.Locale.getDefault())
        return buildString {
            append("Tumhare ${all.size} reminders hain:\n")
            all.forEachIndexed { i, r ->
                append("${i + 1}. ${r.message} — ${fmt.format(r.atMillis)}\n")
            }
            append("Bol sakte ho: \"reminder cancel karo\", \"10 minute delay karo\" ya \"abhi karo\".")
        }
    }

    /** Cancels every reminder whose text matches [query] (empty = all). */
    fun cancel(ctx: Context, query: String): String {
        val matches = matches(ctx, query)
        if (matches.isEmpty()) return noMatch(query, "cancel")
        matches.forEach { r ->
            ReminderScheduler.cancel(ctx, r.id)
            ReminderStore.remove(ctx, r.id)
        }
        return "Cancel kar diya: ${matches.joinToString(", ") { it.message }}"
    }

    /** Pushes matched reminders later by [minutes]. */
    fun delay(ctx: Context, query: String, minutes: Int): String {
        val matches = matches(ctx, query)
        if (matches.isEmpty()) return noMatch(query, "delay")
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        return buildString {
            matches.forEach { r ->
                val next = r.copy(atMillis = r.atMillis + minutes * 60_000L)
                ReminderStore.update(ctx, next)
                ReminderScheduler.schedule(ctx, next)
                append("${r.message} → ${fmt.format(next.atMillis)} (${minutes} min baad)\n")
            }
            append("Delay ho gaya.")
        }
    }

    /** Moves matched reminders earlier by [minutes] (never before now). */
    fun earlier(ctx: Context, query: String, minutes: Int): String {
        val matches = matches(ctx, query)
        if (matches.isEmpty()) return noMatch(query, "jldi")
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val minAt = System.currentTimeMillis() + 30_000L
        return buildString {
            matches.forEach { r ->
                val nextAt = (r.atMillis - minutes * 60_000L).coerceAtLeast(minAt)
                val next = r.copy(atMillis = nextAt)
                ReminderStore.update(ctx, next)
                ReminderScheduler.schedule(ctx, next)
                append("${r.message} → ${fmt.format(nextAt)} (${minutes} min jldi)\n")
            }
            append("Ho gaya.")
        }
    }

    /** Fires matched reminders immediately (ringing call now). */
    fun fireNow(ctx: Context, query: String): String {
        val matches = matches(ctx, query)
        if (matches.isEmpty()) return noMatch(query, "abhi")
        matches.forEach { r ->
            ReminderScheduler.cancel(ctx, r.id)
            ReminderStore.remove(ctx, r.id)
            ReminderScheduler.ringNow(ctx, r.message)
        }
        return "Abhi ring ho raha hai: ${matches.joinToString(", ") { it.message }}"
    }

    fun clearAll(ctx: Context): String {
        val all = ReminderStore.all(ctx)
        if (all.isEmpty()) return "Pehle se koi reminder nahi hai."
        all.forEach { ReminderScheduler.cancel(ctx, it.id) }
        ReminderStore.clear(ctx)
        return "Saare ${all.size} reminders clear kar diye."
    }

    /** True if any reminder text contains [query]. */
    fun exists(ctx: Context, query: String): Boolean = matches(ctx, query).isNotEmpty()

    private fun matches(ctx: Context, query: String): List<ReminderStore.Reminder> {
        val q = query.trim().lowercase()
        val all = ReminderStore.all(ctx)
        if (q.isEmpty()) return all
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }
        return all.filter { r ->
            val m = r.message.lowercase()
            words.all { m.contains(it) }
        }
    }

    private fun noMatch(query: String, action: String): String =
        if (query.isBlank()) "Koi reminder nahi mila $action karne ke liye."
        else "\"$query\" se match karta koi reminder nahi mila."
}
