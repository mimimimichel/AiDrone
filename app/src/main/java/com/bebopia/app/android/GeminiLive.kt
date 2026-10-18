package com.bebopia.app.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Network
import android.os.Build
import android.util.Base64
import android.util.Log
import com.bebopia.app.core.ai.Eyes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class LiveState { OFF, CONNECTING, LISTENING, SPEAKING }

/**
 * Conversation vocale en direct avec Gemini Live (comme dans Vr2) :
 * le micro part en continu, la voix de Gemini revient en direct (tu peux lui couper la parole),
 * et une image de la caméra est envoyée chaque seconde.
 * Les actions du drone passent par des outils ([onTool]) : c'est l'application qui décide.
 */
class GeminiLive(
    private val context: Context,
    private val apiKey: String,
    private val network: () -> Network?,
    private val eyes: Eyes,
    private val systemPrompt: String,
    private val tools: JSONArray,
    /** Appelé sur un thread à part, peut bloquer (le temps du déplacement). Renvoie la réponse de l'outil. */
    private val onTool: (name: String, args: Map<String, Any?>) -> Map<String, Any?>,
    private val onTranscript: (pilot: Boolean, text: String) -> Unit,
    private val onTurnEnd: () -> Unit,
    private val onState: (LiveState) -> Unit,
    private val onError: (String) -> Unit,
    /** false : le micro se coupe pendant que le drone parle (si le drone s'interrompt tout seul). */
    private val bargeIn: Boolean,
) {
    companion object {
        private const val TAG = "GeminiLive"
        private const val URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
        // Même liste que Vr2 : si un modèle refuse la connexion, on essaie le suivant.
        val MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-latest")
        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000

        fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> unwrap(o.opt(k)) }
        private fun unwrap(v: Any?): Any? = when (v) {
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
            JSONObject.NULL -> null
            else -> v
        }
        fun toJson(v: Any?): Any? = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
            is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
            is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
            else -> v
        }
    }

    @Volatile var active = false
        private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var setupDone = false
    @Volatile private var handle: String? = null
    private var modelIndex = 0
    private val failures = ArrayDeque<Long>()

    private val audio = context.getSystemService(AudioManager::class.java)
    private var oldMode = AudioManager.MODE_NORMAL
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var framesWritten = 0L
    @Volatile private var playGeneration = 0
    @Volatile private var lastState = LiveState.OFF
    private val toolRunner = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---- démarrage / arrêt ------------------------------------------------------------------
    fun start() {
        if (active) return
        active = true
        modelIndex = 0
        failures.clear()
        state(LiveState.CONNECTING)
        try {
            startAudio()
        } catch (e: Exception) {
            active = false
            stopAudio()
            onError("Micro ou haut-parleur indisponible : ${e.message}")
            state(LiveState.OFF)
            return
        }
        connect()
        thread(name = "live-frames", isDaemon = true) { frameLoop() }
        thread(name = "live-state", isDaemon = true) { stateLoop() }
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { ws?.close(1000, "fin") }
        ws = null
        stopAudio()
        state(LiveState.OFF)
    }

    private fun state(s: LiveState) {
        if (s != lastState) {
            lastState = s
            onState(s)
        }
    }

    private fun connect() {
        setupDone = false
        val net = network()
        // Par la 4G : le Wi-Fi du drone n'a pas Internet.
        val client = http.newBuilder().apply {
            if (net != null) {
                socketFactory(net.socketFactory)
                dns(object : okhttp3.Dns { override fun lookup(hostname: String) = net.getAllByName(hostname).toList() })
            }
        }.build()
        val req = Request.Builder().url(URL + apiKey).build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, "$code $reason")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = response?.let { "${it.code} ${it.message}" } ?: (t.message ?: t.javaClass.simpleName)
            closed(webSocket, why)
        }
    }

    private fun closed(socket: WebSocket, why: String) {
        if (socket !== ws) return // ancienne connexion
        ws = null
        if (!active) return
        Log.w(TAG, "fermé : $why (${MODELS[modelIndex]})")
        friendly(why)?.let {
            onError(it)
            stop()
            return
        }
        val now = System.currentTimeMillis()
        while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
        failures.addLast(now)
        if (failures.size > 8) {
            onError("La conversation se coupe sans arrêt ($why). Réessaie dans un instant.")
            stop()
            return
        }
        val wasReady = setupDone
        if (!wasReady) {
            // Refus avant la mise en route : modèle suivant.
            modelIndex = (modelIndex + 1) % MODELS.size
            handle = null
        }
        state(LiveState.CONNECTING)
        thread(isDaemon = true) {
            Thread.sleep(if (wasReady) 300 else 1500)
            if (active) connect()
        }
    }

    /** Erreurs à expliquer au pilote (inutile de réessayer). */
    private fun friendly(why: String): String? = when {
        Regex("API key not valid|API_KEY_INVALID|PERMISSION_DENIED", RegexOption.IGNORE_CASE).containsMatchIn(why) ->
            "Clé Gemini refusée par Google : vérifie-la dans les réglages (⚙)."
        Regex("PerDay|quota", RegexOption.IGNORE_CASE).containsMatchIn(why) ->
            "Quota gratuit Gemini atteint : réessaie plus tard (ou demain)."
        Regex("Unable to resolve host|UnknownHost|Failed to connect", RegexOption.IGNORE_CASE).containsMatchIn(why) && failures.size >= 3 ->
            "Impossible de joindre Gemini : vérifie que les données mobiles (4G) sont activées."
        else -> null
    }

    private fun setupMessage(): JSONObject {
        val setup = JSONObject()
            .put("model", "models/${MODELS[modelIndex]}")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Sans ça, audio + vidéo = 2 minutes de conversation au maximum.
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().apply { handle?.let { put("handle", it) } })
        return JSONObject().put("setup", setup)
    }

    // ---- messages du serveur ----------------------------------------------------------------
    private fun handle(socket: WebSocket, text: String) {
        if (socket !== ws) return
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        if (m.has("setupComplete")) {
            setupDone = true
            state(LiveState.LISTENING)
        }
        m.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable") && u.has("newHandle")) handle = u.optString("newHandle")
        }
        if (m.has("goAway")) socket.close(1000, "goAway") // on reprend la session aussitôt
        m.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) stopPlayback()
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onTranscript(true, it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onTranscript(false, it) }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) playQueue.add(Base64.decode(data, Base64.DEFAULT))
            }
            if (sc.optBoolean("turnComplete")) onTurnEnd()
        }
        m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val fc = calls.optJSONObject(i) ?: continue
                val id = fc.optString("id")
                val name = fc.optString("name")
                val args = fc.optJSONObject("args")?.let { toMap(it) } ?: emptyMap()
                toolRunner.execute {
                    val result = try {
                        onTool(name, args)
                    } catch (e: Exception) {
                        mapOf("result" to "échec : ${e.message}")
                    }
                    val resp = JSONObject().put("id", id).put("name", name).put("response", toJson(result))
                    send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(resp))))
                }
            }
        }
    }

    private fun send(o: JSONObject) {
        val w = ws ?: return
        if (setupDone) w.send(o.toString())
    }

    // ---- ce que l'application envoie --------------------------------------------------------
    private fun clientText(text: String, turnComplete: Boolean) {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", turnComplete)))
    }

    /** Message écrit par le pilote : comme s'il l'avait dit. */
    fun ask(text: String) = clientText(text, true)

    /** Fait dire quelque chose à l'IA (une seule voix : la sienne). */
    fun say(text: String) = clientText(
        "[Message de l'application, pas du pilote] Dis-lui ceci, en une phrase courte et naturelle : $text", true,
    )

    /** Signale un événement à l'IA ; [reply] : elle doit y répondre. */
    fun event(text: String, reply: Boolean) = clientText("[Message de l'application, pas du pilote] $text", reply)

    // ---- image de la caméra, ~1 par seconde -------------------------------------------------
    private fun frameLoop() {
        while (active) {
            val t0 = System.currentTimeMillis()
            if (setupDone) {
                val jpeg = runCatching { eyes.captureJpegBase64() }.getOrNull()
                // Image vide (vidéo pas prête) : le serveur couperait la connexion.
                if (jpeg != null && jpeg.length > 2000) {
                    send(JSONObject().put("realtimeInput", JSONObject().put("video",
                        JSONObject().put("data", jpeg).put("mimeType", "image/jpeg"))))
                }
            }
            val wait = 1000 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
    }

    // ---- audio ------------------------------------------------------------------------------
    private val speaking: Boolean
        get() {
            val t = track ?: return false
            return playQueue.isNotEmpty() || (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten
        }

    private fun stateLoop() {
        while (active) {
            if (setupDone) state(if (speaking) LiveState.SPEAKING else LiveState.LISTENING)
            Thread.sleep(150)
        }
    }

    @SuppressLint("MissingPermission") // la permission micro est demandée avant d'ouvrir la conversation
    private fun startAudio() {
        // Mode « appel » : active l'annulation d'écho du téléphone, pour pouvoir couper la parole au drone.
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToSpeaker()

        val minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, IN_RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("micro occupé")
        }
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { setEnabled(true) }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(rec.audioSessionId)?.apply { setEnabled(true) }
        record = rec

        val minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minOut, OUT_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = tr
        framesWritten = 0
        tr.play()
        rec.startRecording()
        thread(name = "live-mic", isDaemon = true) { micLoop(rec) }
        thread(name = "live-speaker", isDaemon = true) { speakerLoop(tr) }
    }

    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            // Casque ou écouteurs branchés : on les garde. Sinon haut-parleur (pas l'écouteur d'appel).
            val preferred = listOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
            val device = preferred.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            if (device != null) audio.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !audio.isWiredHeadsetOn
        }
    }

    private fun micLoop(rec: AudioRecord) {
        val buf = ShortArray(IN_RATE / 10) // 100 ms
        val bytes = ByteArray(buf.size * 2)
        var quietUntil = 0L
        while (active && record === rec) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            if (!setupDone) continue
            if (!bargeIn) {
                // Micro coupé pendant que le drone parle (et un court instant après).
                if (speaking) quietUntil = System.currentTimeMillis() + 400
                if (System.currentTimeMillis() < quietUntil) continue
            }
            for (i in 0 until n) {
                bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
            }
            val b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP)
            send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$IN_RATE"))))
        }
    }

    private fun speakerLoop(tr: AudioTrack) {
        while (active && track === tr) {
            val chunk = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val gen = playGeneration
            var off = 0
            while (off < chunk.size && active && gen == playGeneration) {
                val w = tr.write(chunk, off, chunk.size - off)
                if (w <= 0) break
                off += w
                framesWritten += w / 2
            }
        }
    }

    /** Le pilote coupe la parole : on jette ce qui reste à dire. */
    private fun stopPlayback() {
        playGeneration++
        playQueue.clear()
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                framesWritten = 0
                // Après flush, la position de lecture repart de zéro.
                t.play()
            }
        }
    }

    private fun stopAudio() {
        val rec = record
        val tr = track
        record = null
        track = null
        playQueue.clear()
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { echo?.release() }
        runCatching { noise?.release() }
        echo = null
        noise = null
        runCatching { tr?.stop() }
        runCatching { tr?.release() }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = oldMode
        }
    }
}
