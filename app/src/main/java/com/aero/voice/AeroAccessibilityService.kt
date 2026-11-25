package com.aero.voice

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used for best-effort system actions:
 * - Opening the Recents/Overview screen (GLOBAL_ACTION_RECENTS).
 * - Attempting to press the system "Clear all" button via accessibility nodes.
 *
 * Users must enable this under Settings → Accessibility → Installed services.
 * This is optional — voice control still works for opening/minimizing apps.
 */
class AeroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "connected")
        liveService = this
        if (pendingClearRecents) {
            pendingClearRecents = false
            openRecents()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // After opening Overview, try to find and click "Clear all".
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            activeOverviewOpen > 0
        ) {
            activeOverviewOpen--
            clickClearAll()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (liveService === this) liveService = null
        super.onDestroy()
    }

    private fun openRecents() {
        try {
            activeOverviewOpen++
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (t: Throwable) {
            Log.e(TAG, "recents action failed", t)
            activeOverviewOpen = 0
        }
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

    companion object {
        private const val TAG = "AeroA11y"

        @Volatile private var liveService: AeroAccessibilityService? = null
        @Volatile private var pendingClearRecents = false
        @Volatile private var activeOverviewOpen = 0

        /** True if this accessibility service is enabled on the device. */
        fun isEnabled(): Boolean {
            return try {
                val app = AeroApp.instance ?: return false
                val flat = Settings.Secure.getString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                flat.split(":").any { it.equals("${app.packageName}/${AeroAccessibilityService::class.java.name}", true) }
            } catch (t: Throwable) {
                Log.e(TAG, "isEnabled check failed", t)
                false
            }
        }

        /**
         * Requests clearing recents. Returns true if the service is enabled and
         * the request was queued (Overview will open, then we press Clear all).
         */
        fun requestClearRecents(): Boolean {
            if (!isEnabled()) return false
            pendingClearRecents = true
            val svc = liveService
            if (svc != null) {
                pendingClearRecents = false
                svc.openRecents()
            }
            return true
        }
    }
}
