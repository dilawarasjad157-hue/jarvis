package com.jarvis.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Watches chat notifications. With auto-reply ON, a WhatsApp message gets an answer written by
 * Gemini and sent through the notification's own "Reply" button, so WhatsApp never has to open.
 */
class JarvisNotifier : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gemini: Gemini? = null
    private val lastSeen = HashMap<String, Pair<String, Long>>()
    private val lastReply = HashMap<String, Long>()
    private val hourly = ArrayList<Long>()

    private val chatApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
        "com.google.android.apps.messaging", "com.samsung.android.messaging"
    )
    private val whatsapp = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onCreate() {
        super.onCreate()
        gemini = Gemini(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val n = s.notification ?: return
        val pkg = s.packageName
        if (pkg !in chatApps) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return
        if (title == "WhatsApp" || title == "WhatsApp Business") return           // "3 new messages" summary
        if (Regex("""^\d+ (new )?messages?""").containsMatchIn(text)) return
        val group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        // In groups the title is "Group: Person"; keep the group name as the chat key.
        val sender = title

        val now = System.currentTimeMillis()
        val prev = lastSeen[sender]
        if (prev != null && prev.first == text && now - prev.second < 30_000) return
        lastSeen[sender] = text to now

        val replyAction = n.actions?.firstOrNull { a ->
            a.remoteInputs != null && a.remoteInputs.isNotEmpty() &&
                (a.title?.toString()?.contains("reply", ignoreCase = true) ?: true)
        } ?: n.actions?.firstOrNull { it.remoteInputs != null && it.remoteInputs.isNotEmpty() }

        Inbox.add(Msg(sender, text, pkg, now, group), replyAction)

        if (pkg in whatsapp && replyAction != null && Prefs.autoReply(this)) maybeAutoReply(sender, text, group, replyAction)
    }

    private fun maybeAutoReply(sender: String, text: String, group: Boolean, action: Notification.Action) {
        if (group && !Prefs.arGroups(this)) return
        val only = Prefs.arOnly(this).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (only.isNotEmpty() && only.none { sender.lowercase().contains(it) }) return

        val now = System.currentTimeMillis()
        val last = lastReply[sender] ?: 0L
        if (now - last < 15_000) return
        hourly.removeAll { now - it > 3_600_000 }
        if (hourly.size >= 40) return            // safety brake
        hourly.add(now)
        lastReply[sender] = now

        val g = gemini ?: return
        scope.launch {
            try {
                delay(Random.nextLong(2500, 6000))     // feels human, and lets the user answer first
                val owner = Prefs.ownerName(applicationContext).ifBlank { "the phone's owner" }
                val system = """
You are writing a WhatsApp reply on behalf of $owner, who is busy right now.
Owner's instructions: ${Prefs.arInstruction(applicationContext)}
Rules:
- Write like a real person texting: 1-2 short sentences, same language and script as the incoming message (Roman Urdu stays Roman Urdu, English stays English).
- Never share passwords, OTP codes, card/bank details or addresses, never agree to send money, and never make firm promises. For such things say you will get back personally.
- If the message needs no reply (just "ok", thanks, a sticker or emoji), output exactly: SKIP
- Output only the message text, nothing else.
""".trimIndent()
                val history = Inbox.history(sender)
                val prompt = "Conversation so far:\n$history\n\nNew message from $sender: $text\n\nWrite the reply."
                var reply = g.text(system, prompt).trim().trim('"')
                if (reply.isEmpty() || reply.equals("SKIP", ignoreCase = true)) return@launch
                if (Prefs.arSign(applicationContext)) reply += "  (Jarvis auto-reply)"
                if (Inbox.send(applicationContext, action, reply)) {
                    Inbox.addMine(sender, reply)
                    Bus.post(false, "Auto-reply → $sender: $reply")
                }
            } catch (e: Exception) {
                Bus.post(false, "Auto-reply fail ($sender): ${e.message?.take(80)}")
            }
        }
    }
}
