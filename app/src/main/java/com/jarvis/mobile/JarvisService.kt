package com.jarvis.mobile

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

data class ScreenItem(val label: String, val rect: Rect)

/**
 * JARVIS lives here. As an Accessibility Service it can read any screen, tap,
 * type and scroll, and it keeps running in the background. A floating orb is the
 * "Hey Jarvis" button: tap it, speak, and JARVIS carries out the request.
 */
class JarvisService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisService? = null
        const val IDLE = 0
        const val LISTENING = 1
        const val THINKING = 2
        const val SPEAKING = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voice: Voice? = null
    private var brain: Brain? = null
    private var orb: Orb? = null
    private var job: Job? = null
    private var loop: Job? = null

    @Volatile
    private var convoUntil = 0L
    private var caption: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideCaption = Runnable { caption?.animate()?.alpha(0f)?.setDuration(300)?.start() }

    @Volatile
    var currentPkg: String = ""
    private var lastItems: List<ScreenItem> = emptyList()

    // ------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val v = Voice(this)
        voice = v
        brain = Brain(this, Tools(this, v))
        showOrb()
        if (Prefs.alwaysOn(this)) startLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { currentPkg = it.toString() }
    }

    override fun onInterrupt() {
        job?.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        loop?.cancel()
        loop = null
        job?.cancel()
        scope.cancel()
        try {
            orb?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (e: Exception) {
        }
        orb = null
        try {
            caption?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (e: Exception) {
        }
        caption = null
        ui.removeCallbacks(hideCaption)
        voice?.shutdown()
        voice = null
        if (instance === this) instance = null
    }

    // ------------------------------------------------------------ orb + conversation

    private fun showOrb() {
        if (orb != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (66 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 24
        lp.y = resources.displayMetrics.heightPixels / 3

        val o = Orb(this)
        o.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ox = lp.x
                        oy = lp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (abs(dx) > 14 || abs(dy) > 14) moved = true
                        if (moved) {
                            lp.x = ox + dx.toInt()
                            lp.y = oy + dy.toInt()
                            try {
                                wm.updateViewLayout(v, lp)
                            } catch (e: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggle() }
                    }
                }
                return true
            }
        })
        wm.addView(o, lp)
        orb = o
        setState(IDLE)
    }

    private fun setState(s: Int) {
        Bus.setState(s)
    }

    /** Tap on the orb / tile / home reactor: start listening, or interrupt if busy. */
    fun toggle() {
        val v = voice ?: return
        if (job?.isActive == true) {
            // Tapping while JARVIS is busy interrupts him.
            job?.cancel()
            v.stopAll()
            setState(IDLE)
            return
        }
        if (loop?.isActive == true) {
            // Always-on mode is already listening: a tap just means "talk now, no wake word needed".
            convoUntil = System.currentTimeMillis() + 15000
            return
        }
        job = scope.launch {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                v.speak("Please allow the microphone permission in the Jarvis app.")
                return@launch
            }
            setState(LISTENING)
            val said = v.listen()
            if (said.isNullOrBlank()) {
                setState(IDLE)
                return@launch
            }
            runCommand(said)
        }
    }

    // ------------------------------------------------------------ always-on microphone

    private val wake = Regex(
        "\\b(?:(?:hey|hi|ok|okay|aye|ae)\\s+)?(?:jarvis|jarves|jarvas|jarvice|jervis|jarwis|charvis|garvis)\\b|جارویس|جاروس|جاروِس",
        RegexOption.IGNORE_CASE
    )

    /** null = ignore; "" = only the wake word was said; otherwise the command. */
    private fun extractCommand(heard: String, inConvo: Boolean): String? {
        val m = wake.find(heard)
        if (m != null) return heard.removeRange(m.range).trim(' ', ',', '.', '!', '?', '-')
        return if (inConvo) heard.trim() else null
    }

    fun isAlwaysOn(): Boolean = loop?.isActive == true

    /** Turn the always-listening mode on/off. Call from a visible activity so Android allows the mic. */
    fun setAlwaysOn(on: Boolean) {
        Prefs.setAlwaysOn(this, on)
        if (on) startLoop() else stopLoop()
    }

    /** Re-assert the foreground mic service and the loop (safe to call repeatedly). */
    fun ensureMic() {
        if (!Prefs.alwaysOn(this)) return
        MicService.start(this)
        startLoop()
    }

    fun startLoop() {
        if (loop?.isActive == true || !Prefs.alwaysOn(this)) return
        MicService.start(this)
        loop = scope.launch { listenLoop() }
    }

    fun stopLoop() {
        loop?.cancel()
        loop = null
        MicService.stop(this)
        if (job?.isActive != true) setState(IDLE)
    }

    private suspend fun listenLoop() {
        val v = voice ?: return
        while (true) {
            if (job?.isActive == true) { delay(300); continue }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                delay(3000); continue
            }
            val started = System.currentTimeMillis()
            setState(LISTENING)
            val heard = v.listen()
            if (job?.isActive != true) setState(IDLE)
            if (heard.isNullOrBlank()) {
                // instant failures (no mic access, recogniser busy): back off so we don't spin
                delay(if (System.currentTimeMillis() - started < 800) 2000 else 250)
                continue
            }
            val inConvo = System.currentTimeMillis() < convoUntil
            val cmd = extractCommand(heard, inConvo) ?: continue
            if (cmd.isEmpty()) {
                val j = scope.launch {
                    setState(SPEAKING)
                    v.speak("Yes, sir?")
                    setState(IDLE)
                    convoUntil = System.currentTimeMillis() + 12000
                }
                job = j
                j.join()
                continue
            }
            val j = scope.launch {
                runCommand(cmd)
                convoUntil = System.currentTimeMillis() + 12000
            }
            job = j
            j.join()
        }
    }

    /** Used by the typing box in the app. */
    fun submit(text: String) {
        job?.cancel()
        job = scope.launch { runCommand(text) }
    }

    private suspend fun runCommand(text: String) {
        val b = brain ?: return
        val v = voice ?: return
        Bus.post(true, text)
        showCaption("🎙  $text")
        setState(THINKING)
        try {
            val reply = b.handle(text)
            Bus.post(false, reply)
            showCaption(reply)
            setState(SPEAKING)
            v.speak(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: JarvisError) {
            Bus.post(false, e.message ?: "Something went wrong.")
            setState(SPEAKING)
            v.speak(e.message ?: "Something went wrong.")
        } catch (e: Exception) {
            Bus.post(false, "Sorry sir, something failed. ${e.message ?: ""}")
            setState(SPEAKING)
            v.speak("Sorry sir, something failed. ${e.message ?: ""}")
        } finally {
            setState(IDLE)
        }
    }

    private fun showCaption(text: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        var tv = caption
        if (tv == null) {
            tv = TextView(this)
            tv.setTextColor(Color.parseColor("#E6F7FF"))
            tv.textSize = 14f
            tv.maxLines = 5
            tv.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            val bg = GradientDrawable()
            bg.cornerRadius = 18 * d
            bg.setColor(Color.argb(225, 6, 14, 28))
            bg.setStroke((1.2f * d).toInt(), Color.argb(170, 25, 211, 232))
            tv.background = bg
            tv.maxWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (96 * d).toInt()
            try {
                wm.addView(tv, lp)
            } catch (e: Exception) {
                return
            }
            caption = tv
        }
        tv.text = text
        tv.animate().cancel()
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(200).start()
        ui.removeCallbacks(hideCaption)
        ui.postDelayed(hideCaption, (3500 + text.length * 45L).coerceAtMost(12000L))
    }

    // ------------------------------------------------------------ screen control

    fun readScreen(): String {
        val root = rootInActiveWindow
            ?: return "The screen can't be read right now (it may be locked or a protected screen)."
        val items = ArrayList<ScreenItem>()
        walk(root, items, 0)
        lastItems = items
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append('\n')
        for ((i, item) in items.withIndex()) {
            sb.append('[').append(i).append("] ").append(item.label).append('\n')
        }
        if (items.isEmpty()) sb.append("(nothing readable on this screen)")
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<ScreenItem>, depth: Int) {
        if (n == null || out.size >= 120 || depth > 40) return
        if (n.isVisibleToUser) {
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            if (label.isNotEmpty() || n.isEditable || n.isCheckable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val flags = ArrayList<String>()
                    if (n.isClickable) flags.add("tap")
                    if (n.isEditable) flags.add("input")
                    if (n.isCheckable) flags.add(if (n.isChecked) "on" else "off")
                    if (n.isScrollable) flags.add("scroll")
                    val shown = label.replace('\n', ' ').take(80).ifEmpty {
                        n.className?.toString()?.substringAfterLast('.') ?: "view"
                    }
                    val suffix = if (flags.isEmpty()) "" else " (" + flags.joinToString(",") + ")"
                    out.add(ScreenItem(shown + suffix, r))
                }
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    fun itemLabel(index: Int): String? = lastItems.getOrNull(index)?.label

    suspend fun tapItem(index: Int): Boolean {
        val item = lastItems.getOrNull(index) ?: return false
        return tapAt(item.rect.exactCenterX(), item.rect.exactCenterY())
    }

    suspend fun clickByText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = root.findAccessibilityNodeInfosByText(t)
        val target = found.firstOrNull { it.isVisibleToUser } ?: return false
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        target.getBoundsInScreen(r)
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    fun typeText(s: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) target = findEditable(root)
        if (target == null) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val f = findEditable(n.getChild(i))
            if (f != null) return f
        }
        return null
    }

    fun press(button: String): Boolean = when (button.lowercase().trim()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
        else -> false
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine<Boolean> { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!started && cont.isActive) cont.resume(false)
        }

    suspend fun tapAt(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60)
    }

    /** down = see more content below (finger swipes up). */
    suspend fun swipe(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * (if (down) 0.72f else 0.30f)
        val to = dm.heightPixels * (if (down) 0.30f else 0.72f)
        val p = Path()
        p.moveTo(x, from)
        p.lineTo(x, to)
        return gesture(p, 350)
    }
}
