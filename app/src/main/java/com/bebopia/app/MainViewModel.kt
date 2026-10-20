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
import com.bebopia.app.android.GeminiVision
import com.bebopia.app.android.HeadsetStream
import com.bebopia.app.core.ai.ObjectFinder
import com.bebopia.app.dji.DjiDrone
import com.bebopia.app.dji.DjiLink
import android.graphics.SurfaceTexture
import com.bebopia.app.core.ai.AreaSearch
import com.bebopia.app.core.ai.FlightEntry
import com.bebopia.app.core.ai.FlightLog
import com.bebopia.app.core.ai.Follower
import com.bebopia.app.core.ai.Memory
import com.bebopia.app.core.ai.Navigator
import com.bebopia.app.core.ai.Shots
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
import com.bebopia.app.core.ai.Plan
import com.bebopia.app.core.ai.StepState
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

enum class Mode { NONE, CONNECTING, BEBOP, MAVIC, SIM }

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
    val ceiling: Double = 3.0,
    val radius: Double = 15.0,
    val guardWatching: Boolean = false,
    val guardTtc: Double? = null,
    val plan: Plan? = null,
    val headsetOn: Boolean = false,
    val headsetUrl: String = "",
    val headsetViewers: Int = 0,
    val obstacle: Boolean = false,
    val memoryCount: Int = 0,
    val waypointCount: Int = 0,
    val activity: String? = null,
    val recording: Boolean = false,
    val autoRecord: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("bebopia", Context.MODE_PRIVATE)
    private val memory = Memory(java.io.File(app.filesDir, "memoire.json"))
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
    var ceiling: Double
        get() = prefs.getFloat("ceiling", 3f).toDouble().coerceIn(2.0, 120.0)
        private set(v) = prefs.edit().putFloat("ceiling", v.coerceIn(2.0, 120.0).toFloat()).apply()
    var radius: Double
        get() = prefs.getFloat("radius", 15f).toDouble().coerceIn(5.0, 500.0)
        private set(v) = prefs.edit().putFloat("radius", v.coerceIn(5.0, 500.0).toFloat()).apply()
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
    /** Filmer chaque vol en entier sur la carte du drone. */
    var autoRecord: Boolean
        get() = prefs.getBoolean("auto_record", false)
        private set(v) = prefs.edit().putBoolean("auto_record", v).apply()

    private val limits = Limits().also {
        it.maxStepM = maxStep
        it.maxAltitudeM = ceiling
        it.maxDistanceM = radius
    }
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

    /** Éclaircit l'affichage de la vidéo du drone quand l'image est sombre (mesure toutes les 1,5 s). */
    private fun startDisplayGain() {
        viewModelScope.launch(Dispatchers.Default) {
            var applied = 1f
            while (isActive) {
                delay(1500)
                if (mode != Mode.MAVIC && mode != Mode.BEBOP) continue
                val tv = textureView ?: continue
                val bmp = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (tv.isAvailable) runCatching { tv.getBitmap(64, 36) }.getOrNull() else null
                } ?: continue
                val gain = com.bebopia.app.android.brightnessGain(com.bebopia.app.android.meanLuma(bmp))
                bmp.recycle()
                // Lissé, et seulement si ça change vraiment (évite le scintillement)
                val target = applied * 0.5f + gain * 0.5f
                if (kotlin.math.abs(target - applied) > 0.08f) {
                    applied = target
                    val filter = com.bebopia.app.android.gainFilter(applied)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        tv.setLayerPaint(filter?.let { f -> android.graphics.Paint().apply { colorFilter = f } })
                    }
                }
            }
        }
    }

    init {
        startDisplayGain()
        startNavTicks()
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
            linkUp = bebop?.linkUp ?: if (mode == Mode.MAVIC) djiOrNull?.isConnected() ?: false else true,
            hasVideo = video?.hasRecentFrame ?: if (mode == Mode.MAVIC) djiOrNull?.hasRecentFrame() ?: false else (mode == Mode.SIM),
            listening = listening,
            voice = voiceLabel,
            network = if (mode == Mode.MAVIC) djiOrNull?.videoStats() ?: router.describe else router.describe,
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
            ceiling = limits.maxAltitudeM,
            radius = limits.maxDistanceM,
            guardWatching = guard?.watching ?: false,
            guardTtc = guard?.lastTtcS,
            plan = a?.plan,
            headsetOn = headset != null,
            headsetUrl = headset?.urls()?.firstOrNull() ?: "",
            headsetViewers = headset?.viewerCount ?: 0,
            obstacle = System.currentTimeMillis() - obstacleAt < 3000,
            memoryCount = memory.size,
            waypointCount = nav?.waypointCount() ?: 0,
            activity = a?.activity,
            recording = d?.recording ?: false,
            autoRecord = autoRecord,
        )
    }

    private val vision = GeminiVision({ geminiKey }, router::openApi)

    /** [hfov] : champ de vision horizontal de la caméra (Bebop 75°, Mavic Mini 72°, téléphone 65°). */
    private fun attachFinder(d: DroneBase, a: Agent, eyes: Eyes, simulation: Boolean, hfov: Double = if (simulation) 65.0 else 75.0, track: GrayFrames? = null) {
        // Avant chaque avancée commandée par l'IA : obstacle dans l'axe ? (modèle de vision)
        a.pathChecker = com.bebopia.app.core.ai.PathChecker { jpeg, fwd -> vision.checkPath(jpeg, fwd) }
        a.finder = ObjectFinder(
            d, eyes, vision,
            hfovDeg = hfov,
            simulation = simulation,
            shouldStop = { a.paused },
            report = { a.addChat(Role.SYSTEM, it) },
        )
        a.areaSearch = AreaSearch(
            d, eyes, vision,
            hfovDeg = hfov,
            simulation = simulation,
            shouldStop = { a.paused },
            report = { a.addChat(Role.SYSTEM, it) },
            onPlan = { a.missionPlan(it) },
        )
        // Détecteur d'objets du téléphone (sans Internet) : recherche et suivi plus rapides
        localDetector?.close()
        val ld = com.bebopia.app.android.LocalDetector(getApplication(), frame = {
            if (simulation) previewView?.bitmap?.let { b ->
                val w = 640
                val h = (w.toFloat() * b.height / b.width).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
            } else textureView?.takeIf { it.isAvailable }?.getBitmap(640, 360)
        }, log = { a.addChat(Role.SYSTEM, it) })
        localDetector = ld
        a.finder?.fast = ld
        a.areaSearch?.fast = ld
        // Carte, suivi, cinéma, journal de vol
        val n = Navigator(d, simulation, shouldStop = { a.paused }, report = { a.addChat(Role.SYSTEM, it) },
            save = { prefs.edit().putString("waypoints", it).apply() })
        prefs.getString("waypoints", null)?.let { n.load(it) }
        n.pathCheck = { fwd -> a.safeForward(fwd) }
        a.nav = n
        a.areaSearch?.nav = n
        nav = n
        a.follower = Follower(d, eyes, vision, track, hfovDeg = hfov, simulation = simulation, shouldStop = { a.paused },
            report = { a.addChat(Role.SYSTEM, it) }, onSeen = { t -> n.addPin(t, true, null) }).also { it.fast = ld }
        a.shots = Shots(d, simulation, shouldStop = { a.paused }, report = { a.addChat(Role.SYSTEM, it) },
            pathCheck = { { fwd: Double -> a.safeForward(fwd) } })
        val log = FlightLog(
            java.io.File(getApplication<Application>().filesDir, "vols").also { it.mkdirs() }, d, eyes,
            summarize = { prompt -> if (geminiKey.isBlank()) null else vision.text(prompt) },
            onSaved = { summary, title ->
                memory.add("Vol du $title : $summary", d.homeGps())
                a.addChat(Role.SYSTEM, "📔 Vol enregistré dans le journal ($title) : $summary")
            },
        )
        a.onLine = { role, text -> log.line(role, text) }
        a.onSnap = { label -> thread(isDaemon = true) { log.snap(label) } }
        flightLog = log
    }

    // ---- carte, journal, mémoire -------------------------------------------------------------
    @Volatile private var nav: Navigator? = null
    @Volatile private var localDetector: com.bebopia.app.android.LocalDetector? = null
    @Volatile private var flightLog: FlightLog? = null
    @Volatile private var weakSignalSince = 0L
    @Volatile private var recordedFlight = false

    /** Une fois par seconde : trace, journal, signal radio, enregistrement du vol. */
    private fun startNavTicks() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                val d = drone ?: continue
                runCatching { nav?.tick() }
                runCatching { flightLog?.tick() }
                // Filmer tout le vol
                if (d.flying && autoRecord && !recordedFlight) {
                    recordedFlight = true
                    d.startRecording()?.let { sysMessage("Enregistrement du vol impossible : $it") }
                } else if (!d.flying && recordedFlight) {
                    recordedFlight = false
                    if (d.recording) runCatching { d.stopRecording() }
                }
                // Signal radio faible loin du départ : retour par le même chemin.
                val q = d.signalQuality()
                if (q != null && q < 25 && d.flying && d.distanceFromHome() > 10) {
                    if (weakSignalSince == 0L) weakSignalSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - weakSignalSince > 4000) {
                        weakSignalSince = 0L
                        agent?.autoReturn("signal radio faible ($q %)")
                    }
                } else weakSignalSince = 0L
            }
        }
    }

    fun flights(): List<FlightEntry> = flightLog?.list()
        ?: FlightLog(java.io.File(getApplication<Application>().filesDir, "vols"), SimDrone(limits), Eyes { null }, { null }, { _, _ -> }).list()

    fun clearMemory() {
        memory.clear()
        sysMessage("🧠 Mémoire de l'IA effacée.")
    }

    fun clearWaypoints() {
        nav?.clearWaypoints() ?: prefs.edit().remove("waypoints").apply()
        sysMessage("📍 Points de la carte effacés.")
    }

    fun changeAutoRecord(on: Boolean) {
        autoRecord = on
        refresh()
    }

    private fun startGuard(d: DroneBase, a: Agent, frames: GrayFrames) {
        guard?.stop()
        guard = ObstacleGuard(d, frames, enabled = { guardOn }, onObstacle = { ttc, moving ->
            obstacleAt = System.currentTimeMillis()
            a.obstacleStop(ttc, moving)
        }, log = ::log).also { it.start() }
    }

    private fun makeAgent(d: DroneBase, eyes: Eyes): Agent {
        // Avec une clé Gemini : conversation en direct (pas de cerveau « question/réponse »).
        // Choisi à chaque étape : coller la clé Gemini en cours de route suffit.
        val claude = Brain { image, text -> ClaudeBrain(apiKey, model, router::openApi).decide(image, text) }
        val a = Agent(d, eyes, { if (useLive) null else claude }, speaker, confirm = confirm, log = ::log)
        a.forward = { text ->
            val l = live
            if (l != null && l.active) l.ask(text)
            else a.addChat(Role.SYSTEM, "Appuie sur 🎤 pour ouvrir la conversation avec le drone.")
        }
        a.onEvent = { text, reply -> live?.takeIf { it.active }?.event(text, reply) }
        a.onTrust = { on -> setTrust(on) }
        a.onHandBack = { handBack() }
        agentEyes = eyes
        synchronized(localChat) {
            localChat.forEach { a.addChat(it.role, it.text) }
            localChat.clear()
        }
        a.onChange = { refresh() }
        return a
    }

    // ---- connexion ------------------------------------------------------------------------
    // ---- Mavic Mini (kit DJI) ----------------------------------------------------------------
    // Créé seulement au premier usage du Mavic : sans kit DJI chargé, il ne doit jamais exister.
    @Volatile private var djiOrNull: DjiLink? = null
    private val dji: DjiLink get() = djiOrNull ?: DjiLink().also { djiOrNull = it }
    @Volatile private var surfaceTexture: Triple<SurfaceTexture, Int, Int>? = null

    fun connectMavic() {
        if (mode != Mode.NONE) return
        if (!com.bebopia.app.dji.DjiBoot.ready) {
            sysMessage("Le kit DJI n'a pas pu se charger sur ce téléphone (${com.bebopia.app.dji.DjiBoot.error ?: "?"}) : le Mavic n'est pas utilisable.")
            return
        }
        mode = Mode.CONNECTING
        sysMessage("Connexion au Mavic : branche la radiocommande au téléphone (câble USB), allume-la, puis allume le drone. " +
            "Si Android propose d'ouvrir une application, choisis Bebop IA. Ferme DJI Fly.")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dji.register(getApplication(), object : DjiLink.Listener {
                    override fun onStatus(message: String) = sysMessage(message)
                    override fun onProductChanged(connected: Boolean) {
                        if (!connected && mode == Mode.MAVIC) agent?.linkLost()
                        if (connected && mode == Mode.MAVIC) sysMessage("Liaison avec le drone rétablie.")
                    }
                    override fun onPilotSticks() = pilotTakeover()
                })
                val end = System.currentTimeMillis() + 120_000
                while (!dji.isConnected() && System.currentTimeMillis() < end && mode == Mode.CONNECTING) delay(500)
                if (mode != Mode.CONNECTING) return@launch
                if (!dji.isConnected()) throw IllegalStateException("drone DJI introuvable au bout de 2 minutes")
                val d = DjiDrone(limits, dji, ::log)
                d.connect()
                val eyes = TextureEyes({ textureView }, { dji.hasRecentFrame() })
                val a = makeAgent(d, eyes)
                startGuard(d, a, TextureGray({ textureView }, { dji.hasRecentFrame() }))
                attachFinder(d, a, eyes, simulation = false, hfov = 72.0, track = TextureGray({ textureView }, { dji.hasRecentFrame() }, 320))
                drone = d
                agent = a
                mode = Mode.MAVIC
                surfaceTexture?.let { (st, w, h) -> dji.startVideo(getApplication(), st, w, h) }
                a.start()
                a.addChat(Role.SYSTEM, "Connecté : ${d.name} (batterie ${d.battery() ?: "?"} %). L'IA est en pause : parle-lui, ou appuie sur « ▶ IA ». " +
                    "En intérieur, il faut un sol texturé et éclairé pour que le drone tienne sa position.")
                a.addChat(Role.SYSTEM, "État du drone : ${dji.diagnostics()}")
                // Image très sombre ? (pièce sombre, caméra masquée, ou vidéo mal décodée)
                viewModelScope.launch(Dispatchers.IO) {
                    delay(6000)
                    dji.videoStats(); delay(2000)
                    val stats = dji.videoStats()
                    val g = TextureGray({ textureView }, { true }).grab()
                    if (!dji.hasRecentFrame()) {
                        // Pas de données : on se réabonne au flux et on réessaie.
                        dji.ensureVideoListener()
                        a.addChat(Role.SYSTEM, "Le Mavic n'envoie pas de vidéo ($stats). Vérifie que DJI Fly est bien fermée " +
                            "(et pas en arrière-plan), puis débranche et rebranche le câble.")
                    } else if (g == null) {
                        a.addChat(Role.SYSTEM, "Vidéo reçue mais pas affichée ($stats).")
                    } else {
                        val mean = g.px.average()
                        if (mean < 60) {
                            // Image sombre : on demande à la caméra d'exposer plus (+1 IL).
                            val err = d.brighter(true)
                            a.addChat(Role.SYSTEM, if (err == null) "Image sombre : caméra réglée plus claire (+1), et affichage éclairci automatiquement."
                                else "Image sombre ; réglage de la caméra refusé ($err). L'affichage est éclairci automatiquement.")
                        }
                        if (mean < 25) a.addChat(Role.SYSTEM, "L'image du Mavic est très sombre (luminosité %.0f/255). ".format(mean) +
                            "Vérifie que le cache de protection de la nacelle est bien retiré et allume la lumière : " +
                            "le drone en a aussi besoin pour tenir sa position sans GPS. ($stats)")
                    }
                }
            } catch (e: Exception) {
                if (mode == Mode.CONNECTING) mode = Mode.NONE
                sysMessage("Connexion au Mavic impossible : ${e.message ?: e.javaClass.simpleName}")
            }
            refresh()
        }
    }

    /** Surface de la vidéo (le kit DJI y décode directement le flux). */
    fun onSurfaceTexture(st: SurfaceTexture?, w: Int, h: Int) {
        surfaceTexture = st?.let { Triple(it, w, h) }
        if (st == null) djiOrNull?.stopVideo()
        else if (mode == Mode.MAVIC) djiOrNull?.startVideo(getApplication(), st, w, h)
    }

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
                attachFinder(d, a, TextureEyes({ textureView }, { v.hasRecentFrame }), simulation = false, track = TextureGray({ textureView }, { v.hasRecentFrame }, 320))
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
        CrashReport.mark("simulation : début")
        try {
            val d = SimDrone(limits, ::log)
            d.connect()
            val a = makeAgent(d, PreviewEyes { previewView })
            CrashReport.mark("simulation : agent créé")
            startGuard(d, a, PreviewGray({ previewView }))
            attachFinder(d, a, PreviewEyes { previewView }, simulation = true, track = PreviewGray({ previewView }, 320))
            CrashReport.mark("simulation : détecteur lancé")
            drone = d
            agent = a
            mode = Mode.SIM
            a.start()
            a.addChat(Role.SYSTEM, "Simulation : la caméra du téléphone remplace celle du drone, rien ne vole. Parle à l'IA ou appuie sur « ▶ IA ».")
            CrashReport.mark("simulation : prête")
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
        val wasMavic = mode == Mode.MAVIC
        if (wasMavic || mode == Mode.CONNECTING) djiOrNull?.stopVideo()
        agent = null
        drone = null
        video = null
        nav = null
        flightLog = null
        localDetector?.let { ld -> thread(isDaemon = true) { ld.close() } }
        localDetector = null
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
        if (it.paused && drone?.manualControl == true) {
            handBack()
            return@withAgent
        }
        if (it.paused && useLive && live == null) {
            sysMessage("L'IA pilote pendant la conversation : appuie aussi sur 🎤 pour lui parler.")
        }
        if (it.paused && geminiKey.isBlank() && apiKey.isBlank()) sysMessage("Ajoute d'abord ta clé Gemini dans les réglages (⚙).")
        else it.togglePause()
    }
    fun takeoff() = withAgent { it.humanTakeoff() }
    fun land() = withAgent { it.humanLand() }
    fun emergency() = withAgent { it.humanEmergency() }
    fun approve() = withAgent { it.approve() }
    fun approvePlan() = withAgent { it.approvePlan() }
    fun rejectPlan() = withAgent { it.rejectPlan() }
    fun cancelPlan() = withAgent { it.cancelPlan() }
    fun clearPlan() = withAgent { it.clearPlan() }
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
    private fun startLive(a: Agent, fromHeadset: Boolean = false) {
        val d = drone ?: return
        val eyes = agentEyes ?: return
        val l = GeminiLive(
            context = getApplication(),
            apiKey = geminiKey,
            network = { router.cellular },
            eyes = eyes,
            systemPrompt = LivePrompt.SYSTEM + (if (robotVoice) LivePrompt.ROBOT_STYLE else "") +
                memory.promptText(d.gps() ?: pilotLat.takeIf { !it.isNaN() }?.let { it to pilotLon }),
            tools = GeminiLive.toJson(LivePrompt.TOOLS) as JSONArray,
            onTool = { name, args ->
                when (name) {
                    "drone_action" -> {
                        (args["vu"] as? String)?.takeIf { it.isNotBlank() }?.let { a.addChat(Role.SYSTEM, "👁 $it") }
                        val r = a.aiAction(args)
                        // Image prise juste après le mouvement : l'IA décide la suite sur ce qu'elle voit maintenant.
                        live?.sendFrameNow()
                        mapOf("result" to r, "etat" to d.state())
                    }
                    "go_to_look", "follow_target", "go_to_point", "tour_points", "film_shot", "retrace_path" -> {
                        val action = when (name) {
                            "go_to_look" -> mapOf("type" to "go_look", "vu" to args["vu"])
                            "follow_target" -> mapOf("type" to "follow", "target" to args["target"], "minutes" to args["minutes"], "classe" to args["classe"])
                            "go_to_point" -> mapOf("type" to "goto_point", "point" to args["point"]?.toString())
                            "tour_points" -> mapOf("type" to "tour")
                            "film_shot" -> mapOf("type" to "film", "shot" to args["shot"], "distance_m" to args["distance_m"], "record" to (args["record"] != false))
                            else -> mapOf("type" to "retrace", "land" to (args["land"] == true))
                        }
                        (args["vu"] as? String)?.takeIf { it.isNotBlank() }?.let { a.addChat(Role.SYSTEM, "👁 $it") }
                        val r = a.aiAction(action)
                        live?.sendFrameNow()
                        mapOf("result" to r, "etat" to d.state())
                    }
                    "mark_point" -> {
                        val r = nav?.markPoint(args["label"] as? String ?: "") ?: "carte indisponible"
                        thread(isDaemon = true) { flightLog?.snap("point " + (args["label"] as? String ?: "")) }
                        mapOf("result" to r, "points" to (nav?.describeWaypoints() ?: ""))
                    }
                    "remember" -> {
                        val note = args["note"] as? String ?: ""
                        memory.add(note, d.gps())
                        a.addChat(Role.SYSTEM, "🧠 Retenu : $note")
                        mapOf("result" to "retenu pour les prochains vols")
                    }
                    "drone_status" -> d.state() + mapOf(
                        "points_marques" to (nav?.describeWaypoints() ?: "aucun"),
                        "action_en_cours" to (a.activity ?: "aucune"),
                        "detecteur_telephone" to (localDetector?.averageMs?.takeIf { it > 0 }?.let { "%.0f ms par image".format(it) } ?: "pas encore utilisé"),
                        "ia" to if (a.paused) "en pause (ne peut pas bouger)" else "active",
                        "validation_par_le_pilote" to a.confirm,
                        "batterie_pourcent" to d.battery(),
                        "plan" to (a.planText() ?: "aucun"),
                    )
                    "search_area" -> mapOf(
                        "result" to a.aiAction(mapOf(
                            "type" to "search_area",
                            "target" to (args["target"] as? String ?: ""),
                            "radius_m" to args["radius_m"],
                            "altitude_m" to args["altitude_m"],
                            "approach" to (args["approach"] == true),
                            "classe" to args["classe"],
                        )),
                        "etat" to d.state(),
                    )
                    "search_object" -> mapOf(
                        "result" to a.aiAction(mapOf(
                            "type" to "search",
                            "target" to (args["target"] as? String ?: ""),
                            "approach" to (args["approach"] == true),
                            "classe" to args["classe"],
                        )),
                        "etat" to d.state(),
                    )
                    "propose_plan" -> mapOf("result" to a.proposePlan(
                        args["goal"] as? String ?: "",
                        (args["steps"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                    ))
                    "plan_progress" -> {
                        val st = when (args["status"] as? String) {
                            "en_cours" -> StepState.DOING
                            "fait" -> StepState.DONE
                            "echec" -> StepState.FAILED
                            else -> StepState.SKIPPED
                        }
                        mapOf("result" to a.planProgress((args["step"] as? Number)?.toInt() ?: 0, st, args["note"] as? String ?: ""))
                    }
                    "finish_plan" -> mapOf("result" to a.finishPlan(args["success"] as? Boolean ?: false, args["summary"] as? String ?: ""))
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
            micOptional = fromHeadset || headsetAudioClients > 0,
        )
        live = l
        routeLiveAudio()
        l.start()
        if (!l.active) {
            live = null
            return
        }
        val where = if (mode == Mode.SIM) "Simulation : tu vois par la caméra du téléphone, rien ne vole." else "Tu es connecté au vrai drone (${drone?.name ?: "drone"})."
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

    // ---- vue dans le casque (Meta Quest) ------------------------------------------------------
    @Volatile private var headset: HeadsetStream? = null

    fun setHeadset(on: Boolean) {
        if (!on) {
            headset?.stop()
            headset = null
            sysMessage("Vue dans le casque arrêtée.")
            refresh()
            return
        }
        if (headset != null) return
        val h = HeadsetStream(
            grab = ::grabForHeadset,
            state = ::headsetState,
            onHead = { p, y, follow, recenter -> drone?.let { it.noteHead(p, y); it.headLook(p, y, follow, recenter) } },
            tls = headsetTls(),
            onMic = { pcm -> live?.feedMic(pcm) },
            onAudioClients = { n -> headsetAudio(n) },
            onTile = { z, x, y -> mapTiles.get(z, x, y) },
        )
        startPilotLocation()
        if (!h.start()) {
            sysMessage("Impossible de lancer la vue dans le casque (port 8080 occupé ?).")
            return
        }
        headset = h
        val urls = h.urls()
        sysMessage(
            if (urls.isEmpty()) "Vue dans le casque prête, mais le téléphone n'est sur aucun Wi-Fi : connecte-le au même Wi-Fi que le casque, ou active son partage de connexion."
            else "Dans le navigateur du Quest (même Wi-Fi que le téléphone), ouvre : ${urls.first()} " +
                "(accepte l'avertissement de sécurité : Avancé → Continuer), puis « 🥽 Entrer en VR » : la caméra suit ta tête. " +
                "Dehors, sans box : active le point d'accès mobile du téléphone et connecte le Quest dessus.",
        )
        refresh()
    }

    @Volatile private var headsetAudioClients = 0
    private val mapTiles by lazy { com.bebopia.app.android.MapTiles(getApplication(), router::openApi) }

    // Position du pilote (GPS du téléphone), pour la mini-carte du casque
    @Volatile private var pilotLat = Double.NaN
    @Volatile private var pilotLon = Double.NaN
    private var pilotListening = false

    private fun startPilotLocation() {
        if (pilotListening) return
        val app = getApplication<Application>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val lm = app.getSystemService(android.location.LocationManager::class.java) ?: return
        val listener = android.location.LocationListener { loc -> pilotLat = loc.latitude; pilotLon = loc.longitude }
        viewModelScope.launch(Dispatchers.Main) {
            runCatching {
                for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                    if (lm.isProviderEnabled(p)) {
                        @Suppress("MissingPermission")
                        lm.getLastKnownLocation(p)?.let { pilotLat = it.latitude; pilotLon = it.longitude }
                        @Suppress("MissingPermission")
                        lm.requestLocationUpdates(p, 2000L, 1f, listener)
                    }
                }
                pilotListening = true
            }
        }
    }

    /** Casque relié (ou non) en audio : la conversation passe par lui. */
    private fun headsetAudio(n: Int) {
        headsetAudioClients = n
        if (n > 0) {
            if (live == null && useLive && agent != null) {
                viewModelScope.launch(Dispatchers.Main) {
                    agent?.let { startLive(it, fromHeadset = true) }
                    routeLiveAudio()
                }
            } else {
                routeLiveAudio()
            }
            sysMessage("🎧 Audio dans le casque : parle avec le micro du Quest, la voix de l'IA sort dans le casque.")
        } else {
            routeLiveAudio()
            sysMessage("🎧 Audio du casque coupé : la voix repasse par le téléphone.")
        }
    }

    private fun routeLiveAudio() {
        val l = live ?: return
        val h = headset
        if (headsetAudioClients > 0 && h != null) {
            l.audioOut = { pcm -> h.sendAudio(pcm) }
            l.onFlush = { h.sendText("flush") }
            l.useLocalSpeaker = false
        } else {
            l.audioOut = null
            l.onFlush = null
            l.useLocalSpeaker = true
        }
    }

    /** Certificat de l'adresse https du casque (fourni dans l'application, auto-signé). */
    private fun headsetTls(): javax.net.ssl.SSLServerSocketFactory? = runCatching {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        getApplication<Application>().assets.open("headset.p12").use { ks.load(it, "bebopia".toCharArray()) }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "bebopia".toCharArray())
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        ctx.serverSocketFactory
    }.onFailure { Log.e("BebopIA", "certificat du casque", it) }.getOrNull()

    /** Image pour le casque (appelée sur le thread de l'interface). */
    private fun grabForHeadset(): android.graphics.Bitmap? = when (mode) {
        Mode.BEBOP, Mode.MAVIC -> textureView?.takeIf { it.isAvailable }?.getBitmap(960, 540)
        Mode.SIM -> previewView?.bitmap?.let { b ->
            val w = 960
            val h = (w.toFloat() * b.height / b.width).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
        }
        else -> null
    }

    /** État complet pour le cockpit du casque (JSON). */
    private fun headsetState(): String {
        val s = _state.value
        val d = drone
        fun clean(v: Any?): Any? = when (v) {
            null -> org.json.JSONObject.NULL
            is Double -> if (v.isNaN() || v.isInfinite()) org.json.JSONObject.NULL else v
            is Float -> if (v.isNaN() || v.isInfinite()) org.json.JSONObject.NULL else v.toDouble()
            else -> v
        }
        val tel = org.json.JSONObject()
        runCatching { d?.telemetry() }.getOrNull()?.forEach { (k, v) -> tel.put(k, clean(v)) }
        if (!pilotLat.isNaN()) tel.put("pilotLat", pilotLat).put("pilotLon", pilotLon)
        val plan = s.plan?.let { p ->
            org.json.JSONObject()
                .put("goal", p.goal)
                .put("running", p.running)
                .put("approved", p.approved)
                .put("finished", p.finished)
                .put("success", p.success)
                .put("summary", p.summary)
                .put("steps", org.json.JSONArray().apply {
                    p.steps.forEach { st -> put(org.json.JSONObject().put("t", st.text).put("s", st.state.name).put("n", st.note)) }
                })
        }
        val chat = org.json.JSONArray().apply {
            s.chat.takeLast(6).forEach { line ->
                put(org.json.JSONObject().put("r", when (line.role) { Role.PILOT -> "P"; Role.DRONE -> "D"; else -> "S" }).put("t", line.text))
            }
        }
        return org.json.JSONObject()
            .put("mode", s.mode.name)
            .put("linkUp", s.linkUp)
            .put("video", s.hasVideo)
            .put("droneState", s.droneState)
            .put("battery", clean(s.battery))
            .put("alt", s.altitude)
            .put("dist", s.distance)
            .put("ceiling", s.ceiling)
            .put("radius", s.radius)
            .put("maxStep", s.maxStep)
            .put("paused", s.paused)
            .put("thinking", s.thinking)
            .put("voice", s.voice)
            .put("pending", clean(s.pending))
            .put("steps", s.steps)
            .put("maxSteps", s.maxSteps)
            .put("guardOn", s.guardOn)
            .put("guardWatching", s.guardWatching)
            .put("ttc", clean(s.guardTtc))
            .put("obstacle", s.obstacle)
            .put("confirm", s.confirm)
            .put("manual", d?.manualControl == true)
            .put("say", s.chat.lastOrNull { it.role == Role.DRONE }?.text ?: "")
            .put("tel", tel)
            .put("plan", plan ?: org.json.JSONObject.NULL)
            .put("nav", runCatching { nav?.mapJson() }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("activity", clean(s.activity))
            .put("rec", s.recording)
            .put("moveArrow", runCatching {
                val a = agent
                val mv = (a?.pending ?: a?.currentAction)?.takeIf { it["type"] == "move" }
                val f = Agent.num(mv ?: emptyMap(), "forward")
                val r = Agent.num(mv ?: emptyMap(), "right")
                if (d != null && mv != null && kotlin.math.hypot(f, r) > 0.05) org.json.JSONObject()
                    .put("b", ((mv["_vue"] as? Double) ?: d.bodyCompass()) + Math.toDegrees(kotlin.math.atan2(r, f)))
                    .put("d", kotlin.math.hypot(f, r))
                    .put("pending", a?.pending != null)
                else null
            }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("track", agent?.follower?.let { f ->
                f.box?.let { b ->
                    org.json.JSONObject().put("cx", b.cx).put("cy", b.cy).put("w", b.w).put("h", b.h)
                        .put("label", f.target ?: "").put("ok", System.currentTimeMillis() - f.confirmedAt < 4000)
                }
            } ?: org.json.JSONObject.NULL)
            .put("chat", chat)
            .put("top", listOf(s.mode.name, s.battery?.let { "$it %" } ?: "").joinToString(" "))
            .toString()
    }

    // ---- reprise en main avec la radiocommande -----------------------------------------------
    @Volatile private var takeoverAt = 0L

    /** Le pilote bouge un manche : il reprend la main immédiatement. */
    private fun pilotTakeover() {
        val d = drone ?: return
        if (d.manualControl || System.currentTimeMillis() - takeoverAt < 1500) return
        takeoverAt = System.currentTimeMillis()
        thread(isDaemon = true) {
            runCatching { d.setManual(true) }
            agent?.pilotTakeover()
            refresh()
        }
    }

    /** Le pilote rend les commandes à l'IA. */
    private fun handBack() {
        val d = drone ?: return
        thread(isDaemon = true) {
            if (d.manualControl) runCatching { d.setManual(false) }
            agent?.let { if (it.paused) it.togglePause() }
            sysMessage("🤖 L'IA a de nouveau les commandes.")
            refresh()
        }
    }

    /** Mode confiance : l'IA agit sans demander l'accord du pilote (garde-fous inchangés). */
    fun setTrust(on: Boolean) {
        if (confirm == !on) return
        confirm = !on
        agent?.confirm = !on
        // Faire confiance, c'est aussi la laisser piloter : on la sort de pause.
        agent?.let { if (on && it.paused) it.togglePause() }
        sysMessage(if (on) "🤝 Mode confiance : l'IA agit sans te demander, plans compris. Garde-fous actifs ; dis « stop » à tout moment."
            else "✋ Mode prudent : l'IA te demande ton accord avant chaque déplacement et chaque plan.")
        live?.takeIf { it.active }?.event(
            if (on) "Le pilote passe en MODE CONFIANCE : plus besoin de son accord pour tes déplacements ni pour tes plans. Reste prudent."
            else "Le pilote repasse en MODE PRUDENT : chaque déplacement et chaque plan doivent de nouveau être validés.",
            true,
        )
        refresh()
    }

    fun saveSettings(
        key: String, gemini: String, model: String, confirm: Boolean, speak: Boolean, bargeIn: Boolean,
        maxStep: Double, guardOn: Boolean, robotVoice: Boolean, ceiling: Double, radius: Double,
    ) {
        if (Math.abs(ceiling - limits.maxAltitudeM) > 0.1 || Math.abs(radius - limits.maxDistanceM) > 0.1) {
            this.ceiling = ceiling
            this.radius = radius
            limits.maxAltitudeM = this.ceiling
            limits.maxDistanceM = this.radius
            drone?.let { d -> thread(isDaemon = true) { runCatching { d.applyLimits() } } }
            live?.takeIf { it.active }?.event(
                "Le pilote a réglé le plafond à %.0f m et le rayon à %.0f m.".format(limits.maxAltitudeM, limits.maxDistanceM), false,
            )
        }
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
        if (agent != null && wasLive != useLive && !useLive) stopLive()
        refresh()
    }

    override fun onCleared() {
        headset?.stop()
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
