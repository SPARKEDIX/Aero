package com.aero.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires when a reminder alarm goes off (or on boot, to re-arm alarms). */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Re-arm all future reminders after reboot.
            val now = System.currentTimeMillis()
            ReminderStore.all(context)
                .filter { it.atMillis > now && it.retryCount == 0 }
                .forEach { ReminderScheduler.schedule(context, it) }
            Log.d(TAG, "re-armed reminders after boot")
            return
        }

        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val r = ReminderStore.all(context).firstOrNull { it.id == id } ?: return

        // Launch full-screen ringing alert.
        val alert = Intent(context, ReminderAlertActivity::class.java).apply {
            putExtra(ReminderScheduler.EXTRA_ID, r.id)
            putExtra(ReminderScheduler.EXTRA_MESSAGE, r.message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(alert)
        Log.d(TAG, "reminder fired: ${r.message}")
    }

    companion object {
        private const val TAG = "AeroReminder"
    }
}
