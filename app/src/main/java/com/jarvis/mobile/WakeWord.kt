package com.jarvis.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "Hey Siri" style wake word, no account or key needed. A tiny offline speech model (Vosk) runs on the phone
 * and only reacts to the word "Jarvis". Nothing is sent anywhere. The first start downloads the model (~40 MB).
 */
object WakeWord {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val GRAMMAR = "[\"jarvis\", \"jervis\", \"travis\", \"[unk]\"]"
    private val WAKE = Regex("\\b(jarvis|jervis|travis)\\b")

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    private var onWake: (() -> Unit)? = null

    /** Called (from any thread) if the model could not be loaded, so the app can fall back. */
    @Volatile
    var onFailure: (() -> Unit)? = null

    @Volatile
    var lastError: String = ""

    @Volatile
    private var loading = false

    @Volatile
    private var wanted = false

    @Volatile
    private var lastFire = 0L

    fun available(ctx: Context): Boolean = true

    /** Starts (or resumes) listening for "Jarvis". Returns true when running or when the model is being prepared. */
    @Synchronized
    fun start(ctx: Context, cb: () -> Unit): Boolean {
        onWake = cb
        wanted = true
        if (service != null) return true
        val m = model
        if (m != null) return begin(m)
        if (loading) return true
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val dir = ensureModel(app)
                val loaded = Model(dir.absolutePath)
                model = loaded
                loading = false
                main.post {
                    if (wanted && begin(loaded)) {
                        Bus.post(false, "Wake word tayyar ✔  Ab sirf \"Jarvis\" bolein.")
                    }
                }
            } catch (e: Throwable) {
                loading = false
                lastError = e.message ?: e.toString()
                main.post { onFailure?.invoke() }
            }
        }.start()
        return true
    }

    @Synchronized
    private fun begin(m: Model): Boolean {
        if (service != null) return true
        return try {
            val rec = Recognizer(m, 16000.0f, GRAMMAR)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(listener)
            service = s
            true
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            service = null
            main.post { onFailure?.invoke() }
            false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis)
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            lastError = exception?.message ?: "mic error"
        }

        override fun onTimeout() {}
    }

    private fun check(json: String?) {
        if (json == null || !wanted) return
        val text = try {
            val o = JSONObject(json)
            o.optString("partial").ifEmpty { o.optString("text") }
        } catch (e: Exception) {
            ""
        }
        if (WAKE.containsMatchIn(text)) {
            val now = System.currentTimeMillis()
            if (now - lastFire < 3000) return
            lastFire = now
            onWake?.invoke()
        }
    }

    /** Pause: releases the microphone so the speech recogniser can use it. */
    @Synchronized
    fun stop() {
        val s = service
        service = null
        if (s != null) {
            try { s.stop() } catch (e: Throwable) { }
            try { s.shutdown() } catch (e: Throwable) { }
        }
    }

    /** Fully shut down (setting switched off). The model stays cached on disk. */
    @Synchronized
    fun release() {
        wanted = false
        stop()
    }

    // ------------------------------------------------------------ model download

    private fun ensureModel(app: Context): File {
        val root = File(app.filesDir, "vosk")
        val dir = File(root, MODEL_NAME)
        val ok = File(root, "$MODEL_NAME.ok")
        if (ok.exists() && File(dir, "am").isDirectory) return dir
        root.mkdirs()
        dir.deleteRecursively()
        Bus.post(false, "Wake-word model download ho raha hai (~40 MB, sirf pehli baar). Intezar karein…")
        val zip = File(root, "model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            if (conn.responseCode !in 200..299) throw IOException("download HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> zip.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(root, e.name)
                if (out.canonicalPath.startsWith(rootPath)) {
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
                e = zin.nextEntry
            }
        }
        zip.delete()
        if (!File(dir, "am").isDirectory) throw IOException("model files missing after unzip")
        ok.writeText("ok")
        return dir
    }
}
