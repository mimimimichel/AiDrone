package com.bebopia.app

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bebopia.app.android.BebopVideo
import com.bebopia.app.android.GeminiLive
import com.bebopia.app.android.LiveState
import com.bebopia.app.android.VoiceState
import com.bebopia.app.android.NetworkRouter
import com.bebopia.app.android.PreviewEyes
import com.bebopia.app.android.PreviewGray
import com.bebopia.app.android.TextureGray
import com.bebopia.app.core.vision.GrayFrames
import com.bebopia.app.core.vision.ObstacleGuard
import com.bebopia.app.android.TextureEyes
import com.bebopia.app.android.VoiceIO
import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.SimDrone
import com.bebopia.app.core.ai.Agent
import com.bebopia.app.core.ai.Brain
import com.bebopia.app.core.ai.ChatLine
import com.bebopia.app.core.ai.ClaudeBrain
import com.bebopia.app.core.ai.Eyes
import com.bebopia.app.core.ai.LivePrompt
import com.bebopia.app.core.ai.Speaker
import org.json.JSONArray
import com.bebopia.app.core.ai.Role
import com.bebopia.app.core.bebop.ArNetwork
import com.bebopia.app.core.bebop.BebopDrone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

enum class Mode { NONE, CONNECTING, BEBOP, SIM }

