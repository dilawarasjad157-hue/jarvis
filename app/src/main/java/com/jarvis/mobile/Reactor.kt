package com.jarvis.mobile

import android.animation.ArgbEvaluator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the arc-reactor: glow, tick ring, rotating arcs, voice bars and a pulsing core.
 * Shared by the big HUD on the main screen and the little floating orb.
 */
class ReactorPainter(private val detailed: Boolean) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val eval = ArgbEvaluator()
    private var cur = Color.parseColor("#00D4FF")
    private var lvl = 0f
    private var lastMs = 0L
    private var spin = 0f      // degrees, drives the rotating arcs
    private val bars = if (detailed) 56 else 24

    companion object {
        fun colorFor(state: Int): Int = Color.parseColor(
            when (state) {
                JarvisService.LISTENING -> "#FF8A1F"
                JarvisService.THINKING -> "#B26BFF"
                JarvisService.SPEAKING -> "#00E89A"
                else -> "#00D4FF"
            }
        )
    }

    private fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, nowMs: Long, state: Int, rawLevel: Float) {
        val dt = if (lastMs == 0L) 0.016f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = nowMs
        val t = nowMs / 1000f

        cur = eval.evaluate(0.10f, cur, colorFor(state)) as Int
        val col = cur

        val target = when (state) {
            JarvisService.LISTENING -> rawLevel.coerceIn(0.08f, 1f)
            JarvisService.SPEAKING -> (0.45f + 0.30f * sin(t * 9f) + 0.15f * sin(t * 17f + 1f)).coerceIn(0.1f, 1f)
            JarvisService.THINKING -> 0.25f
            else -> 0.06f + 0.03f * sin(t * 2f)
        }
        lvl += (target - lvl) * 0.2f
        val speed = when (state) {
            JarvisService.THINKING -> 260f
            JarvisService.LISTENING -> 70f
            JarvisService.SPEAKING -> 90f
            else -> 24f
        }
        spin = (spin + speed * dt) % 360f

        // 1. soft glow
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(alpha(col, 0.30f + lvl * 0.35f), alpha(col, 0.10f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, p)
        p.shader = null

        // 2. outer thin ring + ticks
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.012f
        p.color = alpha(col, 0.35f)
        c.drawCircle(cx, cy, r * 0.96f, p)
        if (detailed) {
            c.save()
            c.rotate(-spin * 0.25f, cx, cy)
            p.strokeWidth = r * 0.010f
            for (i in 0 until 72) {
                val long = i % 6 == 0
                val a = i * 5f * (PI / 180f).toFloat()
                val r1 = r * (if (long) 0.86f else 0.90f)
                val r2 = r * 0.935f
                p.color = alpha(col, if (long) 0.85f else 0.35f)
                c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, p)
            }
            c.restore()
        }

        // 3. big rotating arcs
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * (if (detailed) 0.045f else 0.09f)
        rect.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        p.color = alpha(col, 0.95f)
        for (i in 0 until 3) c.drawArc(rect, spin + i * 120f, 68f, false, p)

        // 4. counter-rotating arcs
        p.strokeWidth = r * (if (detailed) 0.028f else 0.06f)
        rect.set(cx - r * 0.64f, cy - r * 0.64f, cx + r * 0.64f, cy + r * 0.64f)
        p.color = alpha(col, 0.60f)
        for (i in 0 until 4) c.drawArc(rect, -spin * 1.6f + i * 90f, 38f, false, p)

        // 4b. orange accent arcs on the outer rim (Mark-style HUD detail)
        if (detailed) {
            p.strokeWidth = r * 0.018f
            rect.set(cx - r * 0.975f, cy - r * 0.975f, cx + r * 0.975f, cy + r * 0.975f)
            p.color = alpha(Color.parseColor("#FF6B00"), 0.85f)
            for (i in 0 until 2) c.drawArc(rect, -spin * 0.5f + i * 180f, 26f, false, p)
        }

        // 5. thinking sweep
        if (state == JarvisService.THINKING) {
            p.strokeWidth = r * 0.03f
            rect.set(cx - r * 0.54f, cy - r * 0.54f, cx + r * 0.54f, cy + r * 0.54f)
            p.color = alpha(Color.WHITE, 0.9f)
            c.drawArc(rect, spin * 2.2f, 50f, false, p)
        }

        // 6. voice bars
        p.strokeWidth = r * (if (detailed) 0.018f else 0.035f)
        for (i in 0 until bars) {
            val a = (i * 360f / bars) * (PI / 180f).toFloat()
            val wob = 0.5f + 0.5f * sin(t * (5f + (i % 7)) + i * 1.7f)
            val len = r * (0.025f + lvl * 0.16f * wob + 0.02f * abs(sin(t + i)))
            val r1 = r * 0.47f
            p.color = alpha(col, 0.55f + 0.4f * wob)
            c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * (r1 + len), cy + sin(a) * (r1 + len), p)
        }

        // 7. core
        val pulse = 1f + 0.05f * sin(t * 3f) + lvl * 0.14f
        val cr = r * 0.36f * pulse
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, cr,
            intArrayOf(Color.WHITE, alpha(col, 0.95f), alpha(col, 0.25f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, cr, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.014f
        p.color = alpha(Color.WHITE, 0.8f)
        c.drawCircle(cx, cy, cr * 0.82f, p)
        p.strokeCap = Paint.Cap.BUTT
    }
}
