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
        // 1) Best: dedicated device-admin activation screen for Aero.
        val add = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(ctx))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Aero ko screen lock/unlock ke liye Device Admin chahiye."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (add.resolveActivity(ctx.packageManager) != null) {
            try {
                ctx.startActivity(add)
                return
            } catch (_: Throwable) {
            }
        }

        // 2) Fallback: the full "Device admin apps" list — user taps Aero → Activate.
        val list = Intent("android.settings.DEVICE_ADMIN_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (list.resolveActivity(ctx.packageManager) != null) {
            try {
                ctx.startActivity(list)
                return
            } catch (_: Throwable) {
            }
        }

        // 3) Last resort: generic Security settings (Samsung: Security → More → Device admin apps).
        try {
            val i = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
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
            val component = adminComponent(ctx)

            // Disable keyguard (retry a couple of times — one shot can silently
            // fail on some OEM builds right after activation).
            var disabled = false
            for (attempt in 1..3) {
                try {
                    dpm.setKeyguardDisabled(component, true)
                    disabled = true
                    break
                } catch (_: Throwable) {
                }
                Thread.sleep(250)
            }
            if (!disabled) {
                return "Unlock fail ho gaya (keyguard disable nahi hua). Device Admin active hai na? Developer Mode check karo."
            }
            Log.d(TAG, "keyguard disabled (AI unlock)")
            // Auto re-lock after 60s.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    dpm.setKeyguardDisabled(component, false)
                    Log.d(TAG, "keyguard re-enabled")
                } catch (_: Throwable) {
                }
            }, UNLOCK_AUTO_RELOCK_MS)

            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) {
                "Phone unlock ho gaya (60 second baad apne aap wapas lock hoga)."
            } else {
                "Screen off hai — power button dabao, PIN nahi maangega (60s tak)."
            }
        } catch (t: Throwable) {
            Log.w(TAG, "unlock failed", t)
            "Unlock kaam nahi kiya: ${t.message}"
        }
    }

    /** Unlock from an Activity context — also dismisses any visible keyguard UI. */
    fun unlockFromActivity(activity: android.app.Activity): String {
        val result = unlock(activity)
        try {
            val kgm = activity.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (kgm.isKeyguardLocked) {
                kgm.requestDismissKeyguard(activity, null)
            }
        } catch (_: Throwable) {
        }
        return result
    }
}
