package com.aero.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Device-time helpers. Everything uses the phone's own timezone
 * (TimeZone.getDefault) — never a fixed server zone.
 */
object TimeUtil {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private val humanFmt = SimpleDateFormat("EEEE, d MMM yyyy, h:mm a", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    init {
        // Always format from the device timezone.
        isoFmt.timeZone = TimeZone.getDefault()
        humanFmt.timeZone = TimeZone.getDefault()
        timeFmt.timeZone = TimeZone.getDefault()
    }

    fun nowIso(): String = isoFmt.format(Date())

    fun nowHuman(): String = humanFmt.format(Date())

    fun nowTime(): String = timeFmt.format(Date())

    fun tzId(): String = TimeZone.getDefault().id
}
