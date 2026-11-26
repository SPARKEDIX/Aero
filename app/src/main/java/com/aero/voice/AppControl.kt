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

            // After the app opens, read the screen (text dump) and feed it to
            // the model so it knows what's on screen and can plan the next tap.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                AeroAccessibilityService.requestDump { dump ->
                    AeroState.liveClient?.sendContextText(
                        "${hit.first} is now open. Current screen: $dump"
                    )
                }
            }, 1500L)
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
            "I need accessibility permission to clear recent apps. Opening settings for you…"
                .also { ScreenShareHelper.openAccessibilitySettings(ctx) }
        }
    }

    // ---- Voice command parsing (Hinglish + English) ----

    sealed class Command {
        data class Open(val target: String) : Command()
        data class Close(val target: String) : Command()
        object ClearRecents : Command()
        data class Call(val target: String) : Command()
        object CutCall : Command()
        data class WhatsAppMessage(val target: String, val message: String) : Command()
        data class WhatsAppCall(val target: String, val video: Boolean) : Command()
        data class Click(val target: String) : Command()
        data class Type(val target: String) : Command()
        object Home : Command()
        object Back : Command()
        object ScrollDown : Command()
        object ScrollUp : Command()
        object ReadNotifications : Command()
        object ClearNotifications : Command()
        object TorchOn : Command()
        object TorchOff : Command()
        object BluetoothOn : Command()
        object BluetoothOff : Command()
        data class Brightness(val level: Int) : Command()
        object Screenshot : Command()
        object WifiSettings : Command()
        object DataSettings : Command()
        data class CameraLive(val lens: String) : Command()
        object CameraStop : Command()
        object VolumeUp : Command()
        object VolumeDown : Command()
        data class VolumeSet(val percent: Int) : Command()
        data class Reminder(val message: String, val atMillis: Long) : Command()
        data class Remember(val fact: String) : Command()
        data class Forget(val query: String) : Command()
        object RecallMemory : Command()
        data class CallMe(val atMillis: Long?) : Command()
        object ListReminders : Command()
        data class CancelReminder(val query: String) : Command()
        data class DelayReminder(val query: String, val minutes: Int) : Command()
        data class EarlierReminder(val query: String, val minutes: Int) : Command()
        data class FireReminderNow(val query: String) : Command()
        object ClearAllReminders : Command()
        data class YoutubePlayPause(val play: Boolean?) : Command()
        data class YoutubeSpeed(val label: String) : Command()
        object LockPhone : Command()
        object UnlockPhone : Command()
        object AutoReplyOn : Command()
        object AutoReplyOff : Command()
        data class AutoReplyAdd(val name: String) : Command()
        data class AutoReplyRemove(val name: String) : Command()
        object BatteryStatus : Command()
        object DeviceStatus : Command()
        object LookScreen : Command()
        data class AgentTask(val goal: String) : Command()
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
    private val cutCallRe = Regex(
        "(?i)\\b(cut|kato|band|end|disconnect)\\b.*\\b(call|phone|baat)\\b|\\b(call|phone)\\b.*\\b(cut|kato|band|end)\\b"
    )
    private val callRe = Regex(
        "(?i)\\b(call|call karo|ko call karo|phone karo)\\b(.*)"
    )
    private val whatsAppMsgRe = Regex(
        "(?i)\\b(whatsapp|whats app|wa)\\b.*\\b(message|msg|bhejo|send)\\b(.*)"
    )
    private val videoCallRe = Regex(
        "(?i)\\b(video call|video call karo|ko video call)\\b(.*)"
    )
    private val voiceCallRe = Regex(
        "(?i)\\b(voice call|audio call|call karo)\\b(.*)"
    )
    private val typeRe = Regex(
        "(?i)\\b(type|likho|likh|type karo)\\b(.*)"
    )
    private val clickRe = Regex(
        "(?i)\\b(tap|click|dabao|daba|press)\\b(.*)"
    )
    private val homeRe = Regex(
        "(?i)\\b(go home|home\\s*(jao|jayo|karo)|home screen)\\b"
    )
    private val backRe = Regex(
        "(?i)\\b(go back|back karo|waps|wapas|peeche|piche)\\b"
    )

    /** Converts a spoken command to a [Command], or null if not app-related. */
    fun parse(text: String): Command? {
        val t = text.trim().lowercase()
        if (t.length < 3) return null

        if (recentsRe.containsMatchIn(t) || recentsRe2.containsMatchIn(t)) {
            return Command.ClearRecents
        }
        if (cutCallRe.containsMatchIn(t)) {
            return Command.CutCall
        }
        if (homeRe.containsMatchIn(t)) {
            return Command.Home
        }
        if (backRe.containsMatchIn(t)) {
            return Command.Back
        }

        // Torch
        if (Regex("(?i)\\b(torch|flashlight|flash)\\b.*\\b(on|jalo|chalu|kholo)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(on|jalo|chalu)\\b.*\\b(torch|flashlight|flash)\\b").containsMatchIn(t)
        ) return Command.TorchOn
        if (Regex("(?i)\\b(torch|flashlight|flash)\\b.*\\b(off|band|bujhao|bujha)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(off|band|bujhao|bujha)\\b.*\\b(torch|flashlight|flash)\\b").containsMatchIn(t)
        ) return Command.TorchOff

        // Bluetooth
        if (Regex("(?i)\\b(bluetooth|bt)\\b.*\\b(on|jalo|chalu)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(on|jalo|chalu)\\b.*\\b(bluetooth|bt)\\b").containsMatchIn(t)
        ) return Command.BluetoothOn
        if (Regex("(?i)\\b(bluetooth|bt)\\b.*\\b(off|band|bujhao)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(off|band|bujhao)\\b.*\\b(bluetooth|bt)\\b").containsMatchIn(t)
        ) return Command.BluetoothOff

        // Brightness
        val br = Regex("(?i)\\b(brightness|brightness set|roshni|chamak)\\b.*?([0-9]{1,3})(?:%| percent)?").find(t)
        if (br != null) {
            val raw = br.groupValues[2].toIntOrNull()
            if (raw != null) {
                // 0-100% → 0-255 system scale.
                return Command.Brightness((raw * 255 / 100).coerceIn(0, 255))
            }
        }
        if (t.contains("brightness") || t.contains("roshni") || t.contains("chamak")) {
            return Command.Brightness(128)
        }

        // Scroll
        if (Regex("(?i)\\b(scroll down|neeche jao|scroll karo neeche|swipe up)\\b").containsMatchIn(t)) {
            return Command.ScrollDown
        }
        if (Regex("(?i)\\b(scroll up|upar jao|swipe down)\\b").containsMatchIn(t)) {
            return Command.ScrollUp
        }

        // Notifications
        if (Regex("(?i)\\b(read|padho|padh|dikhao)\\b.*\\b(notification|notifications|notice)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(notification|notifications)\\b.*\\b(read|padho|padh|dikhao)\\b").containsMatchIn(t)
        ) return Command.ReadNotifications
        if (Regex("(?i)\\b(clear|saaf|band)\\b.*\\b(notification|notifications)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(notification|notifications)\\b.*\\b(clear|saaf|band)\\b").containsMatchIn(t)
        ) return Command.ClearNotifications

        // Screenshot
        if (Regex("(?i)\\b(screenshot|screen shot|ss lo|photo lo|screenshot lo)\\b").containsMatchIn(t)) {
            return Command.Screenshot
        }

        // Volume
        if (Regex("(?i)\\b(volume up|volume badhao|awaz badhao|volume increase)\\b").containsMatchIn(t)) {
            return Command.VolumeUp
        }
        if (Regex("(?i)\\b(volume down|volume kam karo|awaz kam|volume decrease)\\b").containsMatchIn(t)) {
            return Command.VolumeDown
        }
        val vol = Regex("(?i)\\b(volume|awaz|aawaz)\\b.*?([0-9]{1,3})(?:%| percent)?").find(t)
        if (vol != null) {
            val raw = vol.groupValues[2].toIntOrNull()
            if (raw != null) return Command.VolumeSet(raw.coerceIn(0, 100))
        }

        // Reminder: "kal 5 baje yaad kara" / "remind me at 6"
        if (Regex("(?i)\\b(yaad|remind|reminder|yaad kara|yaad dilana)\\b").containsMatchIn(t)) {
            val parsed = ReminderParser.parse(t)
            if (parsed != null) {
                return Command.Reminder(parsed.message, parsed.atMillis)
            }
        }

        // ---- Reminder management ----

        // List: "reminders dikhao" / "kya kya reminders hain"
        if (Regex("(?i)\\b(reminders|reminder|yaad)\\b.*\\b(dikhao|dikha|batao|bata|list|kya kya)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(kya kya|list)\\b.*\\b(reminders|reminder|yaad)\\b").containsMatchIn(t)
        ) {
            return Command.ListReminders
        }

        // Clear all: "saare reminders clear karo" / "clear all reminders"
        if (Regex("(?i)\\b(saare|sab|all)\\b.*\\b(clear|saaf|band|hatao)\\b.*\\b(reminder|reminders|yaad)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(clear|saaf|band|hatao)\\b.*\\b(saare|sab|all)\\b.*\\b(reminder|reminders|yaad)\\b").containsMatchIn(t)
        ) {
            return Command.ClearAllReminders
        }

        // "Abhi karo" / "fire now" — without a target, fire ALL.
        if (Regex("(?i)\\b(reminder|reminders|yaad)\\b.*\\b(abhi|now|fori|turant)\\b.*\\b(karo|karna|kar do|fire)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(abhi|now)\\b.*\\b(reminder|reminders|yaad)\\b.*\\b(karo|karna|kar do)\\b").containsMatchIn(t)
        ) {
            val target = reminderTarget(t)
            return Command.FireReminderNow(target)
        }

        // Delay: "reminder 10 minute delay karo" / "reminder delay karo 10 minute" / "10 min aage kar do"
        val delayMin = Regex(
            "(?i)\\b(reminder|reminders|yaad)\\b.*\\b([0-9]{1,3}|[a-z]+)\\s*(minute|minutes|mins|min|ghanta|ghante|hour)\\s*(delay|aage|baad|peeche|piche)\\b|\\b(delay|aage|baad|peeche|piche)\\b.*\\b([0-9]{1,3}|[a-z]+)\\s*(minute|minutes|mins|min|ghanta|ghante|hour)\\b"
        ).find(t)
        if (delayMin != null) {
            val n = delayMin.groupValues[2].ifEmpty { delayMin.groupValues[6] }
            ReminderParser.parseNumber(n)?.let { minutes ->
                if (minutes in 1..1440) {
                    return Command.DelayReminder(reminderTarget(t), minutes)
                }
            }
        }

        // Earlier: "jldi karo 10 minute" / "reminder pehle kar do" / "10 min jldi"
        val earlierMin = Regex(
            "(?i)\\b(reminder|reminders|yaad)\\b.*\\b(jldi|jaldi|pehle|earlier|peedhe)\\b.*\\b([0-9]{1,3}|[a-z]+)\\s*(minute|minutes|mins|min|ghanta|ghante|hour)\\b|\\b([0-9]{1,3}|[a-z]+)\\s*(minute|minutes|mins|min|ghanta|ghante|hour)\\s*(jldi|jaldi|pehle|earlier)\\b"
        ).find(t)
        if (earlierMin != null) {
            val n = earlierMin.groupValues[3].ifEmpty { earlierMin.groupValues[5] }
            ReminderParser.parseNumber(n)?.let { minutes ->
                if (minutes in 1..1440) {
                    return Command.EarlierReminder(reminderTarget(t), minutes)
                }
            }
        }

        // Cancel: "reminder cancel karo" / "gym wala band karo" / "cancel reminder"
        if (Regex("(?i)\\b(reminder|reminders|yaad)\\b.*\\b(cancel|band|khatam|hatao|remove)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(cancel|band|khatam|hatao|remove)\\b.*\\b(reminder|reminders|yaad)\\b").containsMatchIn(t)
        ) {
            return Command.CancelReminder(reminderTarget(t))
        }

        // ---- YouTube ----
        // "youtube play karo" / "video chalao" / "play kar"
        if (Regex("(?i)\\b(youtube|yt|video|song)\\b.*\\b(play|chalao|chala|chal|start|shuru)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(play|chalao|chala|chal)\\b.*\\b(youtube|yt|video|song)\\b").containsMatchIn(t)
        ) {
            return Command.YoutubePlayPause(true)
        }
        // "youtube pause karo" / "video ruko" / "ruk ja"
        if (Regex("(?i)\\b(youtube|yt|video|song)\\b.*\\b(pause|ruko|ruk|stop|band)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(pause|ruko|ruk|stop)\\b.*\\b(youtube|yt|video|song)\\b").containsMatchIn(t)
        ) {
            return Command.YoutubePlayPause(false)
        }
        // Speed: "2x karo" / "double speed" / "speed 2x" / "1.5x"
        if (Regex("(?i)\\b(2x|2\\s*x|double speed|double|speed 2|speed 2x|do guna)\\b").containsMatchIn(t)) {
            return Command.YoutubeSpeed("2x")
        }
        if (Regex("(?i)\\b(1\\.5x|1\\.5\\s*x|1.5|sawa guna)\\b").containsMatchIn(t)) {
            return Command.YoutubeSpeed("1.5x")
        }
        if (Regex("(?i)\\b(0\\.5x|0\\.5|aadha|half speed|0.5)\\b").containsMatchIn(t)) {
            return Command.YoutubeSpeed("0.5x")
        }
        if (Regex("(?i)\\b(speed normal|normal speed|1x|1\\s*x)\\b").containsMatchIn(t)) {
            return Command.YoutubeSpeed("Normal")
        }

        // ---- Screen lock / unlock ----
        // "phone lock karo" / "screen lock kar do" / "lock kar"
        if (Regex("(?i)\\b(phone|screen|mobile)\\b.*\\b(lock|band|talwa)\\b.*\\b(karo|kar|karna|do)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(lock)\\b.*\\b(phone|screen|mobile|karo|kar|karna|do)\\b").containsMatchIn(t)
        ) {
            return Command.LockPhone
        }
        // "phone unlock karo" / "unlock kar do" / "anlock" / "khole"
        if (Regex("(?i)\\b(phone|screen|mobile)\\b.*\\b(unlock|kholo|khol|khole|band kholo)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(unlock|kholo|khol)\\b.*\\b(phone|screen|mobile|karo|kar|karna|do)\\b").containsMatchIn(t)
        ) {
            return Command.UnlockPhone
        }

        // ---- WhatsApp auto-reply ----
        if (Regex("(?i)\\b(auto ?reply|auto reply|autoreply)\\b.*\\b(on|chalu|shuru|enable)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(whatsapp|wa)\\b.*\\b(auto ?reply)\\b.*\\b(on|chalu|shuru)\\b").containsMatchIn(t)
        ) {
            return Command.AutoReplyOn
        }
        if (Regex("(?i)\\b(auto ?reply|autoreply)\\b.*\\b(off|band|bujhao|disable)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(whatsapp|wa)\\b.*\\b(auto ?reply)\\b.*\\b(off|band)\\b").containsMatchIn(t)
        ) {
            return Command.AutoReplyOff
        }
        val addTarget = Regex("(?i)\\b(auto ?reply|autoreply)\\b.*\\b(kisko|only|sirf|for|ko)\\b(.*)").find(t)
        if (addTarget != null) {
            val name = addTarget.groupValues[1].trim()
            if (name.isNotEmpty()) return Command.AutoReplyAdd(name)
        }
        val rmTarget = Regex("(?i)\\b(auto ?reply|autoreply)\\b.*\\b(hatao|remove|band)\\b.*\\b(kisko|only|sirf|for|ko)\\b(.*)").find(t)
        if (rmTarget != null) {
            val name = rmTarget.groupValues[1].trim()
            if (name.isNotEmpty()) return Command.AutoReplyRemove(name)
        }

        // ---- Device health / status ----
        if (Regex("(?i)\\b(battery|battery status|battery kitni|charge)\\b").containsMatchIn(t)) {
            return Command.BatteryStatus
        }
        if (Regex("(?i)\\b(device status|device health|phone details|health check|device info|phone status)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(status|health)\\b.*\\b(phone|device|mobile)\\b").containsMatchIn(t)
        ) {
            return Command.DeviceStatus
        }

        // ---- Self screen vision ----
        if (Regex("(?i)\\b(screen dekh|screen dekho|dekh screen|look at screen|screen kya dikh raha|screen dekho)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(dekh|dekho)\\b.*\\b(screen|screen par)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(ye kya dikh|kya dikh rha|kya dikh raha|abhi kya dikh|ye kya hai|kya hai screen par)\\b").containsMatchIn(t)
        ) {
            return Command.LookScreen
        }

        // ---- Autonomous agent ----
        val agent = Regex("(?i)\\b(agent|autopilot|aik chalao|task karo|kaam kar do|khud kar|screen task)\\b(.*)").find(t)
        if (agent != null) {
            val goal = agent.groupValues[1].trim().trimStart(':', '-', ' ', '—')
            if (goal.isNotEmpty()) return Command.AgentTask(goal)
        }

        // Memory: "yaad rakho mera naam Kartik hai" / "remember that ..."
        val remember = Regex(
            "(?i)\\b(yaad rakho|yaad rakhna|yaad rakh|remember that|remember|note karo|note kar)\\b(.*)"
        ).find(t)
        if (remember != null) {
            val fact = remember.groupValues[2].trim().trimStart(',', '.', '-', ':').trim()
                .replace(Regex("(?i)^(ki|ke|ka|that)\\s+"), "")
            if (fact.length >= 2) return Command.Remember(fact)
        }

        // Forget: "bhool jao ki ...", "forget ..."
        val forget = Regex("(?i)\\b(bhool jao|bhool ja|forget)\\b(.*)").find(t)
        if (forget != null) {
            val q = forget.groupValues[2].trim().replace(Regex("(?i)^(ki|ke|ka|that)\\s+"), "")
            if (q.length >= 2) return Command.Forget(q)
        }

        // Recall: "tujhe kya yaad hai", "what do you remember"
        if (Regex("(?i)\\b(kya yaad|yaad hai kya|what do you remember|memory batao|memory dikhao)\\b").containsMatchIn(t)) {
            return Command.RecallMemory
        }

        // "mujhe call karo" = in-app Aero call (NOT a SIM call)
        if (Regex("(?i)\\b(call me|mujhe call|mujhko call|phone karo mujhe|mujhe phone karo)\\b").containsMatchIn(t) ||
            Regex("(?i)\\bcall\\s*(karo|karna|kar do|kar de)\\b").containsMatchIn(t) && t.contains("mujh")
        ) {
            val parsed = ReminderParser.parse(t)
            val at = parsed?.atMillis?.takeIf { it > System.currentTimeMillis() + 10_000L }
            return Command.CallMe(at)
        }

        // Camera
        if (Regex("(?i)\\b(front camera|selfie camera|camera front)\\b").containsMatchIn(t)) {
            return Command.CameraLive("front")
        }
        if (Regex("(?i)\\b(back camera|camera back|rear camera)\\b").containsMatchIn(t)) {
            return Command.CameraLive("back")
        }
        if (Regex("(?i)\\b(camera|camera kholo|camera on|live camera)\\b").containsMatchIn(t)) {
            return Command.CameraLive("back")
        }
        if (Regex("(?i)\\b(camera band|camera off|band karo camera)\\b").containsMatchIn(t)) {
            return Command.CameraStop
        }

        // WiFi / data settings
        if (Regex("(?i)\\b(wifi|wi-fi)\\b.*\\b(on|off|chalu|band)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(on|off|chalu|band)\\b.*\\b(wifi|wi-fi)\\b").containsMatchIn(t)
        ) return Command.WifiSettings
        if (Regex("(?i)\\b(mobile data|data|internet)\\b.*\\b(on|off|chalu|band)\\b").containsMatchIn(t) ||
            Regex("(?i)\\b(on|off|chalu|band)\\b.*\\b(mobile data|data|internet)\\b").containsMatchIn(t)
        ) return Command.DataSettings

        val whats = whatsAppMsgRe.find(t)
        if (whats != null) {
            val rest = whats.groupValues[2].trim()
            if (rest.isNotEmpty()) {
                // rest starts with target, message after first comma / "ye"/"ki"/"ko"?
                val parts = rest.split(Regex("[,،]|\\b(?:ye|ki|ke|ko|that|say|bolo)\\b"), limit = 2)
                val target = parts.getOrNull(0)?.trim().orEmpty()
                val message = parts.getOrNull(1)?.trim().orEmpty()
                if (target.isNotEmpty()) {
                    return if (message.isNotEmpty()) {
                        Command.WhatsAppMessage(target, message)
                    } else {
                        // fallback: treat whole rest as target, empty message
                        Command.WhatsAppMessage(target, "")
                    }
                }
            }
        }

        val video = videoCallRe.find(t)
        if (video != null) {
            val target = video.groupValues[1].trim()
            if (target.isNotEmpty()) return Command.WhatsAppCall(target, video = true)
        }

        val voice = voiceCallRe.find(t)
        if (voice != null) {
            val target = voice.groupValues[1].trim()
            if (target.isNotEmpty()) return Command.WhatsAppCall(target, video = false)
        }

        val call = callRe.find(t)
        if (call != null) {
            val target = call.groupValues[1].trim().trimStart(' ', 'k', 'o').trim()
            if (target.isNotEmpty()) return Command.Call(target)
        }
        val type = typeRe.find(t)
        if (type != null) {
            val target = type.groupValues[1].trim()
            if (target.isNotEmpty()) return Command.Type(target)
        }

        val click = clickRe.find(t)
        if (click != null) {
            val target = click.groupValues[1].trim()
            if (target.isNotEmpty()) return Command.Click(target)
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

    /** Extracts a reminder name/target from the sentence (e.g. "gym wala"). */
    private fun reminderTarget(text: String): String {
        val t = text.trim().lowercase()
        // Try patterns: "gym wala reminder", "reminder gym wala", "gym wali"
        val m = Regex(
            "(?i)\\b(reminder|reminders|yaad)\\b.*?\\b([a-z0-9]+(?:\\s+[a-z0-9]+){0,4})\\b.*?(wala|wali|wale|ka|ki|ke)?\\b"
        ).find(t) ?: return ""
        val candidate = m.groupValues[2].trim()
        val stopWords = setOf(
            "cancel", "band", "delete", "remove", "hatao", "khatam", "dikhao", "dikha",
            "batao", "bata", "list", "clear", "saaf", "delay", "aage", "baad", "jldi",
            "jaldi", "pehle", "abhi", "now", "fire", "karo", "karna", "kar", "do",
            "minute", "minutes", "mins", "min", "ghanta", "ghante", "hour", "hours",
            "reminder", "reminders", "yaad", "wala", "wali", "wale"
        )
        val words = candidate.split(Regex("\\s+")).filter {
            it.isNotBlank() && it.length >= 2 && it !in stopWords
        }
        return words.joinToString(" ")
    }

    /**
     * Attempts to run a parsed command. Returns the spoken result string.
     */
    fun run(ctx: Context, command: Command): String = when (command) {
        is Command.Open -> openApp(ctx, command.target)
        is Command.Close -> closeApp(ctx, command.target)
        is Command.ClearRecents -> clearRecents(ctx)
        is Command.Call -> DeviceActions.call(ctx, command.target)
        is Command.CutCall -> DeviceActions.endCall(ctx)
        is Command.WhatsAppMessage -> DeviceActions.whatsAppMessage(ctx, command.target, command.message)
        is Command.WhatsAppCall -> DeviceActions.whatsAppCall(ctx, command.target, command.video)
        is Command.Click -> DeviceActions.clickByText(ctx, command.target)
        is Command.Type -> DeviceActions.typeText(ctx, command.target)
        is Command.Home -> DeviceActions.goHome(ctx)
        is Command.Back -> DeviceActions.goBack(ctx)
        is Command.ScrollDown -> if (AeroAccessibilityService.requestScroll(true)) "Scrolling down." else "I need accessibility permission to scroll."
        is Command.ScrollUp -> if (AeroAccessibilityService.requestScroll(false)) "Scrolling up." else "I need accessibility permission to scroll."
        is Command.ReadNotifications -> {
            AeroAccessibilityService.requestNotificationsDump { dump ->
                AeroState.liveClient?.sendContextText("Notifications: $dump")
            }
            "Here are your recent notifications."
        }
        is Command.ClearNotifications -> if (AeroAccessibilityService.requestClearNotifications()) "Clearing notifications." else "I need accessibility permission to clear notifications."
        is Command.TorchOn -> DeviceControl.torch(ctx, true)
        is Command.TorchOff -> DeviceControl.torch(ctx, false)
        is Command.BluetoothOn -> DeviceControl.bluetooth(ctx, true)
        is Command.BluetoothOff -> DeviceControl.bluetooth(ctx, false)
        is Command.Brightness -> DeviceControl.brightness(ctx, command.level)
        is Command.Screenshot -> DeviceControl.screenshot(ctx)
        is Command.WifiSettings -> DeviceControl.openWifiSettings(ctx)
        is Command.DataSettings -> DeviceControl.openDataSettings(ctx)
        is Command.CameraLive -> DeviceActions.cameraLive(ctx, command.lens)
        is Command.CameraStop -> DeviceActions.cameraStop(ctx)
        is Command.VolumeUp -> DeviceControl.volumeUp(ctx)
        is Command.VolumeDown -> DeviceControl.volumeDown(ctx)
        is Command.VolumeSet -> DeviceControl.volume(ctx, command.percent)
        is Command.Reminder -> {
            val r = ReminderStore.add(ctx, command.message, command.atMillis)
            ReminderScheduler.schedule(ctx, r)
            "Reminder set for ${java.text.SimpleDateFormat("EEE, d MMM h:mm a", java.util.Locale.getDefault()).format(command.atMillis)}: ${command.message}"
        }
        is Command.Remember -> {
            MemoryStore.add(ctx, command.fact)
            "Yaad rakh liya: ${command.fact}"
        }
        is Command.Forget -> {
            val removed = MemoryStore.forget(ctx, command.query)
            if (removed > 0) "Bhool gaya: ${command.query}" else "Mujhe aisa kuch yaad nahi tha: ${command.query}"
        }
        is Command.RecallMemory -> {
            val summary = MemoryStore.summary(ctx)
            AeroState.liveClient?.sendContextText("User's saved memory:\n$summary")
            summary
        }
        is Command.CallMe -> {
            val at = command.atMillis
            if (at == null) {
                ReminderScheduler.ringNow(ctx, "Aero: tumne call karne ko kaha tha.")
                "Calling you now."
            } else {
                val r = ReminderStore.add(ctx, "Aero call", at)
                ReminderScheduler.schedule(ctx, r)
                "Aero will call you at ${java.text.SimpleDateFormat("EEE, d MMM h:mm a", java.util.Locale.getDefault()).format(at)}."
            }
        }
        is Command.ListReminders -> {
            val list = ReminderManager.list(ctx)
            AeroState.liveClient?.sendContextText("User's reminders:\n$list")
            list
        }
        is Command.CancelReminder -> ReminderManager.cancel(ctx, command.query)
        is Command.DelayReminder -> ReminderManager.delay(ctx, command.query, command.minutes)
        is Command.EarlierReminder -> ReminderManager.earlier(ctx, command.query, command.minutes)
        is Command.FireReminderNow -> ReminderManager.fireNow(ctx, command.query)
        is Command.ClearAllReminders -> ReminderManager.clearAll(ctx)
        is Command.YoutubePlayPause -> YouTubeControl.mediaPlayPause(ctx, command.play)
        is Command.YoutubeSpeed -> {
            YouTubeControl.setSpeed(ctx, command.label)
            "${command.label} speed laga raha hoon… (YouTube khulna chahiye)"
        }
        is Command.LockPhone -> ScreenControl.lock(ctx)
        is Command.UnlockPhone -> ScreenControl.unlock(ctx)
        is Command.AutoReplyOn -> {
            WhatsAppAutoReply.setEnabled(ctx, true)
            val targets = WhatsAppAutoReply.allowlist(ctx)
            if (targets.isEmpty()) "WhatsApp auto-reply ON (sabke liye)."
            else "WhatsApp auto-reply ON — sirf: ${targets.joinToString(", ")}."
        }
        is Command.AutoReplyOff -> {
            WhatsAppAutoReply.setEnabled(ctx, false)
            "WhatsApp auto-reply OFF."
        }
        is Command.AutoReplyAdd -> {
            WhatsAppAutoReply.addTarget(ctx, command.name)
            "Auto-reply sirf ${command.name} ke liye."
        }
        is Command.AutoReplyRemove -> {
            WhatsAppAutoReply.removeTarget(ctx, command.name)
            "${command.name} auto-reply se hata diya."
        }
        is Command.BatteryStatus -> {
            val b = DeviceHealth.battery(ctx)
            "Battery ${b.level}%, ${if (b.charging) "charging" else "not charging"}, temperature ${b.tempC}°C."
        }
        is Command.DeviceStatus -> DeviceHealth.summary(ctx)
        is Command.LookScreen -> {
            val ok = ScreenShareHelper.ensureScreenShare(ctx)
            if (ok) {
                "Screen dekh raha hoon. System consent dialog aaye to Allow karo."
            } else {
                "Screen share start nahi ho paya. Settings se permission check karo."
            }
        }
        is Command.AgentTask -> ScreenAgent.start(ctx, command.goal)
    }
}
