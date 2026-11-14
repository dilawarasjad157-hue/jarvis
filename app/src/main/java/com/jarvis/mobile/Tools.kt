package com.jarvis.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telephony.SmsManager
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Used to turn 03xx numbers into +92 numbers for WhatsApp. Change if you are not in Pakistan. */
private const val DEFAULT_COUNTRY_CODE = "92"

/** Buttons that deserve a spoken "are you sure?" before JARVIS taps them. */
private val RISKY = Regex(
    """\b(send|pay|transfer|delete|remove|buy|purchase|order|confirm|submit|post|bhej|uninstall|erase|reset|log ?out|sign ?out)\b""",
    RegexOption.IGNORE_CASE
)

private val YES = setOf("yes", "yeah", "yep", "yup", "haan", "han", "ji", "ok", "okay", "confirm", "sure", "kar", "kardo", "theek")
private val NO = setOf("no", "nope", "nahi", "nahin", "na", "cancel", "stop", "mat", "dont", "don't")

class Tools(private val svc: JarvisService, private val voice: Voice) {

    // ---------------------------------------------------------------- declarations

    private fun decl(
        name: String,
        desc: String,
        props: Map<String, Pair<String, String>> = emptyMap(),
        optional: Set<String> = emptySet()
    ): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            for ((k, v) in props) {
                p.put(k, JSONObject().put("type", v.first).put("description", v.second))
            }
            o.put(
                "parameters",
                JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(props.keys.filter { it !in optional }))
            )
        }
        return o
    }

    fun declarations(): JSONArray {
        val s = "string"
        val n = "integer"
        val b = "boolean"
        val list = JSONArray()
        list.put(decl("open_app", "Open an installed app by name, e.g. WhatsApp, Chrome, Camera, Settings, YouTube.", mapOf("name" to (s to "App name"))))
        list.put(decl("call_contact", "Phone call. 'who' is a contact name or a phone number. The user is asked to confirm.", mapOf("who" to (s to "Contact name or number"))))
        list.put(decl("send_sms", "Send a normal SMS text. The user is asked to confirm.", mapOf("to" to (s to "Contact name or number"), "message" to (s to "Text to send"))))
        list.put(decl("open_whatsapp_chat", "Open a WhatsApp chat with a contact or number, with the message already typed. Afterwards use read_screen and tap_text 'Send'.", mapOf("to" to (s to "Contact name or number"), "message" to (s to "Message to pre-type"))))
        list.put(decl("set_alarm", "Set an alarm at a 24-hour clock time.", mapOf("hour" to (n to "Hour 0-23"), "minute" to (n to "Minute 0-59"), "label" to (s to "Alarm label, may be empty"))))
        list.put(decl("set_timer", "Start a countdown timer.", mapOf("seconds" to (n to "Length in seconds"))))
        list.put(decl("flashlight", "Turn the flashlight on or off.", mapOf("on" to (b to "true for on"))))
        list.put(decl("set_volume", "Set media volume.", mapOf("percent" to (n to "0 to 100"))))
        list.put(decl("open_url", "Open a web address in the browser.", mapOf("url" to (s to "URL"))))
        list.put(decl("web_search", "Search Google in the browser.", mapOf("query" to (s to "Search text"))))
        list.put(decl("open_settings", "Open a Settings page: wifi, bluetooth, display, sound, battery, apps, location, airplane, or main.", mapOf("page" to (s to "Which page"))))
        list.put(decl("phone_status", "Battery level, charging state, current time and the app on screen."))
        list.put(decl("read_screen", "Read what is on the screen now as a numbered list. Always do this before tapping inside an app."))
        list.put(decl("tap_text", "Tap the on-screen item whose text matches.", mapOf("text" to (s to "Visible text or label"))))
        list.put(decl("tap_index", "Tap an item by its number from the latest read_screen.", mapOf("index" to (n to "Item number"))))
        list.put(decl("type_text", "Type into the focused text box (or the first one on screen).", mapOf("text" to (s to "Text to type"))))
        list.put(decl("scroll", "Scroll the screen.", mapOf("direction" to (s to "down or up"))))
        list.put(decl("press_button", "Press a system button: back, home, recents, notifications, quick_settings, lock, screenshot.", mapOf("button" to (s to "Which button"))))
        list.put(decl("read_notifications", "Recent incoming chat messages (WhatsApp, SMS, Telegram...) with sender and time."))
        list.put(decl("whatsapp_reply", "Reply to a person who recently messaged on WhatsApp, directly from the notification, without opening the app.", mapOf("to" to (s to "Sender name as shown in read_notifications"), "message" to (s to "Reply text"))))
        list.put(decl("set_auto_reply", "Turn automatic WhatsApp replies on or off. When on, JARVIS answers new WhatsApp messages by itself.", mapOf("enabled" to (b to "true to turn on"), "instruction" to (s to "How to answer, if the user said so"), "only_for" to (s to "Comma separated names to restrict to; empty for everyone")), setOf("instruction", "only_for")))
        list.put(decl("find_files", "Search the phone's storage for files whose name contains the words. Empty query lists the newest files.", mapOf("query" to (s to "Words from the file name"))))
        list.put(decl("open_file", "Open a file by the exact path returned by find_files.", mapOf("path" to (s to "Full file path"))))
        list.put(decl("media_control", "Control music/video playback: play_pause, next, previous, stop.", mapOf("action" to (s to "play_pause, next, previous or stop"))))
        list.put(decl("wait", "Wait for a screen to load.", mapOf("seconds" to (n to "1 to 5"))))
        return JSONArray().put(JSONObject().put("functionDeclarations", list))
    }

    // ---------------------------------------------------------------- dispatcher

    suspend fun run(name: String, a: JSONObject): String = when (name) {
        "open_app" -> openApp(a.optString("name"))
        "call_contact" -> call(a.optString("who"))
        "send_sms" -> sms(a.optString("to"), a.optString("message"))
        "open_whatsapp_chat" -> whatsapp(a.optString("to"), a.optString("message"))
        "set_alarm" -> alarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"))
        "set_timer" -> timer(a.optInt("seconds", 0))
        "flashlight" -> torch(a.optBoolean("on", true))
        "set_volume" -> volume(a.optInt("percent", 50))
        "open_url" -> openUrl(a.optString("url"))
        "web_search" -> webSearch(a.optString("query"))
        "open_settings" -> openSettings(a.optString("page"))
        "phone_status" -> status()
        "read_screen" -> svc.readScreen()
        "tap_text" -> tapText(a.optString("text"))
        "tap_index" -> tapIndex(a.optInt("index", -1))
        "type_text" -> if (svc.typeText(a.optString("text"))) "Typed." else "No text box found. Tap one first."
        "scroll" -> if (svc.swipe(a.optString("direction").lowercase() != "up")) done() else "Scroll failed."
        "press_button" -> if (svc.press(a.optString("button"))) done() else "Could not press that button."
        "read_notifications" -> Inbox.summary(10)
        "whatsapp_reply" -> waReply(a.optString("to"), a.optString("message"))
        "set_auto_reply" -> autoReply(a.optBoolean("enabled", true), a.optString("instruction"), a.optString("only_for"))
        "find_files" -> findFiles(a.optString("query"))
        "open_file" -> Files.open(svc, a.optString("path")) ?: "Opened."
        "media_control" -> media(a.optString("action"))
        "wait" -> {
            delay(a.optInt("seconds", 1).coerceIn(1, 5) * 1000L)
            "Waited."
        }
        else -> "Unknown tool: $name"
    }

    private suspend fun done(): String {
        delay(500)
        return "Done."
    }

    // ---------------------------------------------------------------- helpers

    /** Asks out loud and listens for yes / no. Anything unclear counts as no. */
    private suspend fun confirm(question: String): Boolean {
        voice.speak("$question Say yes or no.")
        delay(250)
        val answer = voice.listen() ?: return false
        val words = answer.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.any { it in NO }) return false
        return words.any { it in YES }
    }

    private fun granted(p: String) = svc.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        svc.startActivity(i)
    }

    private fun lookup(who: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val c = svc.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$who%"),
            null
        ) ?: return null
        c.use {
            if (it.moveToFirst()) return it.getString(0) to it.getString(1)
        }
        return null
    }

    /** Name or number in, (display name, number) out. */
    private fun resolve(who: String): Pair<String, String>? {
        val digits = who.count { it.isDigit() }
        if (digits >= 5) return who to who.filter { it.isDigit() || it == '+' }
        return lookup(who.trim())
    }

    // ---------------------------------------------------------------- tools

    private fun waReply(to: String, message: String): String {
        if (message.isBlank()) return "The message is empty."
        val (name, action) = Inbox.findAction(to)
            ?: return "No recent message from '$to' with a reply button. Use open_whatsapp_chat instead."
        return if (Inbox.send(svc, action, message)) {
            Inbox.addMine(name, message)
            "Replied to $name."
        } else {
            "That notification is gone, so I can't reply from it. Use open_whatsapp_chat."
        }
    }

    private fun autoReply(on: Boolean, instruction: String, only: String): String {
        Prefs.setAutoReply(svc, on)
        if (instruction.isNotBlank()) Prefs.setArInstruction(svc, instruction)
        if (on) Prefs.setArOnly(svc, only)
        val listener = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(svc).contains(svc.packageName)
        return when {
            !on -> "Auto-reply is now OFF."
            !listener -> "Auto-reply is ON, but Notification access is not enabled yet. Ask the user to enable it in the Jarvis setup screen."
            else -> "Auto-reply is now ON" + (if (only.isNotBlank()) " for $only." else " for everyone.")
        }
    }

    private fun findFiles(q: String): String {
        val ok = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!ok) return "File access is not granted. Ask the user to enable All files access in the Jarvis setup screen."
        val r = Files.find(q)
        return if (r.isEmpty()) "No files found for '$q'." else r.joinToString("\n") { Files.describe(it) }
    }

    private fun media(action: String): String {
        val code = when (action.lowercase().trim()) {
            "play_pause", "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Unknown media action."
        }
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return "Done."
    }

    private suspend fun openApp(name: String): String {
        val pm = svc.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.trim().lowercase()
        if (q.isEmpty()) return "No app name given."
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { q.contains(it.loadLabel(pm).toString().lowercase()) && it.loadLabel(pm).length > 2 }
            ?: return "I couldn't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "That app can't be launched."
        launch(intent)
        delay(1500)
        return "Opened ${hit.loadLabel(pm)}."
    }

    private suspend fun call(who: String): String {
        val (name, number) = resolve(who) ?: return "Contact '$who' not found (or contacts permission is missing)."
        if (!confirm("Call $name?")) return "The user declined the call."
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
        return "Calling $name."
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun sms(to: String, message: String): String {
        val (name, number) = resolve(to) ?: return "Contact '$to' not found (or contacts permission is missing)."
        if (message.isBlank()) return "The message is empty."
        if (!confirm("Send SMS to $name saying: $message?")) return "The user declined to send the SMS."
        if (!granted(Manifest.permission.SEND_SMS)) return "SMS permission is not granted."
        val sm = if (Build.VERSION.SDK_INT >= 31) svc.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(message), null, null)
        return "SMS sent to $name."
    }

    private suspend fun whatsapp(to: String, message: String): String {
        val (name, raw) = resolve(to) ?: return "Contact '$to' not found (or contacts permission is missing)."
        var num = raw.filter { it.isDigit() || it == '+' }
        num = when {
            num.startsWith("+") -> num.substring(1)
            num.startsWith("00") -> num.substring(2)
            num.startsWith("0") -> DEFAULT_COUNTRY_CODE + num.substring(1)
            else -> num
        }
        val uri = Uri.parse("https://wa.me/$num?text=" + Uri.encode(message))
        try {
            launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, uri))
        }
        delay(2500)
        return "WhatsApp chat with $name is open with the message typed. Now read_screen and tap the Send button."
    }

    private suspend fun alarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "Invalid time."
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        delay(500)
        return "Alarm set for %02d:%02d.".format(hour, minute)
    }

    private suspend fun timer(seconds: Int): String {
        if (seconds <= 0) return "Invalid length."
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        delay(500)
        return "Timer started for $seconds seconds."
    }

    private fun torch(on: Boolean): String {
        val cm = svc.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun volume(percent: Int): String {
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (percent.coerceIn(0, 100) * max / 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        return "Volume set to ${percent.coerceIn(0, 100)} percent."
    }

    private fun openUrl(url: String): String {
        if (url.isBlank()) return "No address given."
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return "Opened $u."
    }

    private fun webSearch(query: String): String {
        if (query.isBlank()) return "No search text."
        try {
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
        return "Searching for $query."
    }

    private fun openSettings(page: String): String {
        val action = when (page.lowercase().trim()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        return "Opened settings. Use read_screen and tap_text to change a switch."
    }

    private fun status(): String {
        val bi = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val time = SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.getDefault()).format(Date())
        return "Battery $pct percent${if (charging) ", charging" else ""}. Time: $time. App on screen: ${svc.currentPkg}."
    }

    private suspend fun tapText(text: String): String {
        if (text.isBlank()) return "No text given."
        if (RISKY.containsMatchIn(text) && !confirm("I am about to tap $text. Shall I?")) {
            return "The user declined."
        }
        return if (svc.clickByText(text)) {
            delay(700)
            "Tapped '$text'."
        } else {
            "Could not find '$text' on screen. Use read_screen to see what is there."
        }
    }

    private suspend fun tapIndex(index: Int): String {
        val label = svc.itemLabel(index) ?: return "No item $index. Use read_screen first."
        if (RISKY.containsMatchIn(label) && !confirm("I am about to tap $label. Shall I?")) {
            return "The user declined."
        }
        return if (svc.tapItem(index)) {
            delay(700)
            "Tapped item $index."
        } else {
            "Tap failed."
        }
    }
}
