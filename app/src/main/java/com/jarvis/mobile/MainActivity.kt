package com.jarvis.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home screen: animated reactor, live conversation, quick commands and a typing box. */
class MainActivity : Activity() {
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val pad = Ui.dp(this, 16)

        val root = FrameLayout(this)
        root.addView(Backdrop(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad / 2)
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- header
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        val title = Ui.text(this, "J.A.R.V.I.S", 22f, Ui.CYAN, true)
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        title.letterSpacing = 0.3f
        title.setShadowLayer(22f, 0f, 0f, Ui.CYAN)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gear = Ui.text(this, "⚙  SETUP", 12f, Ui.CYAN, true)
        gear.letterSpacing = 0.1f
        gear.background = Ui.round(this, Color.argb(60, 25, 211, 232), 20f, Ui.CYAN, 1f)
        gear.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7))
        gear.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        header.addView(gear)
        col.addView(header)

        status = Ui.text(this, "", 12f, Ui.DIM, true)
        status.letterSpacing = 0.12f
        status.setPadding(0, Ui.dp(this, 6), 0, 0)
        status.setOnClickListener {
            if (JarvisService.instance == null) startActivity(Intent(this, SetupActivity::class.java))
        }
        col.addView(status)

        // ---- reactor
        val hud = HudView(this)
        hud.setOnClickListener {
            val svc = JarvisService.instance
            if (svc == null) {
                Toast.makeText(this, "Pehle Setup mein Accessibility ON karein", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SetupActivity::class.java))
            } else {
                svc.toggle()
            }
        }
        col.addView(hud, LinearLayout.LayoutParams(MATCH, Ui.dp(this, 270)))
        val hint = Ui.text(this, "Core ko dabayein aur bolein", 12f, Ui.DIM)
        hint.gravity = Gravity.CENTER
        col.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- conversation
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        scroll.addView(chat)
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---- quick chips
        val chips = LinearLayout(this)
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        listOf(
            "🔋 Battery" to "What is my battery level?",
            "💬 WhatsApp" to "Open WhatsApp and tell me if I have new messages",
            "🔦 Torch" to "Turn on the flashlight",
            "🤖 Auto-reply ON" to "Turn on WhatsApp auto reply for everyone",
            "⏹ Auto-reply OFF" to "Turn off WhatsApp auto reply",
            "📂 Latest file" to "Find my most recent file in Downloads and open it",
            "📸 Screenshot" to "Take a screenshot"
        ).forEach { (label, cmd) ->
            val chip = Ui.text(this, label, 12f, Ui.TEXT)
            chip.background = Ui.round(this, Ui.GLASS, 18f, Color.argb(110, 25, 211, 232), 1f)
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
            chip.setOnClickListener { runCommand(cmd) }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = Ui.dp(this, 8)
            chips.addView(chip, lp)
        }
        chipScroll.addView(chips)
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = Ui.dp(this, 4)
        col.addView(chipScroll, clp)

        // ---- input row
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        input = Ui.input(this, "Command likhein…", "")
        input.setSingleLine()
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sendBtn = Ui.text(this, "➤", 18f, Color.parseColor("#02131A"), true)
        sendBtn.gravity = Gravity.CENTER
        sendBtn.background = Ui.round(this, Ui.CYAN, 24f)
        sendBtn.setOnClickListener { send() }
        val slp = LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48))
        slp.leftMargin = Ui.dp(this, 8)
        row.addView(sendBtn, slp)
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = Ui.dp(this, 10)
        col.addView(row, rlp)

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        chat.removeAllViews()
        for (l in Bus.lines) addBubble(l, false)
        Bus.onLine = { addBubble(it, true) }
        Bus.onState = { updateStatus() }
        updateStatus()
        // Always-on mic: (re)start it while the app is visible, which is when Android allows it.
        if (Prefs.alwaysOn(this) && JarvisService.instance != null) {
            JarvisService.instance?.ensureMic()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        Bus.onLine = null
        Bus.onState = null
        super.onStop()
    }

    private fun updateStatus() {
        val svc = JarvisService.instance
        val ar = if (Prefs.autoReply(this)) "   ·   AUTO-REPLY ON" else ""
        if (svc == null) {
            status.text = "● OFFLINE — Setup mein Accessibility ON karein (yahan dabayein)"
            status.setTextColor(Ui.RED)
            return
        }
        val (txt, col) = when (Bus.state) {
            JarvisService.LISTENING -> "● LISTENING…" to Ui.AMBER
            JarvisService.THINKING -> "● PROCESSING…" to Color.parseColor("#B26BFF")
            JarvisService.SPEAKING -> "● SPEAKING" to Ui.GREEN
            else -> "● ONLINE — STANDING BY" to Ui.CYAN
        }
        status.text = txt + ar
        status.setTextColor(col)
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        runCommand(t)
    }

    private fun runCommand(t: String) {
        val svc = JarvisService.instance
        if (svc == null) {
            Toast.makeText(this, "Pehle Setup mein Accessibility ON karein", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        svc.submit(t)
    }

    private fun addBubble(l: Line, animate: Boolean) {
        val tv = TextView(this)
        tv.textSize = 15f
        tv.setTextColor(Ui.TEXT)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        tv.background = if (l.mine) {
            Ui.round(this, Color.argb(70, 25, 211, 232), 16f, Color.argb(160, 25, 211, 232), 1f)
        } else {
            Ui.round(this, Ui.GLASS, 16f, Color.argb(90, 61, 242, 160), 1f)
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = if (l.mine) Gravity.END else Gravity.START
        lp.topMargin = Ui.dp(this, 6)
        chat.addView(tv, lp)

        if (animate) {
            tv.alpha = 0f
            tv.translationY = Ui.dp(this, 14).toFloat()
            tv.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        if (animate && !l.mine) {
            val n = l.text.length
            val a = ValueAnimator.ofInt(0, n)
            a.duration = (n * 18L).coerceIn(300L, 2600L)
            a.addUpdateListener {
                tv.text = l.text.substring(0, it.animatedValue as Int)
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            a.start()
        } else {
            tv.text = l.text
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
