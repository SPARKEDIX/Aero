package com.aero.voice

import java.util.Calendar

/**
 * Parses spoken reminder phrases into (message, fire time).
 * Supports Hinglish + English day/time patterns.
 */
object ReminderParser {

    data class Parsed(val message: String, val atMillis: Long)

    fun parse(text: String): Parsed? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null

        val cal = Calendar.getInstance()
        var dayOffset = 0

        // Day word
        when {
            t.contains("parso") -> dayOffset = 2
            t.contains("kal") -> dayOffset = 1
            t.contains("aaj") || t.contains("aaj") || t.contains("today") -> dayOffset = 0
            t.contains("tomorrow") -> dayOffset = 1
            t.contains("day after tomorrow") -> dayOffset = 2
        }

        // Time: "5 baje" / "5:30" / "8 baje" / "5 pm"
        val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(baje|pm|am)?")
            .find(t) ?: return null

        var hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
        val suffix = timeMatch.groupValues[3].trim()

        // 12-hour with pm/am
        if (suffix == "pm" && hour < 12) hour += 12
        if (suffix == "am" && hour == 12) hour = 0

        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If time already passed today, assume tomorrow.
        if (dayOffset == 0 && cal.timeInMillis < System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Message = everything before the time phrase, cleaned.
        val msg = sanitize(t.replace(timeMatch.value, "").trim())
        return Parsed(msg.ifEmpty { "You have a reminder." }, cal.timeInMillis)
    }

    private fun sanitize(s: String): String {
        return s
            .replace(Regex("\\b(yaad kara|yaad kara dio|reminder set|remind me|yaad dilana|yaad)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