data class UiState(
    val mode: Mode = Mode.NONE,
    val paused: Boolean = true,
    val thinking: Boolean = false,
    val pending: String? = null,
    val idle: Boolean = false,
    val steps: Int = 0,
    val maxSteps: Int = 0,
    val lastText: String = "",
    val battery: Int? = null,
    val altitude: Double = 0.0,
    val distance: Double = 0.0,
    val flying: Boolean = false,
    val droneState: String = "",
    val linkUp: Boolean = true,
    val hasVideo: Boolean = false,
    val listening: Boolean = false,
    val voice: String = "",
    val network: String = "",
    val chat: List<ChatLine> = emptyList(),
    val confirm: Boolean = true,
    val speak: Boolean = true,
    val hasApiKey: Boolean = false,
    val hasGeminiKey: Boolean = false,
    val hasClaudeKey: Boolean = false,
    val bargeIn: Boolean = true,
    val maxStep: Double = 1.0,
    val guardOn: Boolean = true,
    val robotVoice: Boolean = true,
    val guardWatching: Boolean = false,
    val obstacle: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("bebopia", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        private set(v) = prefs.edit().putString("api_key", v.trim()).apply()
    /** Clé Gemini (gratuite sur aistudio.google.com) : conversation vocale en direct. */
    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        private set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()
    var bargeIn: Boolean
        get() = prefs.getBoolean("barge_in", true)
        private set(v) = prefs.edit().putBoolean("barge_in", v).apply()
    private val useLive get() = geminiKey.isNotBlank()
    /** Distance max par action, réglable de 0,2 à 1 m. */
    var maxStep: Double
        get() = prefs.getFloat("max_step", 1.0f).toDouble().coerceIn(0.2, 1.0)
        private set(v) = prefs.edit().putFloat("max_step", v.coerceIn(0.2, 1.0).toFloat()).apply()
    var robotVoice: Boolean
        get() = prefs.getBoolean("robot_voice", true)
        private set(v) = prefs.edit().putBoolean("robot_voice", v).apply()
    var guardOn: Boolean
        get() = prefs.getBoolean("guard", true)
        private set(v) = prefs.edit().putBoolean("guard", v).apply()
    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        private set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var confirm: Boolean
        get() = prefs.getBoolean("confirm", true)
        private set(v) = prefs.edit().putBoolean("confirm", v).apply()
    var speak: Boolean
        get() = prefs.getBoolean("speak", true)
        private set(v) = prefs.edit().putBoolean("speak", v).apply()

    private val limits = Limits().also { it.maxStepM = maxStep }
    @Volatile private var guard: ObstacleGuard? = null
    @Volatile private var obstacleAt = 0L
    private val router = NetworkRouter(app).also { it.start() }
    @Volatile private var listening = false
    @Volatile private var voiceLabel = ""
    private val voice = VoiceIO(
        app,
        onText = { send(it) },
        onStatus = { sysMessage(it) },
        onState = { st, heard ->
            listening = st != VoiceState.OFF
            voiceLabel = when (st) {
                VoiceState.OFF -> ""
                VoiceState.LISTENING -> "🎤 Je t'écoute…"
                VoiceState.HEARD -> "🎤 « $heard »"
                VoiceState.SPEAKING -> "🔊 Le drone parle"
            }
            refresh()
        },
    )
    @Volatile private var live: GeminiLive? = null

    /** La voix du drone : celle de Gemini pendant une conversation en direct, sinon la synthèse vocale du téléphone. */
    private val speaker = Speaker { text ->
        val l = live
        if (l != null && l.active) l.say(text) else voice.say(text)
    }

    @Volatile private var mode = Mode.NONE
    @Volatile private var drone: DroneBase? = null
    @Volatile private var video: BebopVideo? = null
    @Volatile private var agent: Agent? = null
    @Volatile private var agentEyes: Eyes? = null
    private val localChat = mutableListOf<ChatLine>()

    // Vues fournies par l'interface
    @Volatile var textureView: TextureView? = null
    @Volatile var previewView: PreviewView? = null
    @Volatile private var surface: Surface? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        voice.init()
        voice.speakEnabled = speak
        voice.robot = robotVoice
        sysMessage("Connecte le téléphone au Wi-Fi du Bebop (Skycontroller éteinte), puis appuie sur « Connecter le Bebop ». Ou essaie la simulation avec la caméra du téléphone.")
        if (geminiKey.isBlank() && apiKey.isBlank()) {
            sysMessage("Ajoute ta clé Gemini dans les réglages (⚙) : elle est gratuite sur aistudio.google.com/apikey (la même que pour Vr2).")
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(400)
            }
        }
    }

    private fun log(msg: String) {
        Log.i("BebopIA", msg)
        if (msg.startsWith("ALERTE") || msg.startsWith("vidéo : images") || msg.startsWith("liaison")) sysMessage(msg)
    }

    fun sysMessage(text: String) {
        val a = agent
        if (a != null) {
            a.addChat(Role.SYSTEM, text)
        } else {
            synchronized(localChat) { localChat.add(ChatLine(Role.SYSTEM, text)) }
        }
        refresh()
    }

    fun refresh() {
        val a = agent
        val d = drone
        val bebop = d as? BebopDrone
        _state.value = UiState(
            mode = mode,
            paused = a?.paused ?: true,
            thinking = a?.thinking ?: false,
            pending = a?.pending?.let { Agent.describe(it) },
            idle = a?.idle ?: false,
            steps = a?.steps ?: 0,
            maxSteps = limits.maxSteps,
            lastText = a?.lastText ?: "",
            battery = d?.battery(),
            altitude = d?.altitude() ?: 0.0,
            distance = d?.distanceFromHome() ?: 0.0,
            flying = d?.flying ?: false,
            droneState = bebop?.flyingStateLabel ?: if (d != null) (if (d.flying) "en vol" else "au sol") else "",
            linkUp = bebop?.linkUp ?: true,
            hasVideo = video?.hasRecentFrame ?: (mode == Mode.SIM),
            listening = listening,
            voice = voiceLabel,
            network = router.describe,
            chat = a?.chat ?: synchronized(localChat) { localChat.toList() },
            confirm = confirm,
            speak = speak,
            hasApiKey = geminiKey.isNotBlank() || apiKey.isNotBlank(),
            hasGeminiKey = geminiKey.isNotBlank(),
            hasClaudeKey = apiKey.isNotBlank(),
            bargeIn = bargeIn,
            maxStep = limits.maxStepM,
            guardOn = guardOn,
            robotVoice = robotVoice,
            guardWatching = guard?.watching ?: false,
            obstacle = System.currentTimeMillis() - obstacleAt < 3000,
        )
    }

    private fun startGuard(d: DroneBase, a: Agent, frames: GrayFrames) {
        guard?.stop()
        guard = ObstacleGuard(d, frames, enabled = { guardOn }, onObstacle = { ttc ->
            obstacleAt = System.currentTimeMillis()
            a.obstacleStop(ttc)
        }, log = ::log).also { it.start() }
    }

    private fun makeAgent(d: DroneBase, eyes: Eyes): Agent {
        // Avec une clé Gemini : conversation en direct (pas de cerveau « question/réponse »).
        val brain = if (useLive) null else Brain { image, text -> ClaudeBrain(apiKey, model, router::openApi).decide(image, text) }
        val a = Agent(d, eyes, brain, speaker, confirm = confirm, log = ::log)
        if (brain == null) {
            a.forward = { text ->
                val l = live
                if (l != null && l.active) l.ask(text)
                else a.addChat(Role.SYSTEM, "Appuie sur 🎤 pour ouvrir la conversation avec le drone.")
            }
            a.onEvent = { text, reply -> live?.takeIf { it.active }?.event(text, reply) }
        }
        agentEyes = eyes
        synchronized(localChat) {
            localChat.forEach { a.addChat(it.role, it.text) }
            localChat.clear()
        }
        a.onChange = { refresh() }
        return a
    }

    // ---- connexion ------------------------------------------------------------------------
    fun connectBebop() {
        if (mode != Mode.NONE) return
        mode = Mode.CONNECTING
        sysMessage("Connexion au Bebop… (réseau : ${router.describe})")
        viewModelScope.launch(Dispatchers.IO) {
            val d = BebopDrone(limits, router.droneBinder, ::log)
            try {
                d.connect()
                val v = BebopVideo(router.droneBinder, ArNetwork.DEFAULT_HOST, d.streamControlPort, ::log)
                v.surface = surface
                v.start()
                val a = makeAgent(d, TextureEyes({ textureView }, { v.hasRecentFrame }))
                d.onLinkLost = { a.linkLost() }
                startGuard(d, a, TextureGray({ textureView }, { v.hasRecentFrame }))
                drone = d
                video = v
                agent = a
                mode = Mode.BEBOP
                a.start()
                a.addChat(Role.SYSTEM, "Connecté au Bebop (batterie ${d.battery() ?: "?"} %). L'IA est en pause : parle-lui, ou appuie sur « ▶ IA » pour la laisser piloter.")
                if (router.cellular == null) {
                    a.addChat(Role.SYSTEM, "Attention : pas de 4G détectée, l'IA ne pourra pas joindre Claude. Active les données mobiles.")
                }
            } catch (e: Exception) {
                runCatching { d.disconnect() }
                mode = Mode.NONE
                sysMessage("Connexion impossible : ${e.message ?: e.javaClass.simpleName}. Vérifie que le téléphone est sur le Wi-Fi du Bebop et que la Skycontroller est éteinte.")
            }
            refresh()
        }
    }

    fun startSimulation() {
        if (mode != Mode.NONE) return
        try {
            val d = SimDrone(limits, ::log)
            d.connect()
            val a = makeAgent(d, PreviewEyes { previewView })
            startGuard(d, a, PreviewGray { previewView })
            drone = d
            agent = a
            mode = Mode.SIM
            a.start()
            a.addChat(Role.SYSTEM, "Simulation : la caméra du téléphone remplace celle du drone, rien ne vole. Parle à l'IA ou appuie sur « ▶ IA ».")
        } catch (e: Throwable) {
            Log.e("BebopIA", "simulation", e)
            guard?.stop()
            guard = null
            agent = null
            drone = null
            mode = Mode.NONE
            sysMessage("Impossible de lancer la simulation : ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
        refresh()
    }

    fun disconnect() {
        stopLive()
        guard?.stop()
        guard = null
        val a = agent
        val d = drone
        val v = video
        agent = null
        drone = null
        video = null
        mode = Mode.NONE
        a?.stop()
        a?.chat?.let { lines -> synchronized(localChat) { localChat.clear(); localChat.addAll(lines) } }
        thread(isDaemon = true) {
            if (d?.flying == true) {
                sysMessage("Atterrissage avant déconnexion…")
                runCatching { d.land() }
            }
            v?.stop()
            runCatching { d?.disconnect() }
        }
        sysMessage("Déconnecté.")
    }

    // ---- interface -------------------------------------------------------------------------
    fun onSurface(s: Surface?) {
        surface = s
        video?.surface = s
    }

    private fun withAgent(block: (Agent) -> Unit) {
        val a = agent
        if (a == null) sysMessage("Connecte d'abord le drone ou lance la simulation.") else block(a)
        refresh()
    }

    fun toggleAi() = withAgent {
        if (it.paused && geminiKey.isBlank() && apiKey.isBlank()) sysMessage("Ajoute d'abord ta clé Gemini dans les réglages (⚙).")
        else it.togglePause()
    }
    fun takeoff() = withAgent { it.humanTakeoff() }
    fun land() = withAgent { it.humanLand() }
    fun emergency() = withAgent { it.humanEmergency() }
    fun approve() = withAgent { it.approve() }
    fun reject() = withAgent { it.reject() }
    fun send(text: String) {
        if (text.isBlank()) return
        withAgent { it.hear(text.trim()) }
    }
    fun micPressed() {
        if (!useLive) {
            voice.toggleConversation()
            return
        }
        if (live != null) {
            stopLive()
            return
        }
        withAgent { a -> startLive(a) }
    }

    // ---- conversation en direct (Gemini Live) ---------------------------------------------
    private fun startLive(a: Agent) {
        val d = drone ?: return
        val eyes = agentEyes ?: return
        val l = GeminiLive(
            context = getApplication(),
            apiKey = geminiKey,
            network = { router.cellular },
            eyes = eyes,
            systemPrompt = LivePrompt.SYSTEM + if (robotVoice) LivePrompt.ROBOT_STYLE else "",
            tools = GeminiLive.toJson(LivePrompt.TOOLS) as JSONArray,
            onTool = { name, args ->
                when (name) {
                    "drone_action" -> {
                        val r = a.aiAction(args)
                        // Image prise juste après le mouvement : l'IA décide la suite sur ce qu'elle voit maintenant.
                        live?.sendFrameNow()
                        mapOf("result" to r, "etat" to d.state())
                    }
                    "drone_status" -> d.state() + mapOf(
                        "ia" to if (a.paused) "en pause (ne peut pas bouger)" else "active",
                        "validation_par_le_pilote" to a.confirm,
                        "batterie_pourcent" to d.battery(),
                    )
                    else -> mapOf("result" to "outil inconnu")
                }
            },
            onTranscript = { pilot, text -> a.liveTranscript(pilot, text) },
            onTurnEnd = { a.liveTurnEnd() },
            onState = { st ->
                listening = st != LiveState.OFF
                voiceLabel = when (st) {
                    LiveState.OFF -> ""
                    LiveState.CONNECTING -> "🎙 Connexion à Gemini…"
                    LiveState.LISTENING -> "🎙 Conversation : je t'écoute"
                    LiveState.SPEAKING -> "🔊 Le drone parle"
                }
                if (st == LiveState.OFF && live != null) live = null
                refresh()
            },
            onError = { msg -> a.addChat(Role.SYSTEM, msg) },
            bargeIn = bargeIn,
            robot = { robotVoice },
        )
        live = l
        l.start()
        if (!l.active) {
            live = null
            return
        }
        val where = if (mode == Mode.SIM) "Simulation : tu vois par la caméra du téléphone, rien ne vole." else "Tu es connecté au vrai Bebop."
        val how = if (a.paused) "Tu es en pause : tu peux parler mais pas bouger." else "Tu peux piloter."
        val check = (if (a.confirm) " Chaque déplacement doit être validé par le pilote." else "") +
            " Distance max par action : %.1f m.".format(limits.maxStepM)
        l.event("Début de la conversation. $where $how$check Salue le pilote en une phrase courte.", true)
        a.addChat(Role.SYSTEM, "Conversation ouverte : parle normalement, tu peux couper la parole au drone. Réappuie sur 🎤 pour la fermer.")
    }

    private fun stopLive() {
        val l = live ?: return
        live = null
        l.stop()
        listening = false
        voiceLabel = ""
        refresh()
    }

    fun saveSettings(
        key: String, gemini: String, model: String, confirm: Boolean, speak: Boolean, bargeIn: Boolean,
        maxStep: Double, guardOn: Boolean, robotVoice: Boolean,
    ) {
        this.robotVoice = robotVoice
        voice.robot = robotVoice
        val wasLive = useLive
        val stepChanged = Math.abs(maxStep - limits.maxStepM) > 0.01
        this.maxStep = maxStep
        limits.maxStepM = this.maxStep
        this.guardOn = guardOn
        if (stepChanged) {
            live?.takeIf { it.active }?.event("Le pilote a réglé la distance max par action à %.1f m.".format(limits.maxStepM), false)
        }
        if (key.isNotBlank()) apiKey = key
        if (gemini.isNotBlank()) geminiKey = gemini
        this.bargeIn = bargeIn
        this.model = model
        this.confirm = confirm
        this.speak = speak
        voice.speakEnabled = speak
        agent?.confirm = confirm
        if (agent != null && wasLive != useLive) {
            sysMessage("Déconnecte puis reconnecte le drone (ou la simulation) pour utiliser la nouvelle clé.")
        }
        refresh()
    }

    override fun onCleared() {
        stopLive()
        disconnect()
        voice.release()
        router.stop()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
