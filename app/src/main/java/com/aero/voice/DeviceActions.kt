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
        val number = ContactHelper.findNumber(ctx, target)
        if (number == null) return "I couldn't find a contact or number for $target."
        val clean = number.replace(Regex("[^0-9]"), "")
        return try {
            val uri = Uri.parse("https://wa.me/$clean?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            if (AeroAccessibilityService.isEnabled()) {
                // Give WhatsApp a moment to open the chat, then tap Send.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    AeroAccessibilityService.requestClickByText("send")
                }, 2500L)
                "Opening WhatsApp to send the message to $target."
            } else {
                "WhatsApp is open with your message ready — press send to deliver it."
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
            "I need accessibility permission to end calls. Please enable Aero in Settings → Accessibility."
        }
    }

    /** Sends a generic click on any text/button in the current screen. */
    fun clickByText(ctx: Context, text: String): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestClickByText(text)
            "Tapping \"$text\" on the screen."
        } else {
            "I need accessibility permission to tap the screen. Please enable Aero in Settings → Accessibility."
        }
    }

    /** Types text into the currently focused field via clipboard paste. */
    fun typeText(ctx: Context, text: String): String {
        return if (AeroAccessibilityService.isEnabled()) {
            AeroAccessibilityService.requestTypeText(text)
            "Typing on the screen."
        } else {
            "I need accessibility permission to type. Please enable Aero in Settings → Accessibility."
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
