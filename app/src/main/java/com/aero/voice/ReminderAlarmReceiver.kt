package com.aero.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires when a reminder alarm goes off (or on boot, to re-arm alarms). */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val now = System.currentTimeMillis()
            ReminderStore.all(context).forEach { r ->
                when {
                    r.atMillis > now -> ReminderScheduler.schedule(context, r)
                    // Missed while the phone was off: ring now instead of dropping it.
                    now - r.atMillis < 6 * 60 * 60 * 1000L -> fire(context, r)
                    else -> ReminderStore.remove(context, r.id)
                }
            }
            Log.d(TAG, "re-armed reminders after boot")
            return
        }

        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty()
        val r = ReminderStore.all(context).firstOrNull { it.id == id }
            ?: ReminderStore.Reminder(id, message, System.currentTimeMillis()).takeIf { message.isNotBlank() }
            ?: return

        fire(context, r)
        Log.d(TAG, "reminder fired: ${r.message}")
    }

    private fun fire(context: Context, r: ReminderStore.Reminder) {
        // 1) Full-screen notification (works even when background activity
        //    starts are restricted).
        ReminderNotifier.show(context, r.id, r.message, ringing = true)
        // 2) Direct activity start (immediate on most devices, Aero has
        //    SYSTEM_ALERT_WINDOW which exempts it from background limits).
        try {
            val alert = Intent(context, ReminderAlertActivity::class.java).apply {
                putExtra(ReminderScheduler.EXTRA_ID, r.id)
                putExtra(ReminderScheduler.EXTRA_MESSAGE, r.message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(alert)
        } catch (t: Throwable) {
            Log.w(TAG, "direct start blocked, notification will show the call", t)
        }
    }

    companion object {
        private const val TAG = "AeroReminder"
    }
}
