package com.aero.voice

/**
 * Spoken dismiss intents. When the user says any of these (in Hinglish or
 * English), Aero understands they want it to leave / close the floating orb
 * and end the voice session.
 */
object DismissIntents {

    // A few robust, low false-positive patterns.
    private val PATTERNS = listOf(
        Regex("(?i)\\b(hey\\s+)?aero\\s+(tu|tum|aap|jaa|ja|by|bye|band|close)\\b"),
        Regex("(?i)\\b(tu|tum|aap)\\s+(jaa|ja|chale|nikal|band|close)\\b"),
        Regex("(?i)\\bjaa\\s+sakta\\b"),
        Regex("(?i)\\bjaa\\s+sakte\\b"),
        Regex("(?i)\\bgo\\s+away\\b"),
        Regex("(?i)\\bbye\\b"),
        Regex("(?i)\\bband\\s+kar\\b"),
        Regex("(?i)\\bclose\\b"),
        Regex("(?i)\\bchale\\s+jao\\b"),
        Regex("(?i)\\bnikal\\b")
    )

    fun matches(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }
}
