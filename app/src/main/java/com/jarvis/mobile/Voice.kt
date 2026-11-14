package com.jarvis.mobile

import android.content.Context
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

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
            }
        }
    }

    suspend fun speak(raw: String) = suspendCancellableCoroutine<Unit> { cont ->
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
        t.language = if (avail >= TextToSpeech.LANG_AVAILABLE) loc else Locale.US
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
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION_ARRAY)
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

    /** Stop talking and listening right now (barge-in). */
    fun stopAll() {
        tts?.stop()
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
