package com.bebopia.app.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.bebopia.app.core.ai.Speaker
import java.util.Locale

/** Micro (reconnaissance vocale Android) et voix du drone (synthèse vocale Android). */
class VoiceIO(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile var speakEnabled = true
    private var recognizer: SpeechRecognizer? = null
    @Volatile var listening = false
        private set

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.FRANCE)
                ttsReady = true
            } else {
                onStatus("Synthèse vocale indisponible : les réponses s'afficheront seulement.")
            }
        }
    }

    override fun say(text: String) {
        if (speakEnabled && ttsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg-${System.nanoTime()}")
        }
    }

    fun toggleListening() = main.post { if (listening) stopListening() else startListening() }

    private fun setListening(v: Boolean) {
        listening = v
        onListening(v)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Reconnaissance vocale indisponible sur ce téléphone.")
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = setListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                setListening(false)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai rien compris."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le micro dans les réglages du téléphone."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Reconnaissance vocale : pas de réseau. Installe le français hors ligne dans les réglages vocaux."
                    else -> "Reconnaissance vocale : erreur $error."
                }
                onStatus(msg)
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onText(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            r.startListening(intent)
            setListening(true)
        } catch (e: Exception) {
            setListening(false)
            onStatus("Micro indisponible : ${e.message}")
        }
    }

    private fun stopListening() {
        recognizer?.stopListening()
    }

    fun release() {
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
        tts?.shutdown()
        tts = null
    }
}
