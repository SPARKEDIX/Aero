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

    fun isDeviceOwner(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    /** adb command that promotes Aero to Device Owner (enables true auto-unlock). */
    fun deviceOwnerCommand(): String =
        "adb shell dpm set-device-owner com.aero.voice/.AeroDeviceAdminReceiver"

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
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = adminComponent(ctx)

        // setKeyguardDisabled works only for Device Owner / Profile Owner.
        if (isDeviceOwner(ctx)) {
            return try {
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
                    return "Unlock fail ho gaya (keyguard disable nahi hua)."
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        dpm.setKeyguardDisabled(component, false)
                    } catch (_: Throwable) {
                    }
                }, UNLOCK_AUTO_RELOCK_MS)
                "Phone unlock ho gaya (60s baad apne aap wapas lock)."
            } catch (t: Throwable) {
                Log.w(TAG, "unlock failed", t)
                "Unlock kaam nahi kiya: ${t.message}"
            }
        }

        // Normal Device Admin (not owner): Android doesn't allow auto keyguard
        // disable. Best we can do is open the credential screen — user enters
        // PIN/pattern once (or swipe if none).
        return "Aero Device Owner nahi hai, isliye auto keyguard bypass nahi ho sakta. " +
            "Lock screen khol diya — PIN/pattern daal do. " +
            "Full auto-unlock ke liye PC se chalao: ${deviceOwnerCommand()}"
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
