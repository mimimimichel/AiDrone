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
import android.speech.tts.UtteranceProgressListener
import com.bebopia.app.core.ai.Speaker
import java.util.Locale

/** Ce que fait la voix en ce moment, pour l'interface. */
enum class VoiceState { OFF, LISTENING, HEARD, SPEAKING }

/**
 * Micro et voix du drone, en mode conversation.
 *
 * Un appui sur le micro ouvre la conversation : le téléphone écoute, envoie ta phrase,
 * se tait pendant que le drone répond (pour ne pas s'entendre lui-même), puis se remet
 * à écouter tout seul. Un nouvel appui ferme la conversation.
 */
class VoiceIO(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onState: (VoiceState, String) -> Unit,
) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile var speakEnabled = true
    private var recognizer: SpeechRecognizer? = null

    /** Conversation ouverte (le micro se relance tout seul). Lu et écrit sur le thread principal. */
    var conversation = false
        private set
    private var listening = false
    private var speaking = 0 // phrases en cours ou en attente de lecture
    private var errorsInRow = 0
    private val resume = Runnable { resumeIfIdle() }

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.FRANCE)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { speechFinished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { speechFinished() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { speechFinished() } }
                })
                ttsReady = true
            } else {
                onStatus("Synthèse vocale indisponible : les réponses s'afficheront seulement.")
            }
        }
    }

    // ---- voix du drone ---------------------------------------------------------------------
    override fun say(text: String) {
        if (text.isBlank()) return
        main.post {
            if (!speakEnabled || !ttsReady) return@post
            // Le micro se coupe pendant que le drone parle, sinon il s'entend lui-même.
            if (listening) cancelListening()
            main.removeCallbacks(resume)
            speaking++
            report()
            val r = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg-${System.nanoTime()}")
            if (r != TextToSpeech.SUCCESS) speechFinished()
        }
    }

    private fun speechFinished() {
        if (speaking > 0) speaking--
        if (speaking == 0) scheduleResume(250)
        report()
    }

    // ---- conversation ------------------------------------------------------------------------
    /** Ouvre ou ferme la conversation. */
    fun toggleConversation() = main.post { if (conversation) endConversation() else startConversation() }

    private fun startConversation() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Reconnaissance vocale indisponible sur ce téléphone.")
            return
        }
        conversation = true
        errorsInRow = 0
        // Couper le drone s'il parlait : c'est à toi.
        tts?.stop()
        speaking = 0
        startListening()
    }

    private fun endConversation() {
        conversation = false
        main.removeCallbacks(resume)
        if (listening) cancelListening()
        report()
    }

    private fun scheduleResume(delayMs: Long) {
        main.removeCallbacks(resume)
        if (conversation) main.postDelayed(resume, delayMs)
    }

    private fun resumeIfIdle() {
        if (conversation && !listening && speaking == 0) startListening()
    }

    private fun report(heard: String = "") {
        val state = when {
            speaking > 0 -> VoiceState.SPEAKING
            listening && heard.isNotBlank() -> VoiceState.HEARD
            listening -> VoiceState.LISTENING
            conversation -> VoiceState.LISTENING // entre deux écoutes
            else -> VoiceState.OFF
        }
        onState(state, heard)
    }

    private fun startListening() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Laisse le temps de finir sa phrase avant d'envoyer (indicatif, selon le téléphone).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try {
            r.startListening(intent)
            listening = true
        } catch (e: Exception) {
            listening = false
            conversation = false
            onStatus("Micro indisponible : ${e.message}")
        }
        report()
    }

    private fun cancelListening() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) report(text)
        }

        override fun onError(error: Int) {
            if (!listening) return // écoute annulée volontairement
            listening = false
            when (error) {
                // Silence ou bruit : on réécoute sans rien dire.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errorsInRow = 0
                    scheduleResume(150)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopWith("Autorise le micro dans les réglages du téléphone.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    stopWith("Reconnaissance vocale : pas de réseau. Installe le français hors ligne dans les réglages vocaux du téléphone.")
                else -> {
                    // Erreurs passagères (micro occupé, relance trop rapide) : on réessaie un peu plus tard.
                    errorsInRow++
                    if (errorsInRow >= 5) {
                        stopWith("Reconnaissance vocale : erreur $error. Conversation fermée, réappuie sur 🎤.")
                    } else {
                        recognizer?.destroy()
                        recognizer = null
                        scheduleResume(600L * errorsInRow)
                    }
                }
            }
            report()
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            listening = false
            errorsInRow = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onText(text)
            // On réécoute tout de suite : tu peux enchaîner, ou dire « stop » pendant que le drone réfléchit.
            // Dès que le drone répond, le micro se coupe le temps de sa phrase.
            scheduleResume(300)
            report()
        }
    }

    private fun stopWith(msg: String) {
        conversation = false
        main.removeCallbacks(resume)
        onStatus(msg)
    }

    fun release() {
        main.post {
            conversation = false
            main.removeCallbacks(resume)
            recognizer?.destroy()
            recognizer = null
        }
        tts?.shutdown()
        tts = null
    }
}
