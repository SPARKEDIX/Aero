package com.aero.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Handles memory / reminder / call-me tools. Shared by the in-app session and
 * the background (assistant-invoked) session so behaviour is identical.
 */
object AeroTools {

    private const val TAG = "AeroTools"

    fun handle(ctx: Context, call: ToolCall): ToolResponse? = when (call.name) {
        "remember_fact" -> {
            val fact = call.args.optString("fact").trim()
            if (fact.isEmpty()) {
                ToolResponse(call.id, call.name, JSONObject().put("error", "empty fact"))
            } else {
                MemoryStore.add(ctx, fact)
                Log.d(TAG, "remembered: $fact")
                ToolResponse(
                    call.id, call.name,
                    JSONObject().put("status", "Saved to long-term memory: $fact")
                )
            }
        }

        "forget_fact" -> {
            val query = call.args.optString("query").trim()
            val removed = MemoryStore.forget(ctx, query)
            ToolResponse(
                call.id, call.name,
                JSONObject().put(
                    "status",
                    if (removed > 0) "Forgot $removed memory item(s)." else "No matching memory found."
                )
            )
        }

        "recall_memory" -> ToolResponse(
            call.id, call.name,
            JSONObject().put("memory", MemoryStore.promptBlock(ctx).ifEmpty { "No saved memories yet." })
        )

        "get_current_time" -> ToolResponse(
            call.id, call.name,
            JSONObject()
                .put("time", TimeUtil.nowIso())
                .put("timezone", TimeUtil.tzId())
                .put("human", TimeUtil.nowHuman())
        )

        "set_reminder" -> {
            val whenText = call.args.optString("when").trim()
            val message = call.args.optString("message").trim().ifBlank { "Reminder" }
            val at = ReminderParser.parse(whenText)?.atMillis
                ?: ReminderParser.parse("$message $whenText")?.atMillis

            if (at == null) {
                ToolResponse(
                    call.id, call.name,
                    JSONObject()
                        .put("error", "could not understand the time")
                        .put(
                            "hint",
                            "Ask the user for a clear time like 'kal shaam 5 baje' or '10 minute baad'."
                        )
                )
            } else {
                val r = ReminderStore.add(ctx, message, at)
                ReminderScheduler.schedule(ctx, r)
                Log.d(TAG, "reminder scheduled ${r.id} at $at")
                ToolResponse(
                    call.id, call.name,
                    JSONObject().put(
                        "status",
                        "Reminder set for ${ReminderParser.format(at)}: $message. " +
                            "The phone will ring like a call at that time."
                    )
                )
            }
        }

        "call_me" -> {
            val delay = call.args.optInt("delay_minutes", 0)
            val message = call.args.optString("message").trim().ifBlank { "Aero call" }
            if (delay <= 0) {
                ReminderScheduler.ringNow(ctx, message)
                ToolResponse(
                    call.id, call.name,
                    JSONObject().put("status", "Calling the user now. The call screen is ringing.")
                )
            } else {
                val at = System.currentTimeMillis() + delay * 60_000L
                val r = ReminderStore.add(ctx, message, at)
                ReminderScheduler.schedule(ctx, r)
                ToolResponse(
                    call.id, call.name,
                    JSONObject().put(
                        "status",
                        "Aero will call in $delay minute(s), at ${ReminderParser.format(at)}."
                    )
                )
            }
        }

        else -> null
    }
}
