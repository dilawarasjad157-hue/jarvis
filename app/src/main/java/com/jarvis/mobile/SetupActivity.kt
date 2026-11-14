package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Setup: permissions as status cards, Gemini key, voice language and WhatsApp auto-reply controls. */
class SetupActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val refreshers = ArrayList<() -> Unit>()
    private lateinit var list: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var barDone: View
    private lateinit var barRest: View
    private val checks = ArrayList<() -> Boolean>()

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun corePerms() = listOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS
    )

    private fun filesOk(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else has(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun notifOk(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val pad = Ui.dp(this, 16)

        val root = FrameLayout(this)
        root.addView(Backdrop(this), FrameLayout.LayoutParams(MATCH, MATCH))
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(pad, pad, pad, pad * 2)
        scroll.addView(list)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        val t = Ui.text(this, "SYSTEM SETUP", 20f, Ui.CYAN, true)
        t.letterSpacing = 0.25f
        t.setShadowLayer(18f, 0f, 0f, Ui.CYAN)
        list.addView(t)
        progressText = Ui.text(this, "", 12f, Ui.DIM)
        progressText.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6))
        list.addView(progressText)

        val bar = LinearLayout(this)
        barDone = View(this)
        barDone.background = Ui.round(this, Ui.CYAN, 4f)
        barRest = View(this)
        barRest.background = Ui.round(this, Color.argb(60, 120, 220, 255), 4f)
        bar.addView(barDone, LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1f))
        bar.addView(barRest, LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1f))
        list.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        card(
            "1 · Mic, Calls, SMS, Contacts",
            "Awaaz sunne, call karne, SMS bhejne aur contacts dhoondne ke liye.",
            { corePerms().all { has(it) } }, "Allow karein"
        ) { requestPermissions(corePerms().toTypedArray(), 1) }

        card(
            "2 · Restricted settings (Android 13+)",
            "App info → oopar 3 dots (⋮) → \"Allow restricted settings\". Iske baghair Accessibility/Notification access ON nahi hoti. Option na dikhe to chhor dein.",
            null, "App info kholein"
        ) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }

        card(
            "3 · Accessibility — phone control",
            "J.A.R.V.I.S. → ON. Yehi ijazat hai jis se wo screen dekh kar tap, type aur scroll karta hai.",
            { JarvisService.instance != null }, "Accessibility kholein"
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        card(
            "4 · Notification access — WhatsApp auto-reply",
            "J.A.R.V.I.S. → ON. Is se wo WhatsApp messages dekh kar aap ki taraf se reply kar sakta hai.",
            { notifOk() }, "Notification access kholein"
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        card(
            "5 · All files access — files kholna",
            "\"Phone mein falan file kholo\" kehne ke liye.",
            { filesOk() }, "Allow karein"
        ) {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
            }
        }

        // ---- Gemini + voice
        val box = cardBox()
        val head = Ui.text(this, "6 · Dimaag (Gemini) aur awaaz", 15f, Ui.TEXT, true)
        box.addView(head)
        val keyBox = Ui.input(this, "Gemini API key", Prefs.apiKey(this))
        val nameBox = Ui.input(this, "Aap ka naam (auto-reply ke liye)", Prefs.ownerName(this))
        val langBox = Ui.input(this, "Bolne ki zubaan: en-US, ur-PK, hi-IN", Prefs.lang(this))
        for (e in listOf(keyBox, nameBox, langBox)) {
            e.setSingleLine()
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = Ui.dp(this, 8)
            box.addView(e, lp)
        }
        val save = Ui.button(this, "Save") {
            Prefs.setApiKey(this, keyBox.text.toString())
            Prefs.setOwnerName(this, nameBox.text.toString())
            Prefs.setLang(this, langBox.text.toString())
            Toast.makeText(this, "Saved ✔", Toast.LENGTH_SHORT).show()
            refreshAll()
        }
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = Ui.dp(this, 10)
        box.addView(save, slp)
        registerBox(box) { Prefs.apiKey(this).isNotBlank() }

        // ---- auto reply
        val ar = cardBox()
        ar.addView(Ui.text(this, "7 · WhatsApp auto-reply", 15f, Ui.TEXT, true))
        ar.addView(Ui.text(this, "ON ho to naya WhatsApp message aane par Jarvis aap ki taraf se khud jawab deta hai. Awaaz se bhi keh sakte hain: \"msg aaye to reply kar dena\".", 12f, Ui.DIM))
        fun sw(label: String, on: Boolean, change: (Boolean) -> Unit): Switch {
            val s = Switch(this)
            s.text = label
            s.setTextColor(Ui.TEXT)
            s.isChecked = on
            s.setOnCheckedChangeListener { _, b -> change(b) }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = Ui.dp(this, 10)
            ar.addView(s, lp)
            return s
        }
        val swOn = sw("Auto-reply chalu", Prefs.autoReply(this)) { Prefs.setAutoReply(this, it) }
        sw("Groups mein bhi reply karein", Prefs.arGroups(this)) { Prefs.setArGroups(this, it) }
        sw("Reply ke end mein \"(Jarvis auto-reply)\" likhein", Prefs.arSign(this)) { Prefs.setArSign(this, it) }
        val instr = Ui.input(this, "Jarvis ko hidayat: kaise jawab de?", Prefs.arInstruction(this))
        instr.minLines = 2
        instr.setSingleLine(false)
        val only = Ui.input(this, "Sirf in logon ko (naam, comma se). Khali = sab", Prefs.arOnly(this))
        only.setSingleLine()
        for (e in listOf(instr, only)) {
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = Ui.dp(this, 10)
            ar.addView(e, lp)
        }
        val saveAr = Ui.button(this, "Save auto-reply settings") {
            Prefs.setArInstruction(this, instr.text.toString())
            Prefs.setArOnly(this, only.text.toString())
            Toast.makeText(this, "Saved ✔", Toast.LENGTH_SHORT).show()
        }
        val alp = LinearLayout.LayoutParams(WRAP, WRAP)
        alp.topMargin = Ui.dp(this, 10)
        ar.addView(saveAr, alp)
        registerBox(ar) { Prefs.autoReply(this) && notifOk() }
        refreshers.add { swOn.isChecked = Prefs.autoReply(this) }

        val tip = Ui.text(
            this,
            "Tip: Quick Settings (oopar se neeche kheench kar) mein \"JARVIS\" tile add karein — kisi bhi screen se ek tap mein Jarvis sunna shuru kar deta hai.",
            12f, Ui.DIM
        )
        tip.setPadding(0, Ui.dp(this, 14), 0, 0)
        list.addView(tip)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
    }

    private fun cardBox(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        b.setPadding(p, p, p, p)
        b.background = Ui.round(this, Ui.GLASS, 16f, Color.argb(80, 25, 211, 232), 1f)
        return b
    }

    private fun registerBox(box: LinearLayout, check: () -> Boolean) {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = Ui.dp(this, 12)
        list.addView(box, lp)
        checks.add(check)
        refreshers.add {
            box.background = Ui.round(
                this, Ui.GLASS, 16f,
                if (check()) Color.argb(160, 61, 242, 160) else Color.argb(80, 25, 211, 232), 1f
            )
        }
    }

    private fun card(title: String, desc: String, ok: (() -> Boolean)?, btn: String, action: () -> Unit) {
        val box = cardBox()
        val top = LinearLayout(this)
        top.gravity = android.view.Gravity.CENTER_VERTICAL
        val tt = Ui.text(this, title, 15f, Ui.TEXT, true)
        top.addView(tt, LinearLayout.LayoutParams(0, WRAP, 1f))
        val badge = Ui.text(this, "", 11f, Ui.DIM, true)
        top.addView(badge)
        box.addView(top)
        val d = Ui.text(this, desc, 12f, Ui.DIM)
        d.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        box.addView(d)
        val b = Ui.button(this, btn, true) { action() }
        box.addView(b, LinearLayout.LayoutParams(WRAP, WRAP))

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = Ui.dp(this, 12)
        list.addView(box, lp)

        if (ok != null) checks.add(ok)
        refreshers.add {
            if (ok == null) {
                badge.text = "INFO"
                badge.setTextColor(Ui.DIM)
                box.background = Ui.round(this, Ui.GLASS, 16f, Color.argb(60, 120, 220, 255), 1f)
            } else if (ok()) {
                badge.text = "● ACTIVE"
                badge.setTextColor(Ui.GREEN)
                b.visibility = View.GONE
                box.background = Ui.round(this, Ui.GLASS, 16f, Color.argb(160, 61, 242, 160), 1f)
            } else {
                badge.text = "● REQUIRED"
                badge.setTextColor(Ui.AMBER)
                b.visibility = View.VISIBLE
                box.background = Ui.round(this, Ui.GLASS, 16f, Color.argb(110, 255, 176, 32), 1f)
            }
        }
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        val total = checks.size
        val done = checks.count { it() }
        progressText.text = "System readiness: $done / $total"
        (barDone.layoutParams as LinearLayout.LayoutParams).weight = done.coerceAtLeast(0).toFloat().coerceAtLeast(0.001f)
        (barRest.layoutParams as LinearLayout.LayoutParams).weight = (total - done).toFloat().coerceAtLeast(0.001f)
        barDone.requestLayout()
        barRest.requestLayout()
    }
}
