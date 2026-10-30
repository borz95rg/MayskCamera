package com.maysk.camera.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Голосовое управление: «снимок», «фото», «сыр», «cheese», «запись», «стоп». */
class VoiceShutter(private val context: Context, private val onCommand: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private val handler = Handler(Looper.getMainLooper())

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active || !available) return
        active = true
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                handle(results)
                restart()
            }

            override fun onPartialResults(partialResults: Bundle?) = handle(partialResults)
            override fun onError(error: Int) = restart()
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }.onFailure { restart() }
    }

    private var lastFire = 0L

    private fun handle(b: Bundle?) {
        val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = words.joinToString(" ").lowercase()
        val now = System.currentTimeMillis()
        if (now - lastFire < 2000) return
        val cmd = when {
            listOf("снимок", "фото", "сыр", "cheese", "щёлк", "щелк", "снять").any { text.contains(it) } -> "shoot"
            listOf("запись", "record").any { text.contains(it) } -> "record"
            listOf("стоп", "stop", "хватит").any { text.contains(it) } -> "stop"
            else -> null
        }
        if (cmd != null) {
            lastFire = now
            onCommand(cmd)
        }
    }

    private fun restart() {
        if (!active) return
        handler.postDelayed({ listen() }, 400)
    }
}
