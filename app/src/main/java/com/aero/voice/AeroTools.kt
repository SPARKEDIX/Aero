package com.aero.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles memory / reminder / call-me tools. Shared by the in-app session and
 * the background (assistant-invoked) session so behaviour is identical.
 */
object AeroTools {

    private const val TAG = "AeroTools"

    /** Blocking accessibility UI dump (used by look_at_screen). */
    private fun uiDumpNow(ctx: Context): String {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        AeroAccessibilityService.requestDump { dump ->
            holder[0] = dump
            latch.countDown()
        }
        return if (latch.await(1200, TimeUnit.MILLISECONDS)) {
            holder[0]?.take(400) ?: ""
        } else {
            ""
        }
    }

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

        "manage_reminders" -> {
            val action = call.args.optString("action").trim().lowercase()
            val query = call.args.optString("query").trim()
            val minutes = call.args.optInt("minutes", 10).coerceIn(1, 1440)
            val status = when (action) {
                "list" -> ReminderManager.list(ctx)
                "cancel" -> ReminderManager.cancel(ctx, query)
                "delay" -> ReminderManager.delay(ctx, query, minutes)
                "earlier" -> ReminderManager.earlier(ctx, query, minutes)
                "fire_now" -> ReminderManager.fireNow(ctx, query)
                "clear_all" -> ReminderManager.clearAll(ctx)
                else -> "Unknown action: $action"
            }
            ToolResponse(call.id, call.name, JSONObject().put("status", status))
        }

        "youtube_control" -> {
            val action = call.args.optString("action").trim().lowercase()
            val status = when (action) {
                "play" -> YouTubeControl.mediaPlayPause(ctx, true)
                "pause" -> YouTubeControl.mediaPlayPause(ctx, false)
                "toggle" -> YouTubeControl.mediaPlayPause(ctx, null)
                "set_speed" -> {
                    val speed = call.args.optString("speed").trim().ifEmpty { "2x" }
                    YouTubeControl.setSpeed(ctx, if (speed == "normal") "Normal" else speed)
                    "$speed speed laga raha hoon…"
                }
                else -> "Unknown youtube action: $action"
            }
            ToolResponse(call.id, call.name, JSONObject().put("status", status))
        }

        "screen_lock" -> {
            val action = call.args.optString("action").trim().lowercase()
            val status = when (action) {
                "lock" -> ScreenControl.lock(ctx)
                "unlock" -> ScreenControl.unlock(ctx)
                else -> "Unknown screen action: $action"
            }
            ToolResponse(call.id, call.name, JSONObject().put("status", status))
        }

        "whatsapp_autoreply" -> {
            val action = call.args.optString("action").trim().lowercase()
            val name = call.args.optString("name").trim()
            val status = when (action) {
                "on" -> {
                    WhatsAppAutoReply.setEnabled(ctx, true)
                    val targets = WhatsAppAutoReply.allowlist(ctx)
                    if (targets.isEmpty()) "WhatsApp auto-reply ON (sabke liye)."
                    else "WhatsApp auto-reply ON — sirf ${targets.joinToString(", ")}."
                }
                "off" -> {
                    WhatsAppAutoReply.setEnabled(ctx, false)
                    "WhatsApp auto-reply OFF."
                }
                "add_target" -> {
                    WhatsAppAutoReply.addTarget(ctx, name)
                    "Auto-reply sirf $name ke liye."
                }
                "remove_target" -> {
                    WhatsAppAutoReply.removeTarget(ctx, name)
                    "$name auto-reply se hata diya."
                }
                else -> "Unknown auto-reply action: $action"
            }
            ToolResponse(call.id, call.name, JSONObject().put("status", status))
        }

        "device_status" -> ToolResponse(
            call.id, call.name,
            JSONObject().put("status", DeviceHealth.summary(ctx))
        )

        "look_at_screen" -> {
            val ok = ScreenShareHelper.ensureScreenShare(ctx)
            // Instant accessibility UI dump — lets the model answer "what's on
            // screen" right now even before the video feed warms up.
            val dump = uiDumpNow(ctx)
            val status = if (ok) {
                "Screen share shuru ho raha hai (system consent dialog aaye to Allow karo). " +
                    "Screen text abhi: " + dump
            } else {
                "Screen share start nahi ho paya. Screen text: " + dump
            }
            ToolResponse(call.id, call.name, JSONObject().put("status", status))
        }

        "agent_run" -> {
            val goal = call.args.optString("goal").trim()
            ToolResponse(call.id, call.name, JSONObject().put("status", ScreenAgent.start(ctx, goal)))
        }

        else -> null
    }
}
