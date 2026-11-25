package com.aero.voice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Parses spoken reminder phrases into (message, fire time).
 * Supports Hinglish + English:
 *   "kal shaam 5 baje gym", "aaj 8:30 par meeting", "10 minute baad chai",
 *   "2 ghante baad call", "in 15 minutes", "2026-11-26 17:00 party".
 */
object ReminderParser {

    data class Parsed(
        val message: String,
        val atMillis: Long,
        val whenText: String
    )

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    private val NUMBER_WORDS = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4,
        "paanch" to 5, "panch" to 5, "chhe" to 6, "chhah" to 6, "che" to 6,
        "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyarah" to 11, "barah" to 12, "pandrah" to 15, "bees" to 20,
        "tees" to 30, "paitis" to 35, "chalis" to 40, "pachas" to 50,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.isEmpty()) return null
        isoParse(t)?.let { return it }
        relativeParse(t)?.let { return it }
        return absoluteParse(t)
    }

    /** Parse a bare time phrase (used by the model's set_reminder tool). */
    fun parseWhen(phrase: String): Long? = parse(phrase)?.atMillis

    fun format(ms: Long): String =
        SimpleDateFormat("EEE, d MMM h:mm a", Locale.getDefault()).format(Date(ms))

    // ---------------- relative: "10 minute baad" ----------------

    private fun relativeParse(text: String): Parsed? {
        val t = text.lowercase()

        // "aadhe ghante baad" = 30 min, "dedh ghante baad" = 90 min
        if (Regex("\\b(aadhe|adhe)\\s*(ghante|ghanta|hour)\\s*(baad|later)\\b").containsMatchIn(t)) {
            return build(text, 30 * MIN, "30 minutes baad")
        }
        if (Regex("\\b(dedh|dhedh)\\s*(ghante|ghanta|hour)\\s*(baad|later)\\b").containsMatchIn(t)) {
            return build(text, 90 * MIN, "1.5 ghante baad")
        }

        // in N minutes / N minute baad / N min later
        val minRe = Regex(
            "(?i)(?:\\bin\\s+|\\b)([0-9]{1,3}|[a-z]+)\\s*(?:minute|minutes|mins|min|minit)\\s*(?:baad|later|ke baad|mein|me)?\\b"
        )
        minRe.find(t)?.let { m ->
            val n = number(m.groupValues[1])
            if (n != null && n in 1..1440) {
                return build(text, n * MIN, "$n minute baad")
            }
        }

        // in N hours / N ghante baad
        val hrRe = Regex(
            "(?i)(?:\\bin\\s+|\\b)([0-9]{1,2}|[a-z]+)\\s*(?:hour|hours|hr|hrs|ghanta|ghante)\\s*(?:baad|later|ke baad|mein|me)?\\b"
        )
        hrRe.find(t)?.let { m ->
            val n = number(m.groupValues[1])
            if (n != null && n in 1..48) {
                return build(text, n * HOUR, "$n ghante baad")
            }
        }

        return null
    }

    // ---------------- absolute: "kal shaam 5 baje" ----------------

    private fun absoluteParse(text: String): Parsed? {
        val t = text.lowercase()

        var dayOffset = 0
        when {
            t.contains("day after tomorrow") -> dayOffset = 2
            t.contains("parso") || t.contains("parson") -> dayOffset = 2
            t.contains("tomorrow") -> dayOffset = 1
            t.contains("kal") -> dayOffset = 1
            t.contains("aaj") || t.contains("today") -> dayOffset = 0
        }

        val cal = Calendar.getInstance()

        // 1) explicit "5:30" or "at 5:30"
        val colon = Regex("\\b([0-9]{1,2}):([0-9]{2})\\b").find(t)
        // 2) "5 baje" / "5 pm" / "at 5" / "5 o'clock"
        val baje = Regex(
            "(?i)\\b([0-9]{1,2})\\s*(baje|bje|o'?clock|pm|am|p\\.m\\.|a\\.m\\.)\\b"
        ).find(t)
        val atOnly = Regex("(?i)\\bat\\s+([0-9]{1,2})\\b").find(t)

        var hour: Int
        var minute = 0
        var hasAmPm: String? = null
        val matchedText: String

        if (colon != null) {
            hour = colon.groupValues[1].toIntOrNull() ?: return null
            minute = colon.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
            matchedText = colon.value
        } else if (baje != null) {
            hour = baje.groupValues[1].toIntOrNull() ?: return null
            val suffix = baje.groupValues[2].lowercase()
            hasAmPm = when (suffix) {
                "pm", "p.m." -> "pm"
                "am", "a.m." -> "am"
                else -> null
            }
            matchedText = baje.value
        } else if (atOnly != null) {
            hour = atOnly.groupValues[1].toIntOrNull() ?: return null
            matchedText = atOnly.value
        } else {
            return null
        }

        if (hour !in 0..23) return null

        // Day-part hints
        val dayPart = when {
            Regex("\\b(subah|savere|saver|morning)\\b").containsMatchIn(t) -> "am"
            Regex("\\b(dopahar|dupahar|afternoon)\\b").containsMatchIn(t) -> "pm"
            Regex("\\b(shaam|sham|evening)\\b").containsMatchIn(t) -> "pm-evening"
            Regex("\\b(raat|night)\\b").containsMatchIn(t) -> "pm-night"
            else -> null
        }

        when {
            hasAmPm == "pm" && hour < 12 -> hour += 12
            hasAmPm == "am" && hour == 12 -> hour = 0
            dayPart == "am" && hour == 12 -> hour = 0
            dayPart == "pm" && hour < 12 -> hour += 12
            dayPart == "pm-evening" && hour < 12 -> hour += 12
            dayPart == "pm-night" && hour in 8..11 -> hour += 12
        }

        // No hint: "5 baje" said in the afternoon usually means 5 PM.
        if (hasAmPm == null && dayPart == null) {
            val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour in 1..7 && nowHour >= 10) hour += 12
        }

        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If the time already passed today, assume tomorrow.
        if (dayOffset == 0 && cal.timeInMillis <= System.currentTimeMillis() + 15_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val whenText = format(cal.timeInMillis)
        return Parsed(cleanMessage(text, matchedText), cal.timeInMillis, whenText)
    }

    // ---------------- ISO: "2026-11-26 17:00" ----------------

    private fun isoParse(text: String): Parsed? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})").find(text) ?: return null
        return try {
            val cal = Calendar.getInstance().apply {
                set(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt() - 1,
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(),
                    0
                )
                set(Calendar.MILLISECOND, 0)
            }
            Parsed(cleanMessage(text, m.value), cal.timeInMillis, format(cal.timeInMillis))
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- helpers ----------------

    private fun build(text: String, delayMillis: Long, whenText: String): Parsed {
        val at = System.currentTimeMillis() + delayMillis
        return Parsed(cleanMessage(text, null), at, whenText)
    }

    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return NUMBER_WORDS[raw.lowercase()]
    }

    internal fun parseNumber(raw: String): Int? = number(raw)

    private fun cleanMessage(text: String, matchedTime: String?): String {
        var s = " " + text.lowercase() + " "
        matchedTime?.let { s = s.replace(it.lowercase(), " ") }
        val strip = listOf(
            """\b(remind me to|remind me|set a reminder|set reminder|reminder)\b""",
            """\b(yaad karao|yaad kara|yaad dilana|yaad dilao|yaad rakhna|yaad rakho|yaad)\b""",
            """\b(in|after)\s+[0-9a-z]+\s*(minute|minutes|mins|min|hour|hours|hr|ghanta|ghante)\b""",
            """\b[0-9a-z]+\s*(minute|minutes|mins|min|hour|hours|hr|ghanta|ghante)\s*(baad|later)\b""",
            """\b(aadhe|adhe|dedh|dhedh)\s*(ghante|ghanta|hour)\s*(baad|later)\b""",
            """\b(kal|parso|parson|aaj|tomorrow|today|day after tomorrow)\b""",
            """\b(subah|savere|saver|morning|dopahar|dupahar|afternoon|shaam|sham|evening|raat|night)\b""",
            """\b(baje|bje|o'?clock|pm|am|p\.m\.|a\.m\.)\b"""
        )
        strip.forEach { s = Regex("(?i)$it").replace(s, " ") }
        s = Regex("(?i)\\s+").replace(s, " ").trim()
        s = Regex("(?i)^(ki|ke|ka|ko|that|to|par|pe|at|for|mein|me)\\s+").replace(s, "").trim()
        s = Regex("(?i)\\s+(ki|ke|ka|ko|hai|h)\$").replace(s, "").trim()
        s = s.trim(' ', ',', '.', '-', ':')
        return s.ifEmpty { "Reminder" }
    }
}
