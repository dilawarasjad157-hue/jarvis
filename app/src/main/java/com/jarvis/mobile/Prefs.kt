package com.jarvis.mobile

import android.content.Context

/** Small settings store. The key can come from secrets.properties at build time or be typed in the app. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String {
        val saved = sp(c).getString("key", "") ?: ""
        return saved.ifBlank { BuildConfig.DEFAULT_GEMINI_KEY }
    }

    fun setApiKey(c: Context, v: String) {
        sp(c).edit().putString("key", v.trim()).apply()
    }

    /** Speech recognition language, e.g. en-US, ur-PK, hi-IN. */
    fun lang(c: Context): String = sp(c).getString("lang", "en-US") ?: "en-US"

    fun setLang(c: Context, v: String) {
        sp(c).edit().putString("lang", v.trim().ifEmpty { "en-US" }).apply()
    }

    fun ownerName(c: Context): String = sp(c).getString("owner", "") ?: ""
    fun setOwnerName(c: Context, v: String) = sp(c).edit().putString("owner", v.trim()).apply()

    // ---- WhatsApp auto-reply
    fun autoReply(c: Context): Boolean = sp(c).getBoolean("ar_on", false)
    fun setAutoReply(c: Context, v: Boolean) = sp(c).edit().putBoolean("ar_on", v).apply()

    fun arInstruction(c: Context): String =
        sp(c).getString("ar_text", "Dosti wala, mukhtasar andaaz mein jawab do. Agar zaroori ho to kaho ke main thori der mein khud baat karta hoon.") ?: ""

    fun setArInstruction(c: Context, v: String) = sp(c).edit().putString("ar_text", v.trim()).apply()

    /** Comma separated names. Empty = reply to everybody. */
    fun arOnly(c: Context): String = sp(c).getString("ar_only", "") ?: ""
    fun setArOnly(c: Context, v: String) = sp(c).edit().putString("ar_only", v.trim()).apply()

    fun arGroups(c: Context): Boolean = sp(c).getBoolean("ar_groups", false)
    fun setArGroups(c: Context, v: Boolean) = sp(c).edit().putBoolean("ar_groups", v).apply()

    fun arSign(c: Context): Boolean = sp(c).getBoolean("ar_sign", true)
    fun setArSign(c: Context, v: Boolean) = sp(c).edit().putBoolean("ar_sign", v).apply()
}
