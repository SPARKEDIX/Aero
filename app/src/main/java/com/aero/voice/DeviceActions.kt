package com.aero.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * High-level device actions: phone calls, WhatsApp message/call deep links,
 * and window-level controls (Home / Back) that we can do without accessibility.
 */
object DeviceActions {

    private const val TAG = "AeroDevAct"

    /** Places a phone call to a name or number. */
    fun call(ctx: Context, target: String): String {
        val number = ContactHelper.findNumber(ctx, target)
        if (number == null) return "I couldn't find a contact or number for $target."
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "Calling $target."
        } catch (t: Throwable) {
            Log.e(TAG, "call failed", t)
            "I couldn't start the call to $target."
        }
    }

    /** Opens WhatsApp with a pre-filled message; auto-send if accessibility is on. */
    fun whatsAppMessage(ctx: Context, target: String, message: String): String {
        // Ensure contacts permission is granted so we can look up names.
        val number = try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.READ_CONTACTS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ContactHelper.findNumber(ctx, target)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (number == null) {
            return "I need your contacts permission to find \"$target\". Please grant contacts access once."
        }
        val clean = number.replace(Regex("[^0-9]"), "")
        if (clean.isEmpty()) return "I couldn't get a number for $target."
        return try {
            val uri = Uri.parse("whatsapp://send?phone=$clean&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)

            if (AeroAccessibilityService.isEnabled()) {
                // Wait for the chat to open, then tap Send (accurate node click).
                AeroAccessibilityService.requestWaitThenTap("send", 10_000L)
                // Feed the current screen text back to the model for awareness.
                AeroAccessibilityService.requestDump { dump ->
                    AeroState.liveClient?.sendContextText("Current screen: $dump")
                }
                "Opening WhatsApp to send the message to $target."
            } else {
                "WhatsApp is open with your message ready."
            }
        } catch (t: Throwable) {
            Log.e(TAG, "whatsapp failed", t)
            "I couldn't open WhatsApp for $target."
        }
    }

    /** Starts a WhatsApp voice or video call. Returns spoken status. */
    fun whatsAppCall(ctx: Context, target: String, video: Boolean): String {
        val number = ContactHelper.findNumber(ctx, target)
        if (number == null) return "I couldn't find a contact or number for $target."
        val clean = number.replace(Regex("[^0-9]"), "")
        return try {
            val mode = if (video) "video=true" else "audio=true"
            val uri = Uri.parse("whatsapp://call?phone=$clean&$mode")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            if (video) "Starting a WhatsApp video call with $target." else "Starting a WhatsApp voice call with $target."
        } catch (t: Throwable) {
            Log.e(TAG, "whatsapp call failed", t)
            "I couldn't start the WhatsApp call with $target."
        }
    }

    /** Ends the active call via accessibility (tap "End call"), or opens dialer if needed. */
    fun endCall(ctx: Context): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestEndCall()
            "Trying to end the call."
        } else {
            "I need accessibility permission to end calls. Opening settings for you…"
                .also { ScreenShareHelper.openAccessibilitySettings(ctx) }
        }
    }

    /** Sends a generic click on any text/button in the current screen. */
    fun clickByText(ctx: Context, text: String): String {
        // UI-hierarchy automation: exact node click, no screen-share needed.
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestWaitThenTap(text, 8000L)
            // Let the model know what's now on screen.
            AeroAccessibilityService.requestDump { dump ->
                AeroState.liveClient?.sendContextText("After tapping \"$text\", screen: $dump")
            }
            "Tapping \"$text\" on the screen."
        } else {
            "I need accessibility permission to tap the screen. Opening settings for you…"
                .also { ScreenShareHelper.openAccessibilitySettings(ctx) }
        }
    }

    /** Types text into the currently focused field via clipboard paste. */
    fun typeText(ctx: Context, text: String): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestTypeText(text)
            "Typing on the screen."
        } else {
            "I need accessibility permission to type. Opening settings for you…"
                .also { ScreenShareHelper.openAccessibilitySettings(ctx) }
        }
    }

    fun goHome(ctx: Context): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestHome()
            "Going to the home screen."
        } else {
            "I need accessibility permission to press Home."
        }
    }

    fun goBack(ctx: Context): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestBack()
            "Going back."
        } else {
            "I need accessibility permission to go back."
        }
    }
}
