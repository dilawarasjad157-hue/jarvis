package com.jarvis.mobile

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisError(msg: String) : Exception(msg)

/**
 * Talks to Google's Gemini (free tier). If one model is busy, out of free quota
 * or unknown to your key, the next one is tried automatically.
 */
class Gemini(private val ctx: Context) {
    // Fast lite model first. 2.5 models only work for old accounts; they are kept as fallbacks.
    private val models = listOf("gemini-3.1-flash-lite", "gemini-flash-lite-latest", "gemini-flash-latest")
    private var good = 0

    private fun shortMsg(body: String): String {
        val m = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
        } catch (e: Exception) {
            body
        }
        return m.replace('\n', ' ').take(70)
    }

    suspend fun generate(contents: JSONArray, system: String, tools: JSONArray?): JSONObject =
        withContext(Dispatchers.IO) {
            val key = Prefs.apiKey(ctx)
            if (key.isBlank()) throw JarvisError("Gemini key set nahi hai. Jarvis app kholo aur key daalo.")
            val errs = ArrayList<String>()
            for (i in models.indices) {
                val idx = (good + i) % models.size
                val m = models[idx]
                val gen = JSONObject().put("maxOutputTokens", 1024)
                if (m.contains("2.5")) {
                    gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                } else if (m == "gemini-flash-latest") {
                    // newer Flash models think by default: keep it short so replies are fast
                    gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("generationConfig", gen)
                if (tools != null) body.put("tools", tools)
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code in 200..299) {
                        good = idx
                        return@withContext JSONObject(text)
                    }
                    errs.add("$m $code: ${shortMsg(text)}")
                    if (code == 400 && text.contains("API key", ignoreCase = true)) {
                        throw JarvisError("Gemini key galat hai. App mein dobara daalo.")
                    }
                    if (code == 401 || code == 403) {
                        throw JarvisError("Gemini ne key reject kar di ($code).")
                    }
                    // 404 / 429 / 5xx: try the next model
                } catch (e: java.io.IOException) {
                    // timeout / no network on this model: try the next one
                    errs.add("$m: ${e.message}")
                } finally {
                    conn.disconnect()
                }
            }
            throw JarvisError("Gemini jawab nahi de raha. " + errs.joinToString(" | "))
        }
}


/** One-shot text answer (no tools). Used for WhatsApp auto-replies. */
suspend fun Gemini.text(system: String, user: String): String {
    val contents = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
    )
    val r = generate(contents, system, null)
    val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        ?: return ""
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
        val p = parts.getJSONObject(i)
        if (p.has("text") && !p.optBoolean("thought", false)) sb.append(p.getString("text"))
    }
    return sb.toString().trim()
}

/** The agent loop: you speak, Gemini decides which phone tools to use, then answers. */
class Brain(private val ctx: Context, private val tools: Tools) {
    private val gemini = Gemini(ctx)
    private val history = JSONArray()
    private var lastUse = 0L

    private fun system(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        val owner = Prefs.ownerName(ctx).ifBlank { "sir" }
        return """
You are J.A.R.V.I.S., a calm, witty, extremely capable personal AI assistant living inside the Android phone of $owner. Think of Tony Stark's JARVIS: polite, a little dry humour, never robotic. Address the user as "sir" occasionally, not every sentence.
Current date and time: $now.

You operate the phone with your tools, and you also simply talk: answer questions, explain, advise, chat.
- Reply in the same language the user spoke (English, Urdu script or Roman Urdu). Your reply is spoken aloud: one to three short sentences, no lists, no markdown, no emoji.
- Prefer direct tools (call_contact, send_sms, whatsapp_reply, open_whatsapp_chat, set_alarm, set_timer, flashlight, set_volume, media_control, find_files/open_file, web_search) over clicking around.
- To work inside an app: open_app, then read_screen to see what is on screen, then tap_text / tap_index / type_text / scroll. Screens change after every action, so read_screen again before the next step.
- For files: find_files first, then open_file with the exact path it returned.
- WhatsApp: read_notifications shows recent incoming messages. whatsapp_reply answers a person who just messaged without opening anything. If the user says "when messages come, reply yourself / auto reply on", call set_auto_reply (enabled=true, plus instruction/only_for if they gave any). "Stop auto reply" -> enabled=false.
- Never invent phone numbers, contacts or file names. If a tool fails, say so honestly instead of pretending it worked.
- Calls, SMS and risky buttons (send, pay, delete...) are confirmed with the user by the app itself. Just attempt them.
- When the task is finished, say briefly what you did.
""".trimIndent()
    }

    suspend fun handle(userText: String): String {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastUse > 10 * 60 * 1000 || history.length() > 40) {
            while (history.length() > 0) history.remove(0)
        }
        lastUse = nowMs

        history.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText)))
        )

        var steps = 0
        while (steps++ < 14) {
            val resp = gemini.generate(history, system(), tools.declarations())
            val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (content == null || parts == null || parts.length() == 0) {
                return if (cand?.optString("finishReason") == "SAFETY") {
                    "I can't help with that one, sir."
                } else {
                    "I didn't get an answer, sir. Try again."
                }
            }
            // Keep the model's turn exactly as returned (needed for tool-call bookkeeping).
            history.put(content)

            val calls = ArrayList<JSONObject>()
            val said = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("functionCall")) {
                    calls.add(p.getJSONObject("functionCall"))
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    said.append(p.getString("text"))
                }
            }
            if (calls.isEmpty()) {
                return said.toString().trim().ifEmpty { "Done, sir." }
            }

            val results = JSONArray()
            for (c in calls) {
                val name = c.getString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                val result = try {
                    tools.run(name, args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                results.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", name).put("response", JSONObject().put("result", result))
                    )
                )
            }
            history.put(JSONObject().put("role", "user").put("parts", results))
        }
        return "That took too many steps, sir. Try a simpler command."
    }
}
