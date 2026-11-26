package com.aero.voice

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used for best-effort device automation:
 * - Tapping any visible element by its text/content description/resource-id.
 * - Typing into the focused field via clipboard paste.
 * - Ending calls and clearing recents (best effort, with retries).
 *
 * Users must enable this under Settings → Accessibility → Installed services.
 */
class AeroAccessibilityService : AccessibilityService() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "connected")
        liveService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                captureNotification(event)
                // WhatsApp auto-reply hook
                try {
                    val pkg = event.packageName?.toString() ?: ""
                    if (pkg.contains("whatsapp") && WhatsAppAutoReply.isEnabled(this)) {
                        val text = event.text?.joinToString(" ")?.trim().orEmpty()
                        if (text.isNotEmpty() && WhatsAppAutoReply.shouldHandle(this, text)) {
                            lastWaNotificationAt = System.currentTimeMillis()
                            handler.postDelayed({
                                if (System.currentTimeMillis() - lastWaNotificationAt > 2500) {
                                    WhatsAppAutoReply.handleIncoming(this, text)
                                }
                            }, 3000L)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // After opening Overview, try to find and click "Clear all".
                while (activeOverviewOpen > 0) {
                    activeOverviewOpen--
                    clickClearAll()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (liveService === this) liveService = null
        super.onDestroy()
    }

    // ---- Action execution (queue drained per request) ----

    private fun performClickText(text: String) {
        performClickText(text, 3)
    }

    private fun performClickText(text: String, attemptsLeft: Int) {
        val clicked = tryFindAndClick(text)
        if (clicked) return
        if (attemptsLeft > 1) {
            Log.d(TAG, "retry click \"$text\" (${attemptsLeft - 1} left)")
            handler.postDelayed({ performClickText(text, attemptsLeft - 1) }, 1200L)
        } else {
            Log.d(TAG, "no node found for \"$text\"")
        }
    }

    private fun tryFindAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val q = text.lowercase()
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            val resId = n.viewIdResourceName?.lowercase() ?: ""
            if (txt.contains(q) || desc.contains(q) || resId.contains(q)) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "clicked \"$text\" (ok=$ok)")
                    return true
                }
            }
        }
        return false
    }

    private fun performTypeText(text: String) {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        var editable: AccessibilityNodeInfo? = null
        for (n in nodes) {
            if (n.isEditable || (n.className?.toString()?.contains("EditText") == true)) {
                editable = n
                break
            }
        }
        if (editable == null) {
            Log.w(TAG, "no editable field found")
            return
        }
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aero", text))
        editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "typed into field")
    }

    private fun performEndCall() {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val keyLabels = listOf("end call", "end", "disconnect", "hang up", "call end", "decline", "call_button_end")
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            val resId = n.viewIdResourceName?.lowercase() ?: ""
            if (keyLabels.any { txt.contains(it) || desc.contains(it) || resId.contains(it) }) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "end-call tapped")
                }
                return
            }
        }
        Log.d(TAG, "end-call button not found")
    }

    private fun clickClearAll() {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val labels = listOf(
            "clear all", "clr_all", "close all", "remove all", "saaf karo",
            "clear", "remove", "clear_recents", "clearall", "recents_clear_all"
        )
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            val resId = n.viewIdResourceName?.lowercase() ?: ""
            if (labels.any { txt.contains(it) || desc.contains(it) || resId.contains(it) }) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "clicked clear-all")
                    return
                }
            }
        }
        // Retry once after a beat (overview animations can take time).
        handler.postDelayed({
            rootInActiveWindow?.let { clickClearAll() }
        }, 1500L)
    }

    private fun collectAll(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAll(it, out) }
        }
    }

    // ---- Scroll ----

    private fun performScroll(forward: Boolean) {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        for (n in nodes) {
            if (n.isScrollable) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                n.performAction(action)
                Log.d(TAG, if (forward) "scrolled forward" else "scrolled backward")
                return
            }
        }
        Log.d(TAG, "no scrollable node found")
    }

    // ---- Notifications (live capture) ----

    private val recentNotifications = ArrayDeque<String>()
    private var lastWaNotificationAt = 0L

    private fun captureNotification(event: AccessibilityEvent) {
        val text = event.text?.joinToString(" ")?.trim().orEmpty()
        if (text.isNotEmpty()) {
            recentNotifications.addLast(text)
            while (recentNotifications.size > 15) recentNotifications.removeFirst()
        }
    }

    private fun performNotificationsDump(): String {
        return if (recentNotifications.isEmpty()) {
            "(no notifications captured yet)"
        } else {
            recentNotifications.joinToString(" | ")
        }
    }

    private fun performClearNotifications() {
        // Open the notification shade, then try to find Clear all / Dismiss.
        try { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) } catch (_: Throwable) {}
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collectAll(root, nodes)
            val labels = listOf("clear all", "clear notifications", "dismiss all", "saaf karo", "clear")
            for (n in nodes) {
                val txt = n.text?.toString()?.lowercase() ?: ""
                val desc = n.contentDescription?.toString()?.lowercase() ?: ""
                if (labels.any { txt.contains(it) || desc.contains(it) }) {
                    val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                    if (clickable != null) {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "notification clear-all tapped")
                    }
                    return@postDelayed
                }
            }
            Log.d(TAG, "notification clear-all not found")
        }, 2000L)
    }

    // ---- Screen-text dump (text "screenshot" for the model) ----

    private fun performDump(): String {
        val root = rootInActiveWindow ?: return "(no window)"
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val sb = StringBuilder()
        for (n in nodes) {
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text else desc
            if (label.isNotEmpty() && label.length < 120) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(label)
                if (sb.length > 1800) break
            }
        }
        return if (sb.isEmpty()) "(screen has no readable text)" else sb.toString()
    }

    // ---- Wait until a node appears, then tap it ----

    private fun waitThenTap(text: String, timeoutMs: Long) {
        fun attempt(remainingMs: Long) {
            if (tryFindAndClick(text)) {
                Log.d(TAG, "waitThenTap clicked \"$text\"")
            } else if (remainingMs > 0) {
                handler.postDelayed({ attempt(remainingMs - 300) }, 300L)
            } else {
                Log.d(TAG, "waitThenTap timeout on \"$text\"")
            }
        }
        attempt(timeoutMs)
    }

    /** Opens WhatsApp, opens the chat with [sender], types [reply], taps Send. */
    private fun performAutoReply(sender: String, reply: String) {
        if (sender.isBlank() || reply.isBlank()) return
        try {
            val app = AeroApp.instance ?: return
            val launch = app.packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
        } catch (_: Throwable) {
            return
        }
        handler.postDelayed({
            if (!tryFindAndClick(sender)) {
                Log.d(TAG, "auto-reply: chat \"$sender\" nahi mila")
                return@postDelayed
            }
            handler.postDelayed({
                performTypeText(reply)
                handler.postDelayed({
                    val ok = tryFindAndClick("send") ||
                        tryFindAndClick("Send") ||
                        tryFindAndClick("Send message")
                    Log.d(TAG, "auto-reply send tapped=$ok")
                }, 700L)
            }, 900L)
        }, 1600L)
    }

    companion object {
        private const val TAG = "AeroA11y"

        @Volatile private var liveService: AeroAccessibilityService? = null
        @Volatile private var activeOverviewOpen = 0

        /** True if this accessibility service is enabled on the device. */
        fun isEnabled(): Boolean {
            return try {
                val app = AeroApp.instance ?: return false
                val flat = Settings.Secure.getString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                flat.split(":").any {
                    it.equals("${app.packageName}/${AeroAccessibilityService::class.java.name}", true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "isEnabled check failed", t)
                false
            }
        }

        fun requestClearRecents(): Boolean {
            if (!isEnabled()) return false
            activeOverviewOpen++
            val svc = liveService
            if (svc != null) {
                try { svc.performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Throwable) {}
                return true
            }
            return false
        }

        fun requestClickByText(text: String, attempt: Int = 0): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            if (attempt > 0) {
                svc.handler.postDelayed({ svc.performClickText(text) }, 2500L)
            } else {
                svc.performClickText(text)
            }
            return true
        }

        fun requestTypeText(text: String): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.performTypeText(text)
            return true
        }

        fun requestEndCall(): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.handler.postDelayed({ svc.performEndCall() }, 800L)
            return true
        }

        fun requestHome(): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            try { svc.performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
            return true
        }

        fun requestBack(): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            try { svc.performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) {}
            return true
        }

        /** Reads the current screen as text and delivers it via [onResult] (main thread). */
        fun requestDump(onResult: (String) -> Unit) {
            val svc = liveService
            if (svc == null || !isEnabled()) {
                onResult("(accessibility not enabled)")
                return
            }
            svc.handler.post {
                val dump = svc.performDump()
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(dump) }
            }
        }

        /** Taps a node once it appears; keeps retrying up to [timeoutMs]. */
        fun requestWaitThenTap(text: String, timeoutMs: Long = 10_000L): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.handler.post { svc.waitThenTap(text, timeoutMs) }
            return true
        }

        /** Scrolls the current scrollable screen (forward=true → down). */
        fun requestScroll(forward: Boolean): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.handler.post { svc.performScroll(forward) }
            return true
        }

        /** Reads the recent notifications as text via [onResult] (main thread). */
        fun requestNotificationsDump(onResult: (String) -> Unit) {
            val svc = liveService
            if (svc == null || !isEnabled()) {
                onResult("(accessibility not enabled)")
                return
            }
            svc.handler.post {
                val dump = svc.performNotificationsDump()
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(dump) }
            }
        }

        /** Best-effort: opens the notification shade and taps Clear all. */
        fun requestClearNotifications(): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.handler.post { svc.performClearNotifications() }
            return true
        }

        /** Sends a WhatsApp auto-reply (open chat → type → send). */
        fun requestAutoReply(sender: String, reply: String): Boolean {
            if (!isEnabled()) return false
            val svc = liveService ?: return false
            svc.handler.post { svc.performAutoReply(sender, reply) }
            return true
        }

        /** Package name of the current top window (or null). */
        fun currentPackage(): String? =
            liveService?.rootInActiveWindow?.packageName?.toString()
    }
}
