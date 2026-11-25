package com.aero.voice

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used for best-effort device automation:
 * - Opening Recents and pressing "Clear all".
 * - Tapping any visible element by its text/content description.
 * - Typing into the focused field via clipboard paste.
 * - Ending calls (finding the end-call button).
 * - Home / Back global actions.
 *
 * Users must enable this under Settings → Accessibility → Installed services.
 */
class AeroAccessibilityService : AccessibilityService() {

    private var drainPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "connected")
        liveService = this
        drain()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        drain()
        // After opening Overview, try to find and click "Clear all".
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && activeOverviewOpen > 0) {
            activeOverviewOpen--
            clickClearAll()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (liveService === this) liveService = null
        super.onDestroy()
    }

    // ---- Action queue ----

    private fun drain() {
        if (drainPending) return
        var action: Action? = null
        synchronized(actionLock) {
            action = pendingActions.poll()
        }
        when (action) {
            is Action.OpenRecents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is Action.ClickText -> performClickText(action.text)
            is Action.TypeText -> performTypeText(action.text)
            is Action.EndCall -> performEndCall()
            is Action.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is Action.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> return
        }
        drainPending = false
    }

    private fun performClickText(text: String) {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val q = text.lowercase()
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            if (txt.contains(q) || desc.contains(q)) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "clicked \"$text\"")
                } else {
                    Log.w(TAG, "node \"$text\" not clickable")
                }
                recycleAll(nodes)
                return
            }
        }
        Log.d(TAG, "no node found for \"$text\"")
        recycleAll(nodes)
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
            recycleAll(nodes)
            return
        }
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aero", text))
        editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "typed into field")
        recycleAll(nodes)
    }

    private fun performEndCall() {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val keyLabels = listOf("end call", "end", "disconnect", "hang up", "call end", "decline")
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            if (keyLabels.any { txt.contains(it) || desc.contains(it) }) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "end-call tapped")
                }
                recycleAll(nodes)
                return
            }
        }
        Log.d(TAG, "end-call button not found")
        recycleAll(nodes)
    }

    private fun clickClearAll() {
        val root = rootInActiveWindow ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAll(root, nodes)
        val labels = listOf("clear all", "clear", "saaf karo", "close all", "all clear")
        for (n in nodes) {
            val txt = n.text?.toString()?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            if (labels.any { txt.contains(it) || desc.contains(it) }) {
                val clickable = if (n.isClickable) n else n.parent?.takeIf { it.isClickable }
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "clicked clear-all")
                } else {
                    Log.w(TAG, "clear-all node not clickable")
                }
                recycleAll(nodes)
                return
            }
        }
        Log.d(TAG, "clear-all button not found in accessibility tree")
        recycleAll(nodes)
    }

    private fun collectAll(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAll(it, out) }
        }
    }

    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        nodes.forEach { it.recycle() }
    }

    private fun openRecents() {
        activeOverviewOpen++
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    companion object {
        private const val TAG = "AeroA11y"
        private val actionLock = Any()
        private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<Action>()

        @Volatile private var liveService: AeroAccessibilityService? = null
        @Volatile private var activeOverviewOpen = 0

        private sealed class Action {
            object OpenRecents : Action()
            data class ClickText(val text: String) : Action()
            data class TypeText(val text: String) : Action()
            object EndCall : Action()
            object Home : Action()
            object Back : Action()
        }

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
            synchronized(actionLock) { pendingActions.add(Action.OpenRecents) }
            pump()
            return true
        }

        fun requestClickByText(text: String): Boolean {
            if (!isEnabled()) return false
            synchronized(actionLock) { pendingActions.add(Action.ClickText(text)) }
            pump()
            return true
        }

        fun requestTypeText(text: String): Boolean {
            if (!isEnabled()) return false
            synchronized(actionLock) { pendingActions.add(Action.TypeText(text)) }
            pump()
            return true
        }

        fun requestEndCall(): Boolean {
            if (!isEnabled()) return false
            synchronized(actionLock) { pendingActions.add(Action.EndCall) }
            pump()
            return true
        }

        fun requestHome(): Boolean {
            if (!isEnabled()) return false
            synchronized(actionLock) { pendingActions.add(Action.Home) }
            pump()
            return true
        }

        fun requestBack(): Boolean {
            if (!isEnabled()) return false
            synchronized(actionLock) { pendingActions.add(Action.Back) }
            pump()
            return true
        }

        private fun pump() {
            val svc = liveService
            if (svc != null) svc.drain()
        }
    }
}
