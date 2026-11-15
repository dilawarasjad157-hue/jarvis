package com.jarvis.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONArray
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Ears and mouth. Uses the phone's own speech recogniser and text-to-speech,
 * so it costs nothing. Create it on the main thread.
 */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var britishVoice: android.speech.tts.Voice? = null

    /** Best installed British male voice (the "butler" sound), or null. */
    private fun pickBritishMale(t: TextToSpeech): android.speech.tts.Voice? {
        val all = try { t.voices } catch (e: Exception) { null } ?: return null
        val gb = all.filter {
            it.locale.language == "en" && it.locale.country == "GB" && !it.features.contains("notInstalled")
        }
        val maleTags = listOf("-gbd-", "-gbb-", "-rjs-", "-gbg-")
        return gb.firstOrNull { v -> maleTags.any { v.name.contains(it, true) } && !v.name.contains("female", true) }
            ?: gb.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
            ?: gb.firstOrNull()
    }

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.UK
                tts?.setPitch(0.82f)        // deeper, calmer
                tts?.setSpeechRate(0.95f)
                tts?.let { britishVoice = pickBritishMale(it) }
            }
        }
    }

    /** Speaks with ElevenLabs when a key is set (and it works), otherwise with the phone's own voice. */
    suspend fun speak(raw: String) {
        val clean = raw.replace(Regex("[*#`_]"), "").trim()
        val key = Prefs.elevenKey(ctx)
        val urdu = clean.any { it in '\u0600'..'\u06FF' }
        if (key.isNotBlank() && clean.isNotEmpty() && !urdu) {
            val f = withContext(Dispatchers.IO) { elevenFile(clean.take(500), key) }
            if (f != null) {
                playFile(f)
                return
            }
        }
        // Gemini ki mard wali awaaz (Charon) - English aur Urdu dono mein. Fail ho to phone ki awaaz.
        if (clean.isNotEmpty() && System.currentTimeMillis() > geminiDownUntil) {
            val pcm = withContext(Dispatchers.IO) { geminiPcm(clean.take(600)) }
            if (pcm != null) {
                playPcm(pcm)
                return
            }
        }
        speakLocal(raw)
    }

    private val geminiVoice = "Charon"
    private val geminiModels = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts", "gemini-2.5-flash-tts")
    private var geminiModel: String? = null
    private var geminiDownUntil = 0L
    private var track: AudioTrack? = null

    /** Raw 24 kHz 16-bit mono audio from Gemini TTS, cached on disk. Null on any problem. */
    private fun geminiPcm(text: String): ByteArray? {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return null
        val dir = File(ctx.cacheDir, "gvoice").apply { mkdirs() }
        val hash = MessageDigest.getInstance("MD5").digest((geminiVoice + text).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val f = File(dir, "$hash.pcm")
        if (f.exists() && f.length() > 0) return f.readBytes()
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(40)?.forEach { it.delete() }
        val order = geminiModel?.let { listOf(it) } ?: geminiModels
        try {
            for (m in order) {
                val pcm = geminiCall(m, text, key)
                if (pcm != null && pcm.isNotEmpty()) {
                    geminiModel = m
                    f.writeBytes(pcm)
                    return pcm
                }
            }
        } catch (e: Exception) {
            // network problem
        }
        geminiDownUntil = System.currentTimeMillis() + 45000
        return null
    }

    private fun geminiCall(model: String, text: String, key: String): ByteArray? {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            val part = JSONObject().put("text", "Say in a calm, deep, polite and confident tone: $text")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part))))
                .put(
                    "generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig", JSONObject().put(
                                "voiceConfig", JSONObject().put(
                                    "prebuiltVoiceConfig", JSONObject().put("voiceName", geminiVoice)
                                )
                            )
                        )
                )
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val o = parts.getJSONObject(i)
                val inl = o.optJSONObject("inlineData") ?: o.optJSONObject("inline_data")
                val b64 = inl?.optString("data") ?: ""
                if (b64.isNotEmpty()) return Base64.decode(b64, Base64.DEFAULT)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun playPcm(pcm: ByteArray) {
        val frames = pcm.size / 2
        if (frames <= 0) return
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = at
        try {
            at.write(pcm, 0, pcm.size)
            at.play()
            val limit = frames * 1000L / 24000L + 1500L
            val t0 = System.currentTimeMillis()
            while (at.playbackHeadPosition < frames - 240 && System.currentTimeMillis() - t0 < limit) {
                delay(40)
                if (at.playState != AudioTrack.PLAYSTATE_PLAYING) break
            }
        } finally {
            try { at.stop() } catch (e: Exception) { }
            try { at.release() } catch (e: Exception) { }
            if (track === at) track = null
        }
    }

    private var player: MediaPlayer? = null

    /** Downloads (or reuses a cached) mp3 from ElevenLabs. Returns null on any problem. */
    private fun elevenFile(text: String, key: String): File? {
        val voiceId = Prefs.elevenVoice(ctx)
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val hash = MessageDigest.getInstance("MD5").digest((voiceId + text).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val f = File(dir, "$hash.mp3")
        if (f.exists() && f.length() > 0) return f
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(60)?.forEach { it.delete() }
        val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_64")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("xi-api-key", key)
            val body = JSONObject()
                .put("text", text)
                .put("model_id", "eleven_flash_v2_5")
                .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            if (f.length() > 0) f else null
        } catch (e: Exception) {
            f.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun playFile(f: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        fun done() {
            try { mp.release() } catch (e: Exception) { }
            if (player === mp) player = null
            if (cont.isActive) cont.resume(Unit)
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, _, _ -> done(); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            done()
        }
        cont.invokeOnCancellation {
            try { mp.release() } catch (e: Exception) { }
            if (player === mp) player = null
        }
    }

    private suspend fun speakLocal(raw: String) = suspendCancellableCoroutine<Unit> { cont ->
        val text = raw.replace(Regex("[*#`_]"), "").trim().take(3500)
        val t = tts
        if (t == null || !ttsReady || text.isEmpty()) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        // Urdu script -> Urdu voice if the phone has one, otherwise English.
        val urdu = text.any { it in '\u0600'..'\u06FF' }
        val loc = if (urdu) Locale("ur", "PK") else Locale.US
        val avail = t.isLanguageAvailable(loc)
        val bv = britishVoice
        if (!urdu && bv != null) {
            t.voice = bv
        } else {
            t.language = if (avail >= TextToSpeech.LANG_AVAILABLE) loc else Locale.UK
        }
        val id = UUID.randomUUID().toString()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
        })
        cont.invokeOnCancellation { t.stop() }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Listens once. Returns the sentence, or null if nothing was understood. */
    suspend fun listen(): String? {
        tts?.stop()
        return suspendCancellableCoroutine<String?> { cont ->
            main.post {
                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    if (cont.isActive) cont.resume(null)
                    return@post
                }
                val r = SpeechRecognizer.createSpeechRecognizer(ctx)
                recognizer = r
                var done = false
                fun finish(t: String?) {
                    if (done) return
                    done = true
                    try {
                        r.destroy()
                    } catch (e: Exception) {
                    }
                    if (recognizer === r) recognizer = null
                    if (cont.isActive) cont.resume(t)
                }
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        Bus.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        finish(null)
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(list?.firstOrNull())
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(ctx))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }
                r.startListening(intent)
                cont.invokeOnCancellation {
                    main.post { finish(null) }
                }
            }
        }
    }

    // ------------------------------------------------------------ always-on listening
    // One recogniser that restarts itself immediately, so the mic stays on even when nobody speaks.

    private var cOn = false
    private var cPaused = false
    private var cRec: SpeechRecognizer? = null
    private var cCb: ((String) -> Unit)? = null
    private var cFails = 0
    private var cStart = 0L
    private var cToken = 0

    fun startContinuous(cb: (String) -> Unit) {
        main.post {
            cCb = cb
            cOn = true
            cPaused = false
            cFails = 0
            cBegin()
        }
    }

    fun stopContinuous() {
        cOn = false
        main.post {
            cCb = null
            cDestroy()
        }
    }

    /** Silence the always-on mic while Jarvis talks or runs a command. */
    fun pauseContinuous() {
        cPaused = true
        main.post { cDestroy() }
    }

    fun resumeContinuous() {
        main.post {
            if (cOn) {
                cPaused = false
                cBegin()
            }
        }
    }

    private fun cDestroy() {
        cToken++
        try {
            cRec?.destroy()
        } catch (e: Exception) {
        }
        cRec = null
    }

    private fun cBegin() {
        if (!cOn || cPaused) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
        val r: SpeechRecognizer = cRec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { nr ->
            cRec = nr
            nr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    Bus.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    cAgain(error)
                }

                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) cCb?.invoke(t)
                    cAgain(0)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(ctx))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        cStart = System.currentTimeMillis()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            cDestroy()
            cAgain(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** Restart listening right away (a short pause only after repeated instant failures). */
    private fun cAgain(error: Int) {
        if (!cOn || cPaused) return
        val quick = System.currentTimeMillis() - cStart < 700
        if (error != 0 && quick) cFails++ else cFails = 0
        var wait = 40L
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            cDestroy()
            wait = 300L
        } else if (cFails > 3) {
            wait = 3000L
        } else if (error != 0) {
            wait = 80L
        }
        val tk = ++cToken
        main.postDelayed({ if (tk == cToken) cBegin() }, wait)
    }

    /** Stop talking and listening right now (barge-in). */
    fun stopAll() {
        tts?.stop()
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { track?.stop() } catch (e: Exception) { }
        main.post {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
            }
            recognizer = null
        }
    }

    fun shutdown() {
        stopAll()
        tts?.shutdown()
        tts = null
    }
}
