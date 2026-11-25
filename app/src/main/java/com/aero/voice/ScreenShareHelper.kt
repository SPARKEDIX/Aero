package com.aero.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Helpers for "special access" setup:
 * - Auto-starting screen-share consent when the model needs to SEE the screen.
 * - Deep-linking to the accessibility settings page so the user can enable
 *   Aero once (Android requires manual enable — we can't self-grant).
 */
object ScreenShareHelper {

    /**
     * Ensures screen sharing is active. If not, launches the invisible consent
     * activity so the user can accept (Android requires consent each time).
     * Returns true if sharing is already running or consent was launched.
     */
    fun ensureScreenShare(ctx: Context): Boolean {
        if (MediaProjectionHolder.isRunning) return true
        return try {
            val intent = Intent(ctx, ScreenShareConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Opens the system accessibility settings so Aero can be enabled. */
    fun openAccessibilitySettings(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    /** Opens the system overlay/display-over-other-apps settings. */
    fun openOverlaySettings(ctx: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    /** True when Aero's accessibility service is enabled. */
    fun isAccessibilityEnabled(): Boolean = AeroAccessibilityService.isEnabled()
}
