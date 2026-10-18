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
import com.bebopia.app.android.NetworkRouter
import com.bebopia.app.android.PreviewEyes
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
    val network: String = "",
    val chat: List<ChatLine> = emptyList(),
    val confirm: Boolean = true,
    val speak: Boolean = true,
    val hasApiKey: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("bebopia", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        private set(v) = prefs.edit().putString("api_key", v.trim()).apply()
    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        private set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var confirm: Boolean
        get() = prefs.getBoolean("confirm", true)
        private set(v) = prefs.edit().putBoolean("confirm", v).apply()
    var speak: Boolean
        get() = prefs.getBoolean("speak", true)
        private set(v) = prefs.edit().putBoolean("speak", v).apply()

    private val limits = Limits()
    private val router = NetworkRouter(app).also { it.start() }
    @Volatile private var listening = false
    private val voice = VoiceIO(
        app,
        onText = { send(it) },
        onStatus = { sysMessage(it) },
        onListening = { listening = it; refresh() },
    )

    @Volatile private var mode = Mode.NONE
    @Volatile private var drone: DroneBase? = null
    @Volatile private var video: BebopVideo? = null
    @Volatile private var agent: Agent? = null
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
        sysMessage("Connecte le téléphone au Wi-Fi du Bebop (Skycontroller éteinte), puis appuie sur « Connecter le Bebop ». Ou essaie la simulation avec la caméra du téléphone.")
        if (apiKey.isBlank()) sysMessage("Ajoute ta clé API Claude dans les réglages (⚙) avant de commencer.")
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
            network = router.describe,
            chat = a?.chat ?: synchronized(localChat) { localChat.toList() },
            confirm = confirm,
            speak = speak,
            hasApiKey = apiKey.isNotBlank(),
        )
    }

    private fun makeAgent(d: DroneBase, eyes: Eyes): Agent {
        val brain = Brain { image, text -> ClaudeBrain(apiKey, model, router::openApi).decide(image, text) }
        val a = Agent(d, eyes, brain, voice, confirm = confirm, log = ::log)
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
        val d = SimDrone(limits, ::log)
        d.connect()
        val a = makeAgent(d, PreviewEyes { previewView })
        drone = d
        agent = a
        mode = Mode.SIM
        a.start()
        a.addChat(Role.SYSTEM, "Simulation : la caméra du téléphone remplace celle du drone, rien ne vole. Parle à l'IA ou appuie sur « ▶ IA ».")
        refresh()
    }

    fun disconnect() {
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
        if (it.paused && apiKey.isBlank()) sysMessage("Ajoute d'abord ta clé API Claude dans les réglages (⚙).")
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
    fun micPressed() = voice.toggleListening()

    fun saveSettings(key: String, model: String, confirm: Boolean, speak: Boolean) {
        if (key.isNotBlank()) apiKey = key
        this.model = model
        this.confirm = confirm
        this.speak = speak
        voice.speakEnabled = speak
        agent?.confirm = confirm
        refresh()
    }

    override fun onCleared() {
        disconnect()
        voice.release()
        router.stop()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
