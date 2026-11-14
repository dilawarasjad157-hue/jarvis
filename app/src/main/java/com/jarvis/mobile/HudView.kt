package com.jarvis.mobile

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** The big animated reactor on the home screen. */
class HudView(ctx: Context) : View(ctx) {
    private val painter = ReactorPainter(detailed = true)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f * 0.98f
        painter.draw(canvas, width / 2f, height / 2f, r, System.currentTimeMillis(), Bus.state, Bus.level)
        postInvalidateOnAnimation()
    }
}
