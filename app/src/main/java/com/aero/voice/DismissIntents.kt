package com.aero.voice

/**
 * Spoken dismiss intents. When the user says any of these (in Hinglish or
 * English), Aero understands THEY WANT AERO ITSELF to leave / close the
 * floating orb and end the voice session.
 *
 * These are intentionally Aero-specific: plain "band kar" / "close" must NOT
 * dismiss Aero (that would conflict with "app band karo" = close an app).
 */
object DismissIntents {

    private val PATTERNS = listOf(
        // "aero band", "aero close", "aero jaa", "aero bye" …
        Regex("(?i)\\b(hey\\s+)?(aero|jarvis)\\s+(tu|tum|aap|jaa|ja|by|bye|band|close|chale|nikal)\\b"),
        // "tu jaa" / "tum jaa" / "aap jaa" (directly telling Aero to leave)
        Regex("(?i)\\b(tu|tum|aap)\\s+(jaa|ja|chale|nikal)\\b"),
        // "jaa sakta hai", "jaa sakte hai", "go away"
        Regex("(?i)\\bjaa\\s+sakta\\b"),
        Regex("(?i)\\bjaa\\s+sakte\\b"),
        Regex("(?i)\\bgo\\s+away\\b"),
        // standalone soft exits
        Regex("(?i)\\bbye\\b"),
        Regex("(?i)\\bchale\\s+jao\\b"),
        Regex("(?i)\\bnikal\\b")
    )

    fun matches(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }
}
