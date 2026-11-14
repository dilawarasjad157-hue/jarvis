package com.jarvis.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.sin

/** Full-screen sci-fi background: dark gradient, faint grid, drifting particles, slow scan line. */
class Backdrop(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val n = 46
    private val px = FloatArray(n) { pseudo(it * 3 + 1) }
    private val py = FloatArray(n) { pseudo(it * 3 + 2) }
    private val ps = FloatArray(n) { 0.02f + pseudo(it * 3 + 3) * 0.05f }

    private fun pseudo(i: Int): Float {
        val x = sin(i * 12.9898f) * 43758.547f
        return x - Math.floor(x.toDouble()).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = System.currentTimeMillis() / 1000f

        p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#04070F"), Color.parseColor("#0A1426"), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(
            w / 2f, h * 0.30f, w * 0.9f,
            intArrayOf(Color.argb(60, 25, 211, 232), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // grid
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = Color.argb(14, 120, 220, 255)
        val step = 36 * d
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
        var y = (t * 8 * d) % step
        while (y < h) { c.drawLine(0f, y, w, y, p); y += step }

        // particles
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val yy = ((py[i] - t * ps[i]) % 1f + 1f) % 1f
            val xx = px[i] + 0.012f * sin(t * 0.8f + i)
            p.color = Color.argb((40 + 90 * pseudo(i + 99)).toInt(), 120, 230, 255)
            c.drawCircle(xx * w, yy * h, (1f + 1.6f * pseudo(i + 7)) * d, p)
        }

        // scan line
        val sy = ((t * 0.12f) % 1f) * h
        p.shader = LinearGradient(0f, sy - 60 * d, 0f, sy, Color.TRANSPARENT, Color.argb(40, 25, 211, 232), Shader.TileMode.CLAMP)
        c.drawRect(0f, sy - 60 * d, w, sy, p)
        p.shader = null

        postInvalidateOnAnimation()
    }
}
