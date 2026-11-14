package com.jarvis.mobile

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Msg(val sender: String, val text: String, val pkg: String, val time: Long, val group: Boolean)

/** Remembers recent chat notifications and the "Reply" button of each one so JARVIS can answer without opening the app. */
object Inbox {
    private val recent = ArrayList<Msg>()
    private val actions = HashMap<String, Notification.Action>()
    private val convo = HashMap<String, ArrayList<String>>()

    @Synchronized
    fun add(m: Msg, reply: Notification.Action?) {
        recent.add(m)
        while (recent.size > 30) recent.removeAt(0)
        if (reply != null) actions[m.sender.lowercase()] = reply
        val c = convo.getOrPut(m.sender.lowercase()) { ArrayList() }
        c.add("${m.sender}: ${m.text}")
        while (c.size > 8) c.removeAt(0)
    }

    @Synchronized
    fun addMine(sender: String, text: String) {
        val c = convo.getOrPut(sender.lowercase()) { ArrayList() }
        c.add("Me: $text")
        while (c.size > 8) c.removeAt(0)
    }

    @Synchronized
    fun history(sender: String): String = convo[sender.lowercase()]?.joinToString("\n") ?: ""

    @Synchronized
    fun summary(limit: Int): String {
        if (recent.isEmpty()) return "No recent messages."
        val f = SimpleDateFormat("h:mm a", Locale.getDefault())
        return recent.takeLast(limit).joinToString("\n") {
            "[${f.format(Date(it.time))}] ${it.sender}${if (it.group) " (group)" else ""}: ${it.text.take(200)}"
        }
    }

    @Synchronized
    fun findAction(who: String): Pair<String, Notification.Action>? {
        val q = who.lowercase().trim()
        if (q.isEmpty()) return null
        val key = actions.keys.firstOrNull { it == q } ?: actions.keys.firstOrNull { it.contains(q) || q.contains(it) }
        ?: return null
        val a = actions[key] ?: return null
        val name = recent.lastOrNull { it.sender.lowercase() == key }?.sender ?: key
        return name to a
    }

    fun send(ctx: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        val intent = Intent()
        val b = Bundle()
        for (r in inputs) b.putCharSequence(r.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, b)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }
}
