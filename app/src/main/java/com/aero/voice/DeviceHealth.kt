package com.aero.voice

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock

/** Device health & details: battery, storage, RAM, uptime, model. */
object DeviceHealth {

    data class BatteryInfo(
        val level: Int,
        val charging: Boolean,
        val tempC: Float
    )

    fun battery(ctx: Context): BatteryInfo {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val temp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10f
        return BatteryInfo(level.coerceIn(0, 100), charging, temp)
    }

    fun storage(): Pair<Long, Long> { // (total, free) bytes of internal storage
        return try {
            val path = Environment.getDataDirectory()
            val st = StatFs(path.path)
            val total = st.totalBytes
            val free = st.availableBytes
            total to free
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    fun ram(ctx: Context): Pair<Long, Long> { // (total, avail) bytes
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem to mi.availMem
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    fun uptimeMinutes(): Long = SystemClock.elapsedRealtime() / 60000L

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

    private fun mb(b: Long): String = String.format("%.1f GB", b / (1024.0 * 1024 * 1024))

    /** Spoken summary of everything. */
    fun summary(ctx: Context): String {
        val b = battery(ctx)
        val (totalS, freeS) = storage()
        val (totalR, availR) = ram(ctx)
        return buildString {
            append("Battery ${b.level}%, ")
            append(if (b.charging) "charging" else "not charging")
            append(", temperature ${b.tempC}°C. ")
            append("Storage ${mb(freeS)} free of ${mb(totalS)}. ")
            append("RAM ${mb(availR)} free of ${mb(totalR)}. ")
            append("Uptime ${uptimeMinutes()} minutes. ")
            append("Device: ${model()}.")
        }
    }
}
