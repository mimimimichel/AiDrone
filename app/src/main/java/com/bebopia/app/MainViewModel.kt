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
import kotlin.math.abs

enum class Mode { NONE, CONNECTING, BEBOP, MAVIC, SIM, ROOMBA }

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
    /** Cadres à dessiner sur la vidéo (cible suivie, proposée, candidats, trouvée). */
    val marks: List<com.bebopia.app.core.vision.Marks.Mark> = emptyList(),
    val indoor: Boolean = false,
    /** auto, interieur ou exterieur */
    val envMode: String = "auto",
    val sticksFollowLook: Boolean = false,
    val preflightVerdict: String? = null,
    val preflightText: String? = null,
    val gesturesOn: Boolean = true,
    val mediaVideos: Boolean = false,
    val terrainOn: Boolean = false,
    val opticalHoldOn: Boolean = true,
    val holdActive: Boolean = false,
    val terrainAgl: Double? = null,
    /** Geste reconnu (ou en cours), pour l'affichage. */
    val gesture: String? = null,
)

/** Un point du diagnostic du Mavic : état (ok / attention / bloquant), titre, explication. */
class MavicCheck(val state: String, val title: String, val detail: String)

class MavicReport(
    val checks: List<MavicCheck>,
    /** Alertes du drone : (raison, solution), en anglais. */
    val messages: List<Pair<String, String>>,
    /** Les mêmes, expliquées en français par l'IA (null sans clé ou sans alerte). */
    val explained: String?,
    val visionOn: Boolean?,
    val noGpsAllowed: Boolean?,
    val compassCal: String,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("bebopia", Context.MODE_PRIVATE)
    private val memory = Memory(java.io.File(app.filesDir, "memoire.json"))
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        private set(v) = prefs.edit().putString("api_key", v.trim()).apply()
    /** Clé Gemini (gratuite sur aistudio.google.com) : conversation vocale en direct. */
    var geminiKey: String
        // Clé collée dans les réglages, sinon celle intégrée à l'APK au moment de sa construction
        get() = (prefs.getString("gemini_key", "") ?: "").ifBlank { BuildConfig.GEMINI_KEY }
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
    /** Intérieur / extérieur : « auto » (d'après le GPS et le repérage), ou imposé par le pilote. */
    var envMode: String
        get() = prefs.getString("env_mode", "auto") ?: "auto"
        private set(v) = prefs.edit().putString("env_mode", v).apply()
    @Volatile private var sceneIndoor: Boolean? = null
    @Volatile private var sceneIndoorAt = 0L

    fun changeEnvMode(m: String) {
        envMode = m
        sysMessage(when (m) {
            "interieur" -> "🏠 Mode intérieur imposé : pas de 30 cm, lent, jamais au-dessus du plafond."
            "exterieur" -> "🌳 Mode extérieur imposé."
            else -> "🏠/🌳 Intérieur ou extérieur : détecté automatiquement (GPS et repérage)."
        })
        refresh()
    }

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
            hasVideo = video?.hasRecentFrame ?: if (mode == Mode.MAVIC) djiOrNull?.hasRecentFrame() ?: false else (mode == Mode.SIM || mode == Mode.ROOMBA),
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
            marks = com.bebopia.app.core.vision.Marks.current(),
            indoor = d?.indoor ?: false,
            envMode = envMode,
            sticksFollowLook = sticksFollowLook,
            preflightVerdict = lastPreflight?.verdict,
            preflightText = lastPreflight?.text,
            gesturesOn = gesturesOn,
            mediaVideos = mediaVideos,
            terrainOn = terrainTarget != null,
            opticalHoldOn = opticalHoldOn,
            holdActive = holdActive,
            terrainAgl = terrainAgl,
            gesture = gestureLabel(),
        )
    }

    private val vision = GeminiVision({ geminiKey }, router::openApi)

    /** [hfov] : champ de vision horizontal de la caméra (Bebop 75°, Mavic Mini 72°, téléphone 65°). */
    private fun attachFinder(d: DroneBase, a: Agent, eyes: Eyes, simulation: Boolean, hfov: Double = if (simulation) 65.0 else 75.0, track: GrayFrames? = null, phoneCamera: Boolean = simulation) {
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
            if (phoneCamera) previewView?.bitmap?.let { b ->
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
        // Repérage, mémoire de la scène, portes, recherche dans la maison
        val sc = com.bebopia.app.core.ai.SceneMemory { d.indoor }
        d.space = sc
        d.sticksFollowLook = sticksFollowLook
        val rg = track?.takeIf { !simulation && !d.groundRobot }?.let { com.bebopia.app.core.ai.Ranger(d, it, hfov, report = { m -> a.addChat(Role.SYSTEM, m) }) }
        a.ranger = rg
        a.lander = com.bebopia.app.core.ai.Lander(d, eyes, track,
            com.bebopia.app.core.ai.LandingAnalyzer { j, alt, down -> vision.landing(j, alt, down) }, hfov, simulation,
            report = { m -> a.addChat(Role.SYSTEM, m) })
        val sv = com.bebopia.app.core.ai.Surveyor(d, eyes,
            com.bebopia.app.core.ai.SceneAnalyzer { j, t, down, hf -> vision.analyzeView(j, t, down, hf) }, sc, hfov, simulation,
            shouldStop = { a.paused }, report = { a.addChat(Role.SYSTEM, it) },
            onIndoor = { sceneIndoor = it; sceneIndoorAt = System.currentTimeMillis() }, frames = track, ranger = rg)
        val dp = com.bebopia.app.core.ai.DoorPasser(d, eyes, vision, sc, hfov, simulation, shouldStop = { a.paused }, report = { a.addChat(Role.SYSTEM, it) })
        a.scene = sc
        a.surveyor = sv
        a.doorPasser = dp
        a.house = com.bebopia.app.core.ai.HouseSearch(d, sv, dp, { a.finder }, sc, shouldStop = { a.paused },
            report = { a.addChat(Role.SYSTEM, it) }, onPlan = { a.missionPlan(it) })
        scene = sc
        a.onLine = { role, text -> log.line(role, text) }
        a.onSnap = { label -> thread(isDaemon = true) { log.snap(label) } }
        a.onMasterShots = { masterShotsPending = true }
        a.hyperlapseRunner = { k, sec -> runHyperlapse(k, sec) }
        flightLog = log
    }

    // ---- carte, journal, mémoire -------------------------------------------------------------
    @Volatile private var nav: Navigator? = null
    @Volatile private var scene: com.bebopia.app.core.ai.SceneMemory? = null
    @Volatile private var wasFlyingTick = false
    @Volatile private var failsafeSet = false
    @Volatile private var preflightTried = false
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
                runCatching { checkDrift(d) }
                if (questActive && System.currentTimeMillis() - questLastActive > 1000) questActive = false
                // Nouveau vol : la mémoire de la scène repart de zéro ; fin de vol : le plan des pièces va dans la mémoire de l'IA
                if (d.flying && !wasFlyingTick) scene?.clear()
                if (!d.flying && wasFlyingTick) thread(isDaemon = true) {
                    Thread.sleep(8000) // le journal se ferme, le drone se pose bien
                    if (drone === d && !d.flying) {
                        val montage = masterShotsPending
                        downloadMedia(mediaVideos || montage)
                        if (montage) { masterShotsPending = false; flightLog?.currentDir?.let { montageMasterShots(it) } }
                    }
                }
                // Dehors : vérification de la zone et de la météo, une fois par connexion
                if (!d.indoor && !preflightTried && mode != Mode.SIM && mode != Mode.ROOMBA) {
                    preflightTried = true
                    thread(isDaemon = true) { runCatching { runPreflight() } }
                }
                // Dehors avec GPS : si la liaison est perdue, le drone rentre seul (au moins 20 m de haut pour passer les arbres)
                if (!d.indoor && d.hasGps && !failsafeSet) {
                    failsafeSet = true
                    thread(isDaemon = true) {
                        val h = limits.maxAltitudeM.toInt().coerceIn(20, 30)
                        d.setupFailsafe(h)?.let { e -> if (!e.startsWith("non disponible")) sysMessage("Réglage du retour automatique refusé : $e") }
                            ?: sysMessage("🏠 Si la liaison est perdue, le drone rentre seul au point de départ (à $h m de haut).")
                    }
                }
                if (!d.flying && wasFlyingTick) scene?.roomsSummary()?.takeIf { it.isNotBlank() && (scene?.roomCount() ?: 0) >= 1 }?.let { plan ->
                    memory.add("Plan des pièces repéré pendant le vol (depuis le point de décollage) : $plan", d.homeGps() ?: d.gps())
                }
                wasFlyingTick = d.flying
                // Intérieur ou extérieur
                val indoorNow = when (envMode) {
                    "interieur" -> true
                    "exterieur" -> false
                    else -> sceneIndoor?.takeIf { System.currentTimeMillis() - sceneIndoorAt < 15 * 60_000 } ?: !d.hasGps
                }
                if (indoorNow != d.indoor) {
                    d.indoor = indoorNow
                    sysMessage(if (indoorNow) "🏠 Intérieur : pas de 30 cm, lent, jamais au-dessus du plafond, place libre vérifiée."
                        else "🌳 Extérieur : déplacements normaux.")
                    live?.takeIf { it.active }?.event(
                        if (indoorNow) "Tu es en INTÉRIEUR : petits pas (30 cm max, l'application limite), jamais monter pour regarder, " +
                            "repère les lieux (survey) avant de planifier, passe les portes avec pass_door."
                        else "Tu es en EXTÉRIEUR.", false)
                }
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

    /**
     * Pendant que l'IA attend ton « oui » pour suivre ou chercher quelque chose : cadre ce qu'elle compte suivre
     * (orange, d'après Gemini) et les autres candidats vus par le téléphone (pointillés).
     */
    private fun startTargetPreview() {
        viewModelScope.launch(Dispatchers.IO) {
            var forKey = ""
            var proposed: com.bebopia.app.core.vision.Box? = null
            var askedAt = 0L
            while (isActive) {
                delay(450)
                val p = agent?.pending
                val type = p?.get("type") as? String
                val target = (p?.get("target") as? String)?.trim().orEmpty()
                if (p == null || type !in setOf("follow", "search", "search_area") || target.isEmpty()) {
                    if (forKey.isNotEmpty()) { com.bebopia.app.core.vision.Marks.clear("preview"); forKey = ""; proposed = null }
                    continue
                }
                val key = "$type|$target"
                if (key != forKey) { forKey = key; proposed = null; askedAt = 0L }
                val classes = com.bebopia.app.core.vision.CocoClasses.forTarget(target, p["classe"] as? String)
                val local = if (classes != null) runCatching { localDetector?.detect(false) }.getOrNull().orEmpty()
                    .filter { it.label in classes && it.score >= 0.35 } else emptyList()
                // Ce que l'IA comprend par « le chien noir » : demandé à Gemini toutes les 3 s
                if (geminiKey.isNotBlank() && System.currentTimeMillis() - askedAt > 3000) {
                    askedAt = System.currentTimeMillis()
                    val jpeg = agentEyes?.captureJpegBase64()
                    if (jpeg != null) runCatching { vision.find(jpeg, target) }.getOrNull()?.let { d ->
                        proposed = if (d.found && d.confidence >= 0.35) com.bebopia.app.core.vision.Box(d.cx, d.cy, d.w, d.h) else null
                    }
                }
                // Si le téléphone voit le même objet, sa boîte (à jour) remplace celle de Gemini
                val pr = proposed
                val best = if (pr != null) local.maxByOrNull { it.box.iou(pr) }?.takeIf { it.box.iou(pr) > 0.25 } else if (local.size == 1) local[0] else null
                val chosen = best?.box ?: pr
                val marks = ArrayList<com.bebopia.app.core.vision.Marks.Mark>()
                local.filter { it !== best }.forEach { marks.add(com.bebopia.app.core.vision.Marks.Mark(it.box, com.bebopia.app.core.vision.CocoClasses.french(it.label), com.bebopia.app.core.vision.Marks.Kind.CANDIDATE)) }
                chosen?.let { marks.add(com.bebopia.app.core.vision.Marks.Mark(it, target, com.bebopia.app.core.vision.Marks.Kind.PROPOSED)) }
                com.bebopia.app.core.vision.Marks.show("preview", marks, 1500)
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

    // ---- diagnostic du Mavic (ce qui empêche de décoller) et déblocages -----------------------------
    private var explainedFor = ""
    private var explainedText: String? = null

    private fun <T> waitDji(block: ((T?, String?) -> Unit) -> Unit): Pair<T?, String?> {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Pair<T?, String?> = null to "pas de réponse du drone"
        block { v, e -> out = v to e; latch.countDown() }
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    /** État du Mavic avant décollage. Bloquant (quelques secondes) : à appeler hors du thread de l'interface. */
    fun mavicReport(): MavicReport? {
        val l = djiOrNull ?: return null
        if (mode != Mode.MAVIC) return null
        val checks = l.checks().map { MavicCheck(it[0], it[1], it[2]) }
        val msgs = l.djiMessages().map { it[0] to it[1] }
        val visionOn = waitDji<Boolean> { k -> l.getVisionPositioning { v, e -> k(v, e) } }.first
        val noGps = waitDji<Boolean> { k -> l.getTakeoffWithoutGpsAllowed { v, e -> k(v, e) } }.first
        // Alertes du drone expliquées en français (une fois par liste d'alertes)
        val key = msgs.joinToString("|") { it.first + it.second }
        if (msgs.isNotEmpty() && key != explainedFor && geminiKey.isNotBlank()) {
            explainedText = runCatching {
                vision.text("Voici les alertes affichées par un drone DJI Mavic Mini (raison / solution, en anglais). " +
                    "Explique chacune en français simple, pour un débutant, en une ou deux phrases : ce que ça veut dire et quoi faire " +
                    "concrètement. Une ligne par alerte, sans titre.\n" + msgs.joinToString("\n") { "- " + it.first + " / " + it.second })
            }.getOrNull()?.trim()
            explainedFor = key
        }
        return MavicReport(checks, msgs, if (msgs.isEmpty()) null else explainedText, visionOn, noGps, l.compassCalibrationState())
    }

    /** Actions de déblocage. Bloquant : hors du thread de l'interface. Renvoie un message pour le pilote. */
    fun mavicAction(kind: String): String {
        val l = djiOrNull ?: return "Mavic non connecté"
        if (drone?.flying == true && kind in setOf("compass", "imu", "reboot")) return "Impossible en vol : pose le drone d'abord."
        fun done(ok: String, block: (DjiLink.Done) -> Unit): String {
            val (_, err) = waitDji<Unit> { k -> block(DjiLink.Done { e -> k(Unit, e) }) }
            return err?.let { "Refusé par le drone : $it" } ?: ok
        }
        val r = when (kind) {
            "vision_on" -> done("Positionnement visuel (capteurs du dessous) activé.") { l.visionPositioning(true, it) }
            "nogps_allow" -> done("Décollage sans GPS autorisé. Attention : sans GPS ni capteurs du dessous, le drone dérive (mode ATTI) — " +
                "en intérieur, il faut que ses capteurs du dessous fonctionnent pour qu'il tienne en place.") { l.allowTakeoffWithoutGps(true, it) }
            "nogps_lock" -> done("Décollage sans GPS de nouveau verrouillé.") { l.allowTakeoffWithoutGps(false, it) }
            "compass" -> done("Étalonnage de la boussole lancé : loin de tout métal, tiens le drone à plat et tourne sur toi-même " +
                "(un tour complet), puis nez vers le bas et refais un tour.") { l.compassCalibration(true, it) }
            "compass_stop" -> done("Étalonnage de la boussole arrêté.") { l.compassCalibration(false, it) }
            "imu" -> done("Étalonnage de la centrale inertielle lancé : drone posé bien à plat, ne le touche pas (5 à 10 min).") { l.imuCalibration(it) }
            "reboot" -> done("Contrôleur de vol redémarré : attends 30 s que le drone se reconnecte.") { l.rebootFlightController(it) }
            else -> "action inconnue"
        }
        sysMessage("🔧 $r")
        return r
    }

    fun changeAutoRecord(on: Boolean) {
        autoRecord = on
        refresh()
    }

    // ---- maintien optique (Mavic sans GPS ni capteurs du dessous) --------------------------------------
    var opticalHoldOn: Boolean
        get() = prefs.getBoolean("optical_hold", true)
        private set(v) = prefs.edit().putBoolean("optical_hold", v).apply()
    fun changeOpticalHold(on: Boolean) { opticalHoldOn = on; refresh() }
    @Volatile private var holdJob: kotlinx.coroutines.Job? = null
    @Volatile var holdActive = false
        private set

    /** 7 fois par seconde : si le drone dérive faute de repères, on corrige avec la vidéo. */
    private fun startOpticalHold(d: DroneBase, frames: GrayFrames) {
        holdJob?.cancel()
        holdJob = viewModelScope.launch(Dispatchers.Default) {
            val hold = com.bebopia.app.core.vision.OpticalHold(72.0)
            var idleSince = 0L
            var announced = false
            while (isActive && drone === d) {
                delay(140)
                val now = System.currentTimeMillis()
                val want = opticalHoldOn && d.positionHoldWeak() && d.idleForHold
                if (!want) {
                    if (holdActive || d.holdAssist != null) { d.clearHold(); hold.reset(); holdActive = false }
                    idleSince = 0L
                    continue
                }
                if (idleSince == 0L) { idleSince = now; hold.reset(); continue }
                if (now - idleSince < 700) continue // laisser le drone finir de freiner
                val g = frames.grab()
                if (g == null) { d.clearHold(); continue }
                val c = runCatching { hold.update(g, d.compass(), d.gimbalPitch(), now) }.getOrNull()
                if (c == null) { d.clearHold(); continue }
                d.setHold(c.forward, c.right)
                holdActive = true
                if (!announced) {
                    announced = true
                    sysMessage("🧲 Maintien optique : le drone n'a ni GPS ni capteurs du dessous, il dérive. Je corrige avec la vidéo " +
                        "(il faut du décor bien visible devant lui ; allume la lumière, un sol texturé aide aussi ses capteurs).")
                }
            }
            d.clearHold()
            holdActive = false
        }
    }

    /** Le positionnement visuel (caméra du dessous) doit être activé dans les réglages du drone : on le vérifie et on l'allume. */
    private fun ensureVisionPositioning(a: Agent) {
        val l = djiOrNull ?: return
        l.getVisionPositioning { on, err ->
            when {
                on == false -> l.visionPositioning(true) { e ->
                    a.addChat(Role.SYSTEM, if (e == null) "👁 Le positionnement visuel (caméra du dessous) était DÉSACTIVÉ dans les réglages du drone : je l'ai activé. " +
                        "C'est lui qui tient le drone en place en intérieur." else "👁 Positionnement visuel désactivé, et impossible de l'activer ($e) : active-le dans DJI Fly (Sécurité).")
                }
                on == null -> log("réglage du positionnement visuel illisible : $err")
            }
        }
    }

    @Volatile private var driftWarnedFlight = false
    @Volatile private var driftSince = 0L

    /** En vol sans GPS : si la caméra du dessous ne tient pas le drone, on cherche pourquoi et on le dit (une fois par vol). */
    private fun checkDrift(d: DroneBase) {
        val l = djiOrNull
        if (d !is com.bebopia.app.dji.DjiDrone || l == null) return
        if (!d.flying) { driftWarnedFlight = false; driftSince = 0L; return }
        val weak = d.positionHoldWeak() && d.altitude() in 0.5..9.0
        if (!weak) { driftSince = 0L; return }
        if (driftSince == 0L) driftSince = System.currentTimeMillis()
        if (driftWarnedFlight || System.currentTimeMillis() - driftSince < 4000) return
        driftWarnedFlight = true
        val a = agent ?: return
        l.getVisionPositioning { on, _ ->
            if (on == false) {
                l.visionPositioning(true, null)
                a.addChat(Role.SYSTEM, "👁 Caméra du dessous : le positionnement visuel était désactivé dans le drone, je viens de l'activer.")
            }
            val g = runCatching { TextureGray({ textureView }, { true }).grab() }.getOrNull()
            val dark = g != null && g.px.average() < 50
            a.addChat(Role.SYSTEM, "⚠️ Le drone DÉRIVE : il vole sans GPS et sa caméra du dessous ne le tient pas (mode ${l.flightModeName()}). " +
                "Elle a besoin : de lumière (la pièce${if (dark) " a l'air sombre" else ""} doit être bien éclairée, elle ne voit rien sous ~15 lux), " +
                "d'un sol qui a des motifs juste SOUS le drone (un sol uni, brillant, un tapis uni ou un carrelage clair ne marchent pas ; ce qu'il y a sur les murs ne compte pas), " +
                "d'une hauteur entre 0,5 et 10 m, et de lentilles propres sous le drone. " +
                "En attendant : maintien optique actif, pas de manœuvre automatique. Si ça persiste avec un sol à motifs et de la lumière, " +
                "étalonne les capteurs de vision (DJI Assistant 2 sur ordinateur).")
            speaker.say("Attention, je dérive : ma caméra du dessous ne voit pas le sol.")
        }
    }

    private fun startGuard(d: DroneBase, a: Agent, frames: GrayFrames) {
        guard?.stop()
        guard = ObstacleGuard(d, frames, enabled = { guardOn }, onObstacle = { ttc, moving ->
            obstacleAt = System.currentTimeMillis()
            // Il dérive vers l'obstacle tout seul : petit recul pour se dégager (sinon il reste collé)
            if (!moving && d.positionHoldWeak() && d.idleForHold) thread(isDaemon = true) {
                runCatching { d.velocity(-0.3, 0.0, 0.0, 0.0, 900) }
            }
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
        a.takeoffCheck = { takeoffGate(d) }
        a.onForceTakeoff = { forceTakeoff() }
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
                startOpticalHold(d, TextureGray({ textureView }, { dji.hasRecentFrame() }))
                attachFinder(d, a, eyes, simulation = false, hfov = 72.0, track = TextureGray({ textureView }, { dji.hasRecentFrame() }, 320))
                drone = d
                agent = a
                mode = Mode.MAVIC
                surfaceTexture?.let { (st, w, h) -> dji.startVideo(getApplication(), st, w, h) }
                a.start()
                a.addChat(Role.SYSTEM, "Connecté : ${d.name} (batterie ${d.battery() ?: "?"} %). L'IA est en pause : parle-lui, ou appuie sur « ▶ IA ». " +
                    "En intérieur, il faut un sol texturé et éclairé pour que le drone tienne sa position.")
                a.addChat(Role.SYSTEM, "État du drone : ${dji.diagnostics()}")
                ensureVisionPositioning(a)
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

    // ---- Roomba : robot roulant piloté par une carte USB-série, caméra du téléphone ----------------------------
    fun connectRoomba() {
        if (mode != Mode.NONE) return
        mode = Mode.CONNECTING
        sysMessage("🤖 Connexion au Roomba : carte USB-série branchée au téléphone, fils dans la prise du Roomba…")
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            var link: com.bebopia.app.android.UsbSerial? = null
            try {
                link = com.bebopia.app.android.UsbSerial.open(getApplication())
                sysMessage("🔌 Carte USB-série ouverte. J'appelle le Roomba…")
                val d = com.bebopia.app.core.roomba.RoombaDrone(limits, link, ::log)
                d.connect()
                d.indoor = true
                val eyes = PreviewEyes { previewView }
                val a = makeAgent(d, eyes)
                startGuard(d, a, PreviewGray({ previewView }))
                attachFinder(d, a, eyes, simulation = false, hfov = 65.0, track = PreviewGray({ previewView }, 320), phoneCamera = true)
                drone = d
                agent = a
                mode = Mode.ROOMBA
                a.start()
                val s = d.sensors
                a.addChat(Role.SYSTEM, "🤖 Roomba connecté (${d.baud} bauds) : il a dû jouer une petite mélodie. Batterie ${s?.batteryPct ?: "?"} %" +
                    (if (s?.onHomeBase == true) ", sur sa base" else "") + ". « Décoller » le démarre, « Atterrir » l'arrête. " +
                    "Laisse l'écran allumé et désactive l'optimisation de batterie pour Bebop IA si le téléphone reste sur le robot.")
            } catch (e: Throwable) {
                Log.e("BebopIA", "roomba", e)
                link?.close()
                mode = Mode.NONE
                sysMessage("🤖 Roomba : ${e.message ?: e.javaClass.simpleName}")
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
        scene = null
        failsafeSet = false
        preflightTried = false
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
                    "can_i_fly" -> mapOf("result" to runPreflight().text)
                    "terrain_follow" -> mapOf("result" to setTerrainFollow(args["on"] != false, (args["height_m"] as? Number)?.toDouble()))
                    "spotlight", "master_shots", "hyperlapse" -> {
                        val action = when (name) {
                            "spotlight" -> mapOf("type" to "spotlight", "target" to args["target"], "minutes" to args["minutes"])
                            "master_shots" -> mapOf("type" to "mastershots", "distance_m" to args["distance_m"])
                            else -> mapOf("type" to "hyperlapse", "kind" to args["kind"], "seconds" to args["seconds"])
                        }
                        val r = a.aiAction(action)
                        live?.sendFrameNow()
                        mapOf("result" to r, "etat" to d.state())
                    }
                    "take_photo" -> mapOf("result" to photoNow())
                    "camera_settings" -> {
                        val ev = (args["exposure_ev"] as? Number)?.toDouble()
                        val wb = args["white_balance"] as? String
                        val r = runCatching { d.cameraSettings(ev, wb) }.getOrElse { "échec : ${it.message}" }
                        a.addChat(Role.SYSTEM, "🎛 Caméra : $r")
                        mapOf("result" to r)
                    }
                    "download_media" -> mapOf("result" to downloadMedia(args["videos"] == true))
                    "survey", "pass_door", "search_house", "come_back" -> {
                        val action = when (name) {
                            "survey" -> mapOf("type" to "survey", "target" to args["target"], "climb_m" to args["climb_m"])
                            "pass_door" -> mapOf("type" to "pass_door", "door" to args["door"])
                            "search_house" -> mapOf("type" to "search_house", "target" to args["target"])
                            else -> mapOf("type" to "come_back")
                        }
                        val r = a.aiAction(action)
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
                        "lieu" to if (d.indoor) "intérieur (pas de 30 cm max, pas de montée pour regarder)" else "extérieur",
                        "memoire_scene" to (scene?.describe(d.positionNE(), d.lookHeading()) ?: "vide"),
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
        val where = if (mode == Mode.SIM) "Simulation : tu vois par la caméra du téléphone, rien ne vole."
            else if (mode == Mode.ROOMBA) "Tu es un ROBOT QUI ROULE (un Roomba), avec la caméra du téléphone posé dessus. Tu ne voles pas : " +
                "« décoller » = démarrer, « atterrir » = t'arrêter. Tu ne montes pas, tu ne descends pas et tu ne vas pas de côté " +
                "(pour aller à droite tu tournes puis tu avances). Tu vois à hauteur du sol. Tes pare-chocs t'arrêtent si tu touches " +
                "quelque chose, et tu t'arrêtes tout seul au bord d'une marche. Pour rentrer te recharger : return_home (tu cherches ta base tout seul)."
            else "Tu es connecté au vrai drone (${drone?.name ?: "drone"})."
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
            onSticks = { lx, ly, rx, ry -> questSticks(lx, ly, rx, ry) },
            onButton = { b -> questButton(b) },
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
        Mode.SIM, Mode.ROOMBA -> previewView?.bitmap?.let { b ->
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
            .put("sticks", questActive)
            .put("headPaused", d?.headPaused == true)
            .put("preflight", clean(s.preflightVerdict))
            .put("hold", s.holdActive)
            .put("terrain", if (s.terrainOn) (s.terrainAgl ?: -1.0) else null)
            .put("gesture", clean(s.gesture))
            .put("sticksMode", if (sticksFollowLook) "regard" else "libre")
            .put("indoor", s.indoor)
            .put("scene", runCatching { scene?.mapJson() }.getOrNull() ?: org.json.JSONObject.NULL)
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
            .put("marks", org.json.JSONArray().apply {
                s.marks.forEach { m ->
                    put(org.json.JSONObject().put("cx", m.box.cx).put("cy", m.box.cy).put("w", m.box.w).put("h", m.box.h)
                        .put("l", m.label).put("k", m.kind.name))
                }
            })
            .put("chat", chat)
            .put("top", listOf(s.mode.name, s.battery?.let { "$it %" } ?: "").joinToString(" "))
            .toString()
    }

    // ---- pilotage aux manettes du casque (mode 2 : gauche = gaz + rotation, droite = avant/arrière + côtés) ----
    @Volatile private var questActive = false
    @Volatile private var questLastActive = 0L
    @Volatile private var questButtonAt = 0L

    private fun expo(v: Double): Double {
        val a = abs(v)
        if (a < 0.12) return 0.0
        val k = (a - 0.12) / 0.88
        return Math.signum(v) * k * k * 0.6 + Math.signum(v) * k * 0.4 // doux au centre, plein au bout
    }

    private fun questSticks(lx: Double, ly: Double, rx: Double, ry: Double) {
        val d = drone ?: return
        val now = System.currentTimeMillis()
        val any = maxOf(abs(lx), abs(ly), abs(rx), abs(ry)) > 0.12
        if (!any) {
            if (questActive && now - questLastActive > 250) { questActive = false; d.stopVelocity() }
            return
        }
        questLastActive = now
        // Spotlight : le pilote déplace le drone, le suivi garde la caméra sur la cible (pas de reprise en main)
        val fol = agent?.follower
        if (fol != null && fol.spotlight && d.flying) {
            val vh = if (d.indoor) 0.5 else if (d.hasGps) 4.0 else 1.0
            fol.pilot(-expo(ry) * vh, expo(rx) * vh, -expo(ly) * (if (d.indoor) 0.4 else 1.5))
            return
        }
        if (!d.supportsSticks) {
            if (!questActive) sysMessage("🎮 Pilotage aux manettes indisponible avec ce drone (simulation).")
            questActive = true
            return
        }
        if (!questActive) {
            questActive = true
            // Le pilote prend la main : l'IA s'arrête, l'action en cours aussi.
            thread(isDaemon = true) {
                runCatching { d.cancelMove() }
                agent?.let { if (!it.paused) it.pilotTakeover("les manettes du casque") }
                refresh()
            }
        }
        if (!d.flying) return
        val vh = if (d.indoor) 0.5 else if (d.hasGps) 4.0 else 1.0
        var f = -expo(ry) * vh
        var r = expo(rx) * vh
        var u = -expo(ly) * (if (d.indoor) 0.4 else 1.5)
        val yaw = expo(lx) * (if (d.indoor) 40.0 else 70.0)
        // Plafond, rayon, murs repérés : on bloque ce qui y mènerait
        val top = if (d.indoor) (d.space?.ceilingAlt()?.minus(0.5) ?: 2.0) else limits.maxAltitudeM
        if (u > 0 && d.altitude() >= top - 0.1) u = 0.0
        if (u < 0 && d.altitude() <= limits.minAltitudeM) u = 0.0
        if (f != 0.0 || r != 0.0) {
            val bearing = (d.travelHeading() ?: d.lookHeading()) + Math.toDegrees(kotlin.math.atan2(r, f))
            val p = d.positionNE()
            val dist = kotlin.math.hypot(p.first, p.second)
            if (dist > limits.maxDistanceM - 1) {
                // Au bord du rayon : seulement vers le départ
                val home = Math.toDegrees(kotlin.math.atan2(-p.second, -p.first))
                if (abs(com.bebopia.app.core.norm180(bearing - home)) > 80) { f = 0.0; r = 0.0 }
            }
            d.space?.allowed(bearing, p)?.let { free -> if (free < 0.3) { f = 0.0; r = 0.0 } }
        }
        runCatching { d.sticks(f, r, u, yaw) }
    }

    // ---- « Est-ce que je peux voler ici ? » (zones + météo) ----------------------------------------
    @Volatile var lastPreflight: com.bebopia.app.core.ai.PreflightReport? = null
        private set
    @Volatile private var preflightAt: Pair<Double, Double>? = null
    @Volatile private var forceTakeoffUntil = 0L
    private val preflight = com.bebopia.app.core.ai.Preflight { url ->
        val c = router.openApi(java.net.URL(url))
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "BebopIA")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Vérifie la zone et la météo là où est le pilote (ou le drone). Bloquant. */
    fun runPreflight(): com.bebopia.app.core.ai.PreflightReport {
        val d = drone
        val here = d?.gps() ?: pilotLat.takeIf { !it.isNaN() }?.let { it to pilotLon }
            ?: return com.bebopia.app.core.ai.PreflightReport("prudence",
                "⚠️ Pas de position GPS (ni téléphone ni drone) : impossible de vérifier les zones et la météo. Active la localisation du téléphone.", null, null, null)
                .also { lastPreflight = it; refresh() }
        val maxWind = when (mode) { Mode.BEBOP -> 10.0; else -> 8.0 }
        val r = preflight.check(here.first, here.second, d?.name ?: "drone", maxWind)
        lastPreflight = r
        preflightAt = here
        // Hauteur limitée par la zone : le plafond suit
        r.maxHeightM?.let { h ->
            if (h in 1 until limits.maxAltitudeM.toInt() && d?.indoor != true) {
                limits.maxAltitudeM = h.toDouble()
                d?.let { thread(isDaemon = true) { runCatching { it.applyLimits() } } }
                sysMessage("📐 Plafond abaissé à $h m : c'est la limite de cette zone.")
            }
        }
        sysMessage("🛫 ${r.text}")
        refresh()
        return r
    }

    private fun preflightFresh(): Boolean {
        val r = lastPreflight ?: return false
        val at = preflightAt ?: return false
        val here = drone?.gps() ?: pilotLat.takeIf { !it.isNaN() }?.let { it to pilotLon } ?: return true
        val moved = kotlin.math.hypot((here.first - at.first) * 111320, (here.second - at.second) * 78000)
        return System.currentTimeMillis() - r.at < 15 * 60_000 && moved < 300
    }

    /** Avant chaque décollage dehors : refus si zone interdite ou météo dangereuse (« décolle quand même » pour passer outre). */
    private fun takeoffGate(d: DroneBase): String? {
        if (d.groundRobot || d.indoor || System.currentTimeMillis() < forceTakeoffUntil) return null
        val r = if (preflightFresh()) lastPreflight!! else runPreflight()
        return if (r.verdict == "non") "refusé : ${r.text} Dis « décolle quand même » pour passer outre." else null
    }

    fun forceTakeoff() {
        forceTakeoffUntil = System.currentTimeMillis() + 60_000
        sysMessage("⚠️ Décollage forcé par le pilote, malgré la vérification.")
        agent?.humanTakeoff()
    }

    // ---- gestes devant la caméra ---------------------------------------------------------------------
    var gesturesOn: Boolean
        get() = prefs.getBoolean("gestures", true)
        private set(v) = prefs.edit().putBoolean("gestures", v).apply()
    var mediaVideos: Boolean
        get() = prefs.getBoolean("media_videos", false)
        private set(v) = prefs.edit().putBoolean("media_videos", v).apply()
    fun changeGestures(on: Boolean) { gesturesOn = on; refresh() }
    fun changeMediaVideos(on: Boolean) { mediaVideos = on; refresh() }
    fun changeTerrainFollow(on: Boolean) { thread(isDaemon = true) { setTerrainFollow(on, null).let { if (!it.startsWith("suivi")) sysMessage("⛰ $it") } } }

    private val gestureWatcher by lazy { com.bebopia.app.android.GestureWatcher(getApplication()) }
    private val gestures = com.bebopia.app.core.vision.GestureInterpreter()
    @Volatile private var gestureShown: Pair<String, Long>? = null

    private fun gestureLabel(): String? {
        gestures.progress?.let { (g, k) -> return g.label + " " + "▮".repeat((k * 5).toInt()) + "▯".repeat(5 - (k * 5).toInt()) }
        return gestureShown?.takeIf { System.currentTimeMillis() - it.second < 3000 }?.first
    }

    private fun startGestures() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(250)
                val d = drone ?: continue
                if (!gesturesOn || !(d.flying || mode == Mode.SIM || mode == Mode.ROOMBA) || agent == null) continue
                val bmp = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    runCatching {
                        if (mode == Mode.SIM || mode == Mode.ROOMBA) previewView?.bitmap?.let { android.graphics.Bitmap.createScaledBitmap(it, 640, 640 * it.height / it.width, true) }
                        else textureView?.takeIf { it.isAvailable }?.getBitmap(640, 360)
                    }.getOrNull()
                } ?: continue
                val (hand, pose) = runCatching { gestureWatcher.analyze(bmp) }.getOrDefault(null to null)
                bmp.recycle()
                val g = gestures.update(System.currentTimeMillis(), hand, pose) ?: continue
                onGesture(g)
            }
        }
    }

    private fun onGesture(g: com.bebopia.app.core.vision.Gesture) {
        val a = agent ?: return
        gestureShown = g.label to System.currentTimeMillis()
        sysMessage("🤚 Geste reconnu : ${g.label}")
        live?.takeIf { it.active }?.event("Le pilote a fait un geste devant la caméra : ${g.label}. L'application s'en occupe.", false)
        when (g) {
            com.bebopia.app.core.vision.Gesture.STOP -> a.stopNow()
            com.bebopia.app.core.vision.Gesture.LAND -> a.humanLand()
            com.bebopia.app.core.vision.Gesture.YES -> if (a.pending != null) a.approve() else a.approvePlan()
            com.bebopia.app.core.vision.Gesture.NO -> if (a.pending != null) a.reject() else a.rejectPlan()
            com.bebopia.app.core.vision.Gesture.PHOTO -> thread(isDaemon = true) {
                speaker.say("Photo dans 3, 2, 1.")
                Thread.sleep(3000)
                photoNow()
            }
        }
    }

    /** Photo sur la carte du drone + copie de l'image vidéo dans le journal. */
    fun photoNow(): String {
        val d = drone ?: return "pas de drone"
        thread(isDaemon = true) { flightLog?.snap("photo") }
        val r = runCatching { d.takePhoto() }.getOrElse { it.message }
        val msg = if (r == null) "📸 Photo prise" else "📸 Photo enregistrée dans le journal (sur le drone : $r)"
        sysMessage(msg)
        return msg
    }

    // ---- suivi du relief ---------------------------------------------------------------------------
    private val terrain = com.bebopia.app.core.ai.Terrain { url ->
        val c = router.openApi(java.net.URL(url))
        try {
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "BebopIA")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    /** Hauteur voulue au-dessus du sol (m), null = suivi du relief arrêté. */
    @Volatile private var terrainTarget: Double? = null
    /** Hauteur actuelle au-dessus du sol (m), estimée. */
    @Volatile private var terrainAgl: Double? = null
    @Volatile private var groundAtHome: Double? = null
    @Volatile private var terrainWarned = 0L
    @Volatile private var masterShotsPending = false

    fun setTerrainFollow(on: Boolean, heightM: Double?): String {
        val d = drone ?: return "pas de drone"
        if (!on) {
            terrainTarget = null; d.verticalAssist = null; terrainAgl = null
            sysMessage("⛰ Suivi du relief arrêté")
            refresh()
            return "suivi du relief arrêté"
        }
        if (d.indoor) return "refusé : le suivi du relief, c'est dehors (en intérieur le drone garde déjà sa hauteur au capteur)"
        val home = d.homeGps() ?: d.gps() ?: return "refusé : il faut le GPS pour suivre le relief"
        val g0 = terrain.elevations(listOf(home))?.firstOrNull() ?: return "échec : modèle de terrain injoignable (réseau ?)"
        groundAtHome = g0
        val here = d.gps()
        val gHere = here?.let { terrain.elevations(listOf(it))?.firstOrNull() }
        val cur = if (gHere != null) d.altitude() + g0 - gHere else d.altitude()
        val target = (heightM ?: cur).coerceIn(3.0, limits.maxAltitudeM)
        terrainTarget = target
        sysMessage("⛰ Suivi du relief : je garde %.0f m au-dessus du sol (plafond %.0f m au-dessus du décollage)".format(target, limits.maxAltitudeM))
        refresh()
        return "suivi du relief actif à %.0f m du sol".format(target)
    }

    /** Deux fois par seconde : vitesse verticale pour garder la hauteur au-dessus du sol. */
    private fun startTerrainLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            var last: Pair<Double, Double>? = null
            while (isActive) {
                delay(500)
                val d = drone
                val target = terrainTarget
                if (d == null || target == null || !d.flying || d.indoor) {
                    d?.verticalAssist = null; last = null
                    if (d != null && target != null && !d.flying && d.indoor) terrainTarget = null
                    continue
                }
                val g = d.gps()
                if (g == null) { d.verticalAssist = null; continue }
                val p = d.positionNE()
                val course = last?.let { l ->
                    val dn = p.first - l.first; val de = p.second - l.second
                    if (kotlin.math.hypot(dn, de) > 0.4) Math.toDegrees(kotlin.math.atan2(de, dn)) else null
                }
                last = p
                val g0 = groundAtHome ?: continue
                val alt = d.altitude()
                var v = runCatching { terrain.verticalSpeed(g.first, g.second, course, alt, g0, target, d.heightAboveGround()) }.getOrNull()
                val gHere = terrain.elevations(listOf(g))?.firstOrNull()
                terrainAgl = d.heightAboveGround()?.takeIf { it < 8 } ?: gHere?.let { alt + g0 - it }
                if (v != null && v > 0 && alt >= limits.maxAltitudeM - 0.5) {
                    v = 0.0
                    if (System.currentTimeMillis() - terrainWarned > 20_000) {
                        terrainWarned = System.currentTimeMillis()
                        sysMessage("⛰ Le relief monte mais j'atteins le plafond (%.0f m au-dessus du décollage) : monte le plafond dans les réglages ou fais demi-tour".format(limits.maxAltitudeM))
                    }
                }
                d.verticalAssist = v
            }
        }
    }

    // ---- hyperlapse et MasterShots -------------------------------------------------------------------
    /** Hyperlapse : une image par seconde de la vidéo en direct, puis vidéo accélérée (30 i/s) dans le journal du vol. Bloquant. */
    private fun runHyperlapse(kind: String, seconds: Double): String {
        val sh = agent?.shots ?: return "refusé : plans indisponibles"
        val dir = flightLog?.currentDir ?: return "refusé : journal du vol indisponible"
        val file = java.io.File(dir, "hyperlapse-${System.currentTimeMillis() / 1000}.mp4")
        val video = runCatching { com.bebopia.app.android.FrameVideo(file) }.getOrElse { return "échec : encodeur vidéo (${it.message})" }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        sysMessage("⏩ Hyperlapse « $kind » : %.0f s de prise de vue, une image par seconde".format(seconds))
        val r = runCatching {
            sh.hyperlapse(kind, seconds, 1000) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var bmp: android.graphics.Bitmap? = null
                main.post { bmp = runCatching { textureView?.takeIf { it.isAvailable }?.getBitmap(1280, 720) }.getOrNull(); latch.countDown() }
                latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
                bmp?.let { b -> runCatching { video.add(b) }; b.recycle() }
            }
        }.getOrElse { "interrompu : ${it.message}" }
        val out = video.finish()
        return if (out == null) "$r ; pas assez d'images pour une vidéo"
        else "$r ; hyperlapse enregistré (${video.count} images, %.1f s de vidéo) dans le journal du vol".format(video.count / 30.0).also { sysMessage("⏩ $it") }
    }

    /** Après la copie de la vidéo : un petit film avec le milieu de chaque plan MasterShots. Bloquant. */
    private fun montageMasterShots(dir: java.io.File) {
        val segs = agent?.shots?.lastSegments.orEmpty()
        if (segs.isEmpty()) return
        val src = dir.listFiles()?.filter {
            it.name.endsWith(".mp4", true) && !it.name.startsWith("hyperlapse") && !it.name.startsWith("mastershots")
        }?.maxByOrNull { it.length() }
        if (src == null) { sysMessage("🎬 Montage MasterShots : vidéo du drone introuvable (copie des vidéos ratée ?)"); return }
        val clips = segs.map { s ->
            val mid = (s.startS + s.endS) / 2
            val half = minOf(2.5, (s.endS - s.startS) / 2)
            (mid - half).coerceAtLeast(0.0) to (mid + half)
        }.filter { it.second - it.first > 0.8 }
        sysMessage("🎬 Montage MasterShots en cours (${clips.size} plans)…")
        val err = com.bebopia.app.android.Montage.make(getApplication(), src, clips, java.io.File(dir, "mastershots-montage.mp4"))
        sysMessage(if (err == null) "🎬 Montage MasterShots prêt dans le journal du vol" else "🎬 Montage MasterShots raté : $err")
    }

    /** Copie des photos (et vidéos) du vol sur le téléphone, dans le journal. Bloquant. */
    fun downloadMedia(videos: Boolean): String {
        val d = drone ?: return "pas de drone"
        val log = flightLog ?: return "journal indisponible"
        val dir = log.currentDir ?: return "aucun vol enregistré"
        if (d.flying) return "refusé : pose le drone d'abord"
        sysMessage("📥 Copie des ${if (videos) "photos et vidéos" else "photos"} du vol sur le téléphone…")
        val r = runCatching { d.downloadMedia(log.startedAt - 60_000, videos, dir) { sysMessage(it) } }.getOrElse { "échec : ${it.message}" }
        sysMessage("📥 $r")
        return r
    }

    /** Mode des manettes : regard libre (false) ou suit le regard (true). */
    var sticksFollowLook: Boolean
        get() = prefs.getBoolean("sticks_follow_look", false)
        private set(v) = prefs.edit().putBoolean("sticks_follow_look", v).apply()

    fun changeSticksMode(followLook: Boolean) {
        sticksFollowLook = followLook
        drone?.sticksFollowLook = followLook
        sysMessage(if (followLook) "🎮 Manettes : SUIT LE REGARD — « avant » est là où tu regardes, tourner la tête fait tourner le vol."
            else "🎮 Manettes : REGARD LIBRE — le manche gauche fixe le cap de vol, tu peux regarder autour sans changer de direction.")
        refresh()
    }

    private fun questButton(b: String) {
        if (b == "mode") { changeSticksMode(!sticksFollowLook); return }
        val now = System.currentTimeMillis()
        if (now - questButtonAt < 2000) return
        questButtonAt = now
        val a = agent ?: return
        when (b) {
            "takeoff" -> { sysMessage("🎮 Décollage demandé aux manettes."); a.humanTakeoff() }
            "land" -> { sysMessage("🎮 Atterrissage demandé aux manettes."); a.humanLand() }
        }
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

    // Démarrage : en dernier, une fois toutes les propriétés créées (sinon plantage au lancement).
    init {
        startDisplayGain()
        startNavTicks()
        startTerrainLoop()
        startTargetPreview()
        startGestures()
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
}
