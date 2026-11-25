package com.aero.voice

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * AI screen lock / unlock.
 *
 * - Lock: uses the Device Admin `lockNow()` (real, instant lock).
 * - Unlock: Android does NOT let third-party apps type your PIN/pattern on the
 *   lock screen. Instead, with Device Admin we temporarily DISABLE the keyguard
 *   (screen opens straight to home) and re-enable it after 60 seconds so the
 *   phone protects itself again.
 */
object ScreenControl {

    private const val TAG = "AeroScreenControl"
    private const val UNLOCK_AUTO_RELOCK_MS = 60_000L

    fun adminComponent(ctx: Context): ComponentName =
        ComponentName(ctx, AeroDeviceAdminReceiver::class.java)

    fun isAdmin(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(adminComponent(ctx))
        } catch (t: Throwable) {
            Log.w(TAG, "isAdmin failed", t)
            false
        }
    }

    fun openAdminSettings(ctx: Context) {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Aero ko screen lock/unlock ke liye Device Admin chahiye."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "openAdminSettings failed", t)
            try {
                val i = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Throwable) {
            }
        }
    }

    fun lock(ctx: Context): String {
        if (!isAdmin(ctx)) {
            openAdminSettings(ctx)
            return "Pehle Developer Mode mein Aero ko Device Admin banao, phir lock karunga."
        }
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            Log.d(TAG, "locked via lockNow()")
            "Phone lock kar diya."
        } catch (t: Throwable) {
            Log.w(TAG, "lock failed", t)
            "Lock kaam nahi kiya: ${t.message}"
        }
    }

    /** Temporarily disables the keyguard; re-enables after 60s. */
    fun unlock(ctx: Context): String {
        if (!isAdmin(ctx)) {
            openAdminSettings(ctx)
            return "Pehle Developer Mode mein Aero ko Device Admin banao, phir unlock karunga."
        }
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setKeyguardDisabled(adminComponent(ctx), true)
            Log.d(TAG, "keyguard disabled (AI unlock)")
            // Auto re-lock after 60s.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    dpm.setKeyguardDisabled(adminComponent(ctx), false)
                    Log.d(TAG, "keyguard re-enabled")
                } catch (_: Throwable) {
                }
            }, UNLOCK_AUTO_RELOCK_MS)
            "Phone unlock ho gaya (60 second baad apne aap wapas lock hoga)."
        } catch (t: Throwable) {
            Log.w(TAG, "unlock failed", t)
            "Unlock kaam nahi kiya: ${t.message}"
        }
    }
}
