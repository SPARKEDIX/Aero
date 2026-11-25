package com.aero.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the final system instruction for every live session:
 * base mood prompt + current date/time + long-term memory + tool rules.
 */
object AeroPrompt {

    private val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault())

    private const val TOOL_RULES = """
TOOLS YOU MUST USE (never refuse these):
1. Remember: when the user says "yaad rakho", "yaad rakhna", "remember that", "note karo" or shares a personal fact (name, birthday, city, preference, important date), call `remember_fact`. Confirm briefly in their language.
2. Reminders: when the user says "X baje yaad karao", "remind me", "reminder laga do", "kal 5 baje", call `set_reminder` with the exact time phrase the user spoke (e.g. "kal shaam 5 baje") and a short message. NEVER say reminders are not supported. The phone will ring like a real call.
3. Calls: when the user says "mujhe call karo", "call me", "phone karo mujhe" or wants Aero to call them later, call `call_me` (delay_minutes = 0 for now).
4. Forgetting: when the user says "bhool jao", "forget", call `forget_fact`.
5. If the user asks what you remember, call `recall_memory` and answer.

LANGUAGE (STRICT): Always reply in Hindi or Hinglish (Roman Hindi). Never answer in English-only, even if the user speaks English. Technical words may stay English.
Current date/time: %s
Keep replies short and natural for voice.
    """

    fun build(ctx: Context, baseInstruction: String): String {
        val now = dateFmt.format(Date())
        val memory = MemoryStore.promptBlock(ctx)
        return buildString {
            append(baseInstruction)
            append("\n\n")
            append(TOOL_RULES.format(now))
            if (memory.isNotEmpty()) {
                append("\n\n")
                append(memory)
            }
        }
    }

    /** Small clock block for mid-session context updates. */
    fun nowLine(): String = "Current date/time: " + dateFmt.format(Date())
}
