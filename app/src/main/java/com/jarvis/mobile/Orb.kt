package com.jarvis.mobile

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** The floating arc-reactor button that sits over every app. Colour and motion show what JARVIS is doing. */
class Orb(ctx: Context) : View(ctx) {
    private val painter = ReactorPainter(detailed = false)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        painter.draw(canvas, width / 2f, height / 2f, r, System.currentTimeMillis(), Bus.state, Bus.level)
        postInvalidateOnAnimation()
    }
}
