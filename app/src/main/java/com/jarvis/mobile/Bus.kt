package com.jarvis.mobile

import android.os.Handler
import android.os.Looper

data class Line(val mine: Boolean, val text: String)

/** Tiny in-process message bus between the service and the screens. */
object Bus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var state: Int = JarvisService.IDLE
        private set
    @Volatile var level: Float = 0f

    val lines = ArrayList<Line>()
    var onLine: ((Line) -> Unit)? = null
    var onState: (() -> Unit)? = null

    fun post(mine: Boolean, text: String) {
        val l = Line(mine, text)
        main.post {
            lines.add(l)
            while (lines.size > 60) lines.removeAt(0)
            onLine?.invoke(l)
        }
    }

    fun setState(s: Int) {
        state = s
        if (s != JarvisService.LISTENING) level = 0f
        main.post { onState?.invoke() }
    }
}
