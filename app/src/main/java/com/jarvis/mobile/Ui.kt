package com.jarvis.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Colours and small view helpers so the screens look consistent. */
object Ui {
    val CYAN = Color.parseColor("#00D4FF")
    val TEXT = Color.parseColor("#CFF6FF")
    val DIM = Color.parseColor("#5AB8CC")
    val GREEN = Color.parseColor("#3DF2A0")
    val RED = Color.parseColor("#FF5A6A")
    val AMBER = Color.parseColor("#FF8A1F")
    val ORANGE = Color.parseColor("#FF6B00")
    val BG = Color.parseColor("#00060A")
    val GLASS = Color.argb(205, 1, 15, 24)

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun round(c: Context, fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(c, radiusDp).toFloat()
        g.setColor(fill)
        if (stroke != 0) g.setStroke(maxOf(1, dp(c, strokeDp)), stroke)
        return g
    }

    fun text(c: Context, t: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(c)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    fun button(c: Context, t: String, filled: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = t
        b.isAllCaps = false
        b.textSize = 14f
        b.stateListAnimator = null
        b.setTextColor(if (filled) Color.parseColor("#00141C") else CYAN)
        b.background = if (filled) round(c, CYAN, 12f) else round(c, Color.TRANSPARENT, 12f, CYAN, 1.2f)
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        b.setOnClickListener { onClick() }
        return b
    }

    fun input(c: Context, hint: String, value: String): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setText(value)
        e.setTextColor(TEXT)
        e.setHintTextColor(DIM)
        e.textSize = 14f
        e.background = round(c, Color.argb(130, 1, 13, 20), 12f, Color.argb(90, 0, 212, 255), 1f)
        e.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        return e
    }

    fun visible(v: View, show: Boolean) {
        v.visibility = if (show) View.VISIBLE else View.GONE
    }
}
