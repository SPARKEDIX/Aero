package com.aero.voice

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device-app control: open apps, close/minimize apps, and clear recents.
 *
 * Android platform limits (honest notes):
 * - Opening any installed app: fully supported (launcher intent).
 * - Closing a THIRD-PARTY app by force: not allowed for normal apps without
 *   root/system permission. Best effort = minimize it (go to Home). Our own
 *   app can be stopped fully.
 * - Clearing recents: needs an AccessibilityService (we request the overview
 *   and try to press the system "Clear all" button via accessibility nodes).
 */
object AppControl {

    private const val TAG = "AeroApp"

    /** (label, packageName) of every launchable app. */
    fun installedApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        return list.mapNotNull { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim()
            val pkg = ri.activityInfo.packageName
            if (label.isNullOrBlank() || pkg.isBlank()) null else label to pkg
        }.sortedBy { it.first }
    }

    /** Fuzzy match: returns (label, packageName) whose label/package contains the query. */
    fun findApp(ctx: Context, query: String): Pair<String, String>? {
        val q = query.trim().lowercase()
        if (q.length < 2) return null
        val candidates = installedApps(ctx)
        // Exact label match first, then contains match on label/package.
        return candidates.firstOrNull { it.first.lowercase() == q }
            ?: candidates.firstOrNull { it.first.lowercase().contains(q) }
            ?: candidates.firstOrNull { it.second.lowercase().contains(q) }
    }

    /** Opens an app by launcher intent. Returns a spoken-status string. */
    fun openApp(ctx: Context, query: String): String {
        val hit = findApp(ctx, query)
        if (hit == null) return "I couldn't find an app called \"$query\"."
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(hit.second)
            if (launch == null) return "App \"${hit.first}\" cannot be launched."
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            return "Opening ${hit.first}."
        } catch (t: Throwable) {
            Log.e(TAG, "open failed", t)
            return "I couldn't open ${hit.first}."
        }
    }

    /**
     * Closes / minimizes an app. Third-party force-stop isn't permitted for
     * normal apps, so we minimize it by going Home. If it's our own app and a
     * session is running, we end the session too.
     */
    fun closeApp(ctx: Context, query: String): String {
        val hit = findApp(ctx, query)
        if (hit == null) return "I couldn't find an app called \"$query\"."
        if (hit.second == ctx.packageName) {
            // Our own app → end the session if active.
            if (AeroState.sessionActive) {
                AeroState.sessionActive = false
                AeroState.liveClient = null
                BackgroundSessionService.stop(ctx)
                FloatingOrbService.hide(ctx)
            }
            return "Closing Aero."
        }
        // Best effort: minimize the app by going to the Home screen.
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(home)
            return "I've minimized ${hit.first}. Android doesn't let me force-close other apps, but it's now in the background."
        } catch (t: Throwable) {
            return "I couldn't minimize ${hit.first}."
        }
    }

    /** Requests "Clear all" via the accessibility service (opens overview first). */
    fun clearRecents(ctx: Context): String {
        val sent = AeroAccessibilityService.requestClearRecents()
        return if (sent) {
            "Opening the recent apps screen to clear it."
        } else {
            "I need accessibility permission to clear recent apps. Please enable Aero in Settings → Accessibility → Installed services."
        }
    }

    // ---- Voice command parsing (Hinglish + English) ----

    sealed class Command {
        data class Open(val target: String) : Command()
        data class Close(val target: String) : Command()
        object ClearRecents : Command()
    }

    private val openRe = Regex(
        "(?i)\\b(open|kholo|khol|kholkar|chalao|launch)\\b(.*)"
    )
    private val closeRe = Regex(
        "(?i)\\b(close|band|karo|kholo|hatao)\\b.*(.*)"
    )
    private val recentsRe = Regex(
        "(?i)\\b(recent|recents|overview)\\b.*\\b(clear|karo|band|saaf)\\b"
    )
    private val recentsRe2 = Regex(
        "(?i)\\b(clear|saaf)\\b.*\\b(recent|recents|overview)\\b"
    )

    /** Converts a spoken command to a [Command], or null if not app-related. */
    fun parse(text: String): Command? {
        val t = text.trim().lowercase()
        if (t.length < 3) return null

        if (recentsRe.containsMatchIn(t) || recentsRe2.containsMatchIn(t)) {
            return Command.ClearRecents
        }

        val open = openRe.find(t)
        if (open != null) {
            val target = open.groupValues[2].trim()
            if (target.isNotEmpty()) return Command.Open(target)
        }

        val close = closeRe.find(t)
        if (close != null) {
            val target = close.groupValues[2].trim()
            if (target.isNotEmpty()) return Command.Close(target)
        }

        return null
    }

    /**
     * Attempts to run a parsed command. Returns the spoken result string.
     */
    fun run(ctx: Context, command: Command): String = when (command) {
        is Command.Open -> openApp(ctx, command.target)
        is Command.Close -> closeApp(ctx, command.target)
        is Command.ClearRecents -> clearRecents(ctx)
    }
}
