package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import java.text.Normalizer
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/** Fournit l'image actuelle de la caméra en JPEG base64 (null si indisponible). Bloquant. */
fun interface Eyes { fun captureJpegBase64(): String? }

/** Le modèle de vision (mode « une question, une réponse »). Bloquant. */
fun interface Brain { fun decide(jpegBase64: String?, userText: String): Decision }

fun interface Speaker { fun say(text: String) }

enum class Role { PILOT, DRONE, SYSTEM }
class ChatLine(val role: Role, val text: String)

/**
 * Boucle de l'agent : écouter -> regarder -> décider -> parler -> agir, dans son propre thread.
 * L'IA ne touche jamais aux moteurs : elle propose, ce code vérifie et exécute.
 *
 * Quand [brainOf] ne donne pas de cerveau (conversation en direct avec Gemini Live), la conversation se passe ailleurs :
 * l'IA propose ses actions par [aiAction], et ce que dit le pilote arrive par [liveTranscript].
 */
class Agent(
    private val drone: DroneBase,
    private val eyes: Eyes,
    /** Le cerveau « question/réponse » (Claude), ou null en conversation en direct. Relu à chaque étape. */
    private val brainOf: () -> Brain?,
    private val speaker: Speaker,
    @Volatile var goal: String = "",
    @Volatile var confirm: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** Actions soumises à ton accord en mode validation (atterrir n'en a jamais besoin). */
        val NEEDS_CONFIRM = setOf("takeoff", "move", "return_home", "tilt_camera")
        /** Actions refusées s'il n'y a pas d'image. */
        val NEEDS_IMAGE = setOf("takeoff", "move", "return_home")

        val STOP_WORDS = setOf("stop", "stoppe", "arrete", "arretes", "arretez", "pause", "halte", "stopper")
        val LAND_PHRASES = listOf("atterri", "pose toi", "pose-toi", "pose le drone")
        val YES_WORDS = setOf("oui", "ok", "okay", "go", "valide", "confirme", "d'accord", "vas-y", "vasy", "ouais")
        val NO_WORDS = setOf("non", "annule", "annuler", "refuse")

        fun normalize(text: String): String {
            val n = Normalizer.normalize(text.lowercase().replace('’', '\''), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return n.replace(Regex("[^a-z0-9'\\- ]"), " ")
        }

        fun num(a: Map<String, Any?>, key: String, default: Double = 0.0): Double = when (val v = a[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }

        fun describe(action: Map<String, Any?>): String = when (val t = action["type"] as? String) {
            "move" -> {
                val parts = listOf(
                    "avant" to num(action, "forward") to "m",
                    "droite" to num(action, "right") to "m",
                    "haut" to num(action, "up") to "m",
                    "rotation" to num(action, "yaw") to "°",
                ).filter { it.first.second != 0.0 }
                    .map { (lv, unit) -> "%s %+.1f%s".format(lv.first, lv.second, unit) }
                "déplacement " + parts.joinToString(", ").ifEmpty { "nul" }
            }
            "tilt_camera" -> "caméra à %.0f°".format(num(action, "degrees"))
            "takeoff" -> "décollage"
            "return_home" -> "retour au départ"
            else -> t ?: "?"
        }
    }

    @Volatile var paused = true
        private set
    @Volatile var thinking = false
        private set
    @Volatile var pending: Map<String, Any?>? = null
        private set
    @Volatile var idle = false
        private set
    @Volatile var steps = 0
        private set
    @Volatile var lastText = "En pause : appuie sur IA pour la laisser piloter"
        private set

    /** Appelé à chaque changement notable (pour rafraîchir l'interface). */
    var onChange: (() -> Unit)? = null

    /** Conversation en direct : où envoyer les messages écrits du pilote. */
    var forward: ((String) -> Unit)? = null

    /** Conversation en direct : événement à signaler à l'IA (reply = elle doit répondre). */
    var onEvent: ((text: String, reply: Boolean) -> Unit)? = null

    private val live get() = brainOf() == null
    private val execLock = Any()

    private val history = mutableListOf<String>()
    private val chatLines = mutableListOf<ChatLine>()
    private val inbox = LinkedBlockingDeque<String>()
    @Volatile private var approved: Map<String, Any?>? = null
    @Volatile private var running = false
    private var noImageWarned = false

    val chat: List<ChatLine> get() = synchronized(chatLines) { chatLines.toList() }

    private fun conversationForPrompt(): List<String> = synchronized(chatLines) {
        chatLines.filter { it.role != Role.SYSTEM }
            .map { (if (it.role == Role.PILOT) "Pilote : " else "Drone : ") + it.text }
    }

    fun addChat(role: Role, text: String) {
        synchronized(chatLines) {
            chatLines.add(ChatLine(role, text))
            if (chatLines.size > 200) chatLines.removeAt(0)
        }
        changed()
    }

    private fun system(text: String) {
        log(text)
        addChat(Role.SYSTEM, text)
    }

    private fun changed() = onChange?.invoke()

    private fun speak(text: String) {
        // En direct, la voix de l'IA arrive déjà dans la conversation par sa transcription.
        if (!live) addChat(Role.DRONE, text)
        speaker.say(text)
    }

    // ---- conversation en direct --------------------------------------------------------
    private var openRole: Role? = null
    private var utterance = ""
    private var utteranceHandled = false

    /** Morceau de transcription (voix du pilote ou de l'IA), affiché au fil de l'eau. */
    fun liveTranscript(pilot: Boolean, fragment: String) {
        val role = if (pilot) Role.PILOT else Role.DRONE
        val text: String
        var finished: String? = null
        synchronized(chatLines) {
            if (openRole == Role.PILOT && role != Role.PILOT && !utteranceHandled) finished = utterance
            val last = chatLines.lastOrNull()
            if (openRole == role && last != null && last.role == role) {
                text = last.text + fragment
                chatLines[chatLines.size - 1] = ChatLine(role, text)
            } else {
                text = fragment.trimStart()
                chatLines.add(ChatLine(role, text))
                if (chatLines.size > 200) chatLines.removeAt(0)
                if (pilot) utteranceHandled = false
            }
            openRole = role
        }
        // Ta phrase est finie (l'IA commence à répondre) : on regarde si c'était un oui ou un non.
        finished?.let { utteranceHandled = yesNo(it) }
        if (pilot) {
            utterance = text
            // « stop » et « atterris » : tout de suite, sans attendre la fin de la phrase.
            if (!utteranceHandled) utteranceHandled = urgentWords(text)
        }
        changed()
    }

    fun liveTurnEnd() {
        var finished: String? = null
        synchronized(chatLines) {
            if (openRole == Role.PILOT && !utteranceHandled) finished = utterance
            openRole = null
        }
        finished?.let { utteranceHandled = yesNo(it) }
    }

    /**
     * Mots de sécurité et oui/non, traités tout de suite par l'application, sans l'IA.
     * Renvoie true si la phrase a été prise en charge.
     */
    private fun safetyWords(text: String): Boolean = urgentWords(text) || yesNo(text)

    private fun urgentWords(text: String): Boolean {
        val norm = normalize(text)
        val words = norm.split(' ').filter { it.isNotBlank() }.toSet()
        if (LAND_PHRASES.any { norm.contains(it) }) {
            humanLand()
            return true
        }
        if (words.any { it in STOP_WORDS }) {
            stopNow()
            return true
        }
        return false
    }

    private fun yesNo(text: String): Boolean {
        val norm = normalize(text)
        val words = norm.split(' ').filter { it.isNotBlank() }.toSet()
        if (pending != null && words.size <= 3) {
            if (words.any { it in YES_WORDS } || norm.contains("vas y")) {
                approve()
                return true
            }
            if (words.any { it in NO_WORDS }) {
                reject()
                return true
            }
        }
        return false
    }

    /**
     * Action proposée par l'IA en direct (outil drone_action). Bloquant : renvoie le résultat
     * une fois l'action faite, mise en attente de ton accord, ou refusée.
     */
    fun aiAction(action: Map<String, Any?>): String {
        val t = action["type"] as? String ?: return "refusé : type d'action manquant"
        if (t == "land") return execute(action)
        if (paused) return "refusé : IA en pause. Le pilote doit appuyer sur « ▶ IA » pour te laisser piloter."
        if (steps >= drone.limits.maxSteps) {
            thread(isDaemon = true) { autoStop("nombre maximum d'actions atteint, j'atterris") }
            return "refusé : nombre maximum d'actions atteint, atterrissage"
        }
        if (t in NEEDS_IMAGE && eyes.captureJpegBase64() == null) {
            system("Action « ${describe(action)} » refusée : pas d'image.")
            return "refusé : pas d'image de la caméra"
        }
        if (confirm && t in NEEDS_CONFIRM) {
            pending = action
            lastText = "Propose : ${describe(action)}"
            changed()
            return "en attente : le pilote doit dire oui ou non (ou toucher Oui / Non). " +
                "N'appelle pas l'outil une deuxième fois, l'application te dira sa réponse."
        }
        return execute(action)
    }

    // ---- cycle de vie -----------------------------------------------------------------
    fun start() {
        if (running) return
        running = true
        thread(name = "agent", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        paused = true
        pending = null
    }

    // ---- contrôle humain (tous ces appels sont sûrs depuis le thread de l'interface) -----
    fun togglePause() {
        paused = !paused
        pending = null
        idle = false
        lastText = if (paused) "IA en pause" else "IA active"
        system(lastText)
        onEvent?.invoke(
            if (paused) "Le pilote t'a mis en pause : tu ne peux plus bouger." else "Le pilote te laisse piloter : tu peux agir.",
            false,
        )
    }

    fun stopNow() {
        paused = true
        pending = null
        lastText = "Stop : IA en pause"
        system(lastText)
        speak("Je m'arrête et je reste sur place.")
        thread(isDaemon = true) { runCatching { drone.cancelMove() } }
    }

    fun humanLand() {
        paused = true
        pending = null
        lastText = "Atterrissage demandé"
        speak("J'atterris.")
        thread(isDaemon = true) {
            val r = runCatching { drone.land() }.getOrElse { "échec : ${it.message}" }
            system(r)
        }
    }

    fun humanTakeoff() {
        thread(isDaemon = true) {
            val r = runCatching { drone.takeoff() }.getOrElse { "échec : ${it.message}" }
            system(r)
        }
    }

    fun humanEmergency() {
        paused = true
        pending = null
        lastText = "ARRÊT D'URGENCE"
        thread(isDaemon = true) {
            val r = runCatching { drone.emergency() }.getOrElse { "échec : ${it.message}" }
            system(r)
        }
    }

    fun approve() {
        val p = pending ?: return
        pending = null
        approved = p
        changed()
    }

    fun reject() {
        val p = pending ?: return
        log("proposition refusée : ${describe(p)}")
        synchronized(history) { history.add("proposition refusée par le pilote : $p") }
        pending = null
        idle = false
        if (live) onEvent?.invoke("Le pilote a refusé ta proposition (${describe(p)}).", false)
        else inbox.add("(j'ai refusé ta dernière proposition)")
        changed()
    }

    /** Le détecteur d'obstacles vient d'arrêter le drone. */
    fun obstacleStop(ttcS: Double, moving: Boolean) {
        if (moving) {
            pending = null
            lastText = "Obstacle devant : arrêt"
            system("Détecteur d'obstacles : quelque chose devant (contact estimé dans %.1f s), déplacement arrêté.".format(ttcS))
            synchronized(history) { history.add("le détecteur d'obstacles a arrêté le drone : obstacle devant") }
            speak("Obstacle devant moi, je me suis arrêté.")
        } else {
            lastText = "Attention : quelque chose approche"
            system("Détecteur d'obstacles : quelque chose approche devant (contact estimé dans %.1f s). Avance bloquée 5 s.".format(ttcS))
            synchronized(history) { history.add("le détecteur d'obstacles a vu quelque chose approcher devant") }
            speak("Attention, quelque chose approche devant moi.")
        }
    }

    fun linkLost() {
        paused = true
        pending = null
        lastText = "Liaison perdue"
        system("Liaison avec le drone perdue : l'IA est en pause.")
        speaker.say("J'ai perdu la liaison.")
    }

    /** Ce que tu dis ou écris. Les ordres de sécurité sont traités tout de suite, sans l'IA. */
    fun hear(text: String) {
        addChat(Role.PILOT, text)
        synchronized(chatLines) { openRole = null }
        if (live) {
            // En direct : l'IA doit savoir ce qui a été dit, même un ordre de sécurité déjà traité.
            safetyWords(text)
            forward?.invoke(text)
            return
        }
        if (safetyWords(text)) return
        pending = null // un nouvel ordre remplace la proposition en attente
        idle = false
        inbox.add(text)
        changed()
    }

    // ---- boucle -------------------------------------------------------------------------
    private fun modeText(hasImage: Boolean): String {
        var t = if (paused) {
            "PAUSE : tu ne peux pas bouger (action \"none\" uniquement). Réponds au pilote. " +
                "S'il veut que tu bouges, dis-lui d'appuyer sur le bouton IA pour te laisser piloter."
        } else {
            "ACTIF : tu peux agir. Déplacement max par action : %.1f m.".format(drone.limits.maxStepM)
        }
        if (!paused && confirm) {
            t += " Mode validation : chaque déplacement est soumis au pilote avant exécution ; " +
                "dans \"dire\", annonce ce que tu proposes de faire."
        }
        if (!hasImage) t += " Pas d'image disponible."
        return t
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }

    private fun loop() {
        while (running) {
            try {
                step()
            } catch (e: Exception) {
                system("Erreur interne de l'agent : ${e.message}")
                sleep(1000)
            }
        }
    }

    private fun step() {
        approved?.let { action ->
            approved = null
            if (!paused) {
                val r = execute(action)
                onEvent?.invoke("Le pilote a validé « ${describe(action)} ». Résultat : $r", true)
            }
            return
        }

        val brain = brainOf()
        if (brain == null) {
            // En direct : la conversation se passe ailleurs, ici on surveille la batterie.
            val bat = drone.battery()
            if (!paused && drone.flying && bat != null && bat < drone.limits.minBattery) {
                autoStop("batterie faible ($bat %), j'atterris")
            }
            sleep(150)
            return
        }

        val msg = inbox.pollFirst()
        val waiting = pending != null || idle
        if (msg == null && (paused || waiting)) {
            sleep(100)
            return
        }

        if (!paused) {
            val bat = drone.battery()
            if (bat != null && bat < drone.limits.minBattery) {
                autoStop("batterie faible ($bat %), j'atterris")
                return
            }
            if (steps >= drone.limits.maxSteps) {
                autoStop("nombre maximum d'actions atteint, j'atterris")
                return
            }
        }

        var frame = eyes.captureJpegBase64()
        var tries = 0
        while (frame == null && tries < 10 && running) {
            sleep(200)
            frame = eyes.captureJpegBase64()
            tries++
        }
        if (frame == null && msg == null) {
            if (!noImageWarned) {
                system("Pas d'image de la caméra : l'IA attend.")
                noImageWarned = true
            }
            sleep(500)
            return
        }
        if (frame != null) noImageWarned = false

        val history = synchronized(this.history) { this.history.toList() }
        val text = Prompt.buildUserText(goal, modeText(frame != null), drone.state(), history,
            conversationForPrompt(), msg, frame != null)

        thinking = true
        changed()
        val decision = try {
            brain.decide(frame, text)
        } catch (e: Exception) {
            system("Erreur de l'IA : ${e.message}")
            Decision("", "erreur", "", mapOf("type" to "hover", "seconds" to 1.0), null)
        } finally {
            thinking = false
            changed()
        }
        if (!running) return

        if (decision.say.isNotBlank()) speak(decision.say.trim())

        val action = decision.action
        val t = decision.type
        idle = !(decision.continueMission ?: goal.isNotBlank())
        log("vu : ${decision.observation} | ${decision.reasoning} | action : $action")

        if (t == "none") return
        if (paused) {
            log("action ignorée : IA en pause")
            return
        }
        if (inbox.isNotEmpty()) {
            log("nouveau message reçu pendant la réflexion : je reconsidère")
            return
        }
        if (frame == null && t in NEEDS_IMAGE) {
            synchronized(this.history) { this.history.add("$action -> refusé : pas d'image") }
            system("Action « ${describe(action)} » refusée : pas d'image.")
            return
        }
        if (confirm && t in NEEDS_CONFIRM) {
            pending = action
            lastText = "Propose : ${describe(action)}"
            changed()
            return
        }
        execute(action)
    }

    private fun execute(action: Map<String, Any?>): String = synchronized(execLock) {
        steps++
        val result = try {
            when (action["type"] as? String) {
                "takeoff" -> drone.takeoff()
                "land" -> drone.land()
                "move" -> drone.move(num(action, "forward"), num(action, "right"), num(action, "up"), num(action, "yaw"))
                "tilt_camera" -> drone.tiltCamera(num(action, "degrees"))
                "hover" -> drone.hover(num(action, "seconds", 1.0))
                "return_home" -> drone.returnHome()
                "done" -> {
                    idle = true
                    "mission terminée, j'attends tes instructions"
                }
                else -> drone.hover(1.0) + " (action inconnue « ${action["type"]} »)"
            }
        } catch (e: Exception) {
            "échec : ${e.message}"
        }
        log("étape $steps : $action -> $result")
        synchronized(history) { history.add("étape $steps : $action -> $result") }
        lastText = "${describe(action)} : $result"
        if (result.startsWith("refusé") || result.startsWith("échec")) {
            system(result)
            if (!live) speaker.say("Action impossible.")
        }
        changed()
        result
    }

    private fun autoStop(reason: String) {
        paused = true
        pending = null
        lastText = reason
        system(reason)
        speaker.say(reason.replaceFirstChar { it.uppercase() } + ".")
        val r = runCatching { drone.land() }.getOrElse { "échec : ${it.message}" }
        system(r)
    }
}
