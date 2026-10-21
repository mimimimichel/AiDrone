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
        val NEEDS_CONFIRM = setOf("takeoff", "move", "return_home", "tilt_camera", "search", "search_area",
            "go_look", "follow", "goto_point", "tour", "film", "retrace", "survey", "pass_door", "search_house", "come_back")
        /** Actions refusées s'il n'y a pas d'image. */
        val NEEDS_IMAGE = setOf("takeoff", "move", "return_home", "search", "search_area", "go_look", "follow", "film", "goto_point", "tour",
            "survey", "pass_door", "search_house", "come_back")
        /** Actions longues (affichées dans le casque pendant qu'elles durent). */
        val LONG = setOf("search", "search_area", "go_look", "follow", "goto_point", "tour", "film", "retrace", "return_home",
            "survey", "pass_door", "search_house", "come_back")

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
            "search_area" -> "mission : chercher « ${action["target"] ?: "?"} » sur le terrain" +
                (num(action, "radius_m").takeIf { it > 0 }?.let { " (rayon %.0f m)".format(it) } ?: "")
            "search" -> "chercher « ${action["target"] ?: "?"} »" + if (action["approach"] == true) " et m'en approcher" else ""
            "return_home" -> "retour au départ"
            "go_look" -> "aller là où je regarde"
            "follow" -> "suivre « ${action["target"] ?: "?"} »"
            "goto_point" -> "aller au point « ${action["point"] ?: "?"} »"
            "tour" -> "faire le tour des points marqués"
            "film" -> "plan de cinéma « ${action["shot"] ?: "?"} »"
            "retrace" -> "rentrer par le même chemin" + if (action["land"] == true) " et atterrir" else ""
            "survey" -> "repérer les lieux (tour complet)" + (action["target"]?.let { " en cherchant « $it »" } ?: "")
            "pass_door" -> "passer « ${action["door"] ?: "la porte"} »"
            "search_house" -> "chercher « ${action["target"] ?: "?"} » dans la maison, pièce par pièce"
            "come_back" -> "revenir à la pièce de départ"
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

    /** Vérification d'obstacle sur l'image avant chaque avancée (null sans clé Gemini). */
    var pathChecker: PathChecker? = null

    /**
     * Avant d'avancer : le modèle de vision regarde s'il y a un obstacle dans l'axe.
     * Renvoie la distance autorisée (raccourcie pour garder une marge), ou un message de refus.
     */
    fun safeForward(forward: Double): Pair<Double, String?> {
        val checker = pathChecker ?: return forward to null
        if (forward < 0.15) return forward to null
        val frame = eyes.captureJpegBase64() ?: return forward to "refusé : pas d'image pour vérifier la trajectoire"
        val r = try {
            checker.check(frame, forward)
        } catch (e: Exception) {
            // Vérification impossible (réseau) : on avance quand même, mais à petits pas.
            log("vérification de trajectoire impossible : ${e.message}")
            return minOf(forward, 0.4) to null
        }
        val margin = 0.6
        val nearest = r.nearestM
        if (r.clear && (nearest == null || nearest >= forward + margin)) return forward to null
        val allowed = (nearest ?: 0.0) - margin
        if (allowed >= 0.2) {
            system("👁 Obstacle à ~%.1f m devant (%s) : pas raccourci à %.1f m.".format(nearest, r.description.take(60), allowed))
            return allowed to null
        }
        system("👁 Obstacle devant (%s) : avance refusée.".format(r.description.take(80)))
        return 0.0 to "refusé : obstacle sur la trajectoire à ~%s m (%s). Recule, tourne ou monte pour le contourner."
            .format(nearest?.let { "%.1f".format(it) } ?: "?", r.description)
    }

    /** Recherche active d'un objet (null sans clé Gemini). */
    var finder: ObjectFinder? = null

    /** Mission de recherche sur un terrain (null sans clé Gemini). */
    var areaSearch: AreaSearch? = null

    /** Carte : points marqués, « va là », retour par le même chemin. */
    var nav: Navigator? = null

    /** Suivi d'une cible (null sans clé Gemini). */
    var follower: Follower? = null

    /** Plans de cinéma. */
    var shots: Shots? = null

    /** Mesure des distances par parallaxe (montée de 25 cm). */
    var ranger: Ranger? = null

    /** Repérage, mémoire de la scène, portes, recherche dans la maison. */
    var surveyor: Surveyor? = null
    var scene: SceneMemory? = null
    var doorPasser: DoorPasser? = null
    var house: HouseSearch? = null

    /** Chaque ligne terminée de la conversation (pour le journal de vol). */
    var onLine: ((Role, String) -> Unit)? = null

    /** Photo pour le journal de vol. */
    var onSnap: ((String) -> Unit)? = null

    /** Action en cours d'exécution (pour la flèche du déplacement dans le casque). */
    @Volatile var currentAction: Map<String, Any?>? = null
        private set

    /** Action longue en cours (suivi, plan de cinéma…), pour le casque. */
    @Volatile var activity: String? = null
        private set

    /** Retour automatique en cours (batterie, signal) : l'IA ne peut pas agir. */
    @Volatile var returning = false
        private set

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
        onLine?.invoke(role, text)
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
        var done: ChatLine? = null
        synchronized(chatLines) {
            if (openRole == Role.PILOT && role != Role.PILOT && !utteranceHandled) finished = utterance
            val last = chatLines.lastOrNull()
            if (openRole == role && last != null && last.role == role) {
                text = last.text + fragment
                chatLines[chatLines.size - 1] = ChatLine(role, text)
            } else {
                if (openRole != null) done = chatLines.lastOrNull { it.role == openRole }
                text = fragment.trimStart()
                chatLines.add(ChatLine(role, text))
                if (chatLines.size > 200) chatLines.removeAt(0)
                if (pilot) utteranceHandled = false
            }
            openRole = role
        }
        done?.let { onLine?.invoke(it.role, it.text) }
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
        var done: ChatLine? = null
        synchronized(chatLines) {
            if (openRole == Role.PILOT && !utteranceHandled) finished = utterance
            openRole?.let { r -> done = chatLines.lastOrNull { it.role == r } }
            openRole = null
        }
        done?.let { onLine?.invoke(it.role, it.text) }
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

    /** « mode confiance » / « mode prudent » à la voix (ou écrit) : changé par l'application. */
    var onTrust: ((Boolean) -> Unit)? = null

    /** « à toi », « reprends les commandes » : le pilote rend la main à l'IA. */
    var onHandBack: (() -> Unit)? = null

    /** Le pilote reprend la main avec la radiocommande. */
    fun pilotTakeover(with: String = "la radiocommande") {
        paused = true
        pending = null
        lastText = "Le pilote a la main"
        system("🕹 Tu as repris la main avec $with : l'IA est en pause. Touche ▶ IA ou dis « à toi » pour lui rendre les commandes.")
        speak("D'accord, tu as la main.")
        onEvent?.invoke("Le pilote a repris les commandes avec $with : tu es en pause, ne bouge plus.", false)
    }

    private fun yesNo(text: String): Boolean {
        val norm = normalize(text)
        val words = norm.split(' ').filter { it.isNotBlank() }.toSet()
        if (norm.contains("mode confiance") || norm.contains("je te fais confiance")) {
            onTrust?.invoke(true)
            return true
        }
        if (norm.contains("mode prudent") || norm.contains("mode validation") || norm.contains("demande moi avant")) {
            onTrust?.invoke(false)
            return true
        }
        if (words.size <= 6 && (norm.contains("a toi") || norm.contains("reprends les commandes") || norm.contains("reprends la main"))) {
            onHandBack?.invoke()
            return true
        }
        // Plan en attente (et pas de déplacement en attente) : le oui/non porte sur le plan.
        if (pending == null && plan?.let { !it.approved && !it.finished } == true && words.size <= 4) {
            if (words.any { it in YES_WORDS } || norm.contains("vas y")) {
                approvePlan()
                return true
            }
            if (words.any { it in NO_WORDS }) {
                rejectPlan()
                return true
            }
        }
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
    /** Note la vue au moment de l'ordre : « à droite » restera par rapport à elle, même si le pilote tourne la tête ensuite. */
    private fun withView(action: Map<String, Any?>): Map<String, Any?> =
        if (action["type"] == "move" && action["_vue"] == null) action + ("_vue" to drone.lookHeading()) else action

    fun aiAction(actionIn: Map<String, Any?>): String {
        val action = withView(actionIn)
        val t = action["type"] as? String ?: return "refusé : type d'action manquant"
        if (t == "land") return execute(action)
        if (returning) return "refusé : retour automatique au départ en cours, n'agis pas."
        if (drone.manualControl) return "refusé : le pilote a repris la main avec sa radiocommande. Attends qu'il te rende les commandes."
        if (paused) return "refusé : IA en pause. Le pilote doit appuyer sur « ▶ IA » pour te laisser piloter."
        if (steps >= drone.limits.maxSteps) {
            thread(isDaemon = true) { autoStop("nombre maximum d'actions atteint, j'atterris") }
            return "refusé : nombre maximum d'actions atteint, atterrissage"
        }
        if (t in NEEDS_IMAGE && eyes.captureJpegBase64() == null) {
            system("Action « ${describe(action)} » refusée : pas d'image.")
            return "refusé : pas d'image de la caméra"
        }
        val p = plan
        if (p != null && !p.approved && !p.finished) {
            return "refusé : ton plan n'est pas encore validé par le pilote. Attends sa réponse."
        }
        // Plan validé par le pilote : ses déplacements ne redemandent pas d'accord (les garde-fous restent).
        if (confirm && t in NEEDS_CONFIRM && p?.running != true) {
            pending = action
            lastText = "Propose : ${describe(action)}"
            changed()
            return "en attente : le pilote doit dire oui ou non (ou toucher Oui / Non). " +
                "N'appelle pas l'outil une deuxième fois, l'application te dira sa réponse."
        }
        return execute(action)
    }

    // ---- plan pour atteindre un objectif ----------------------------------------------
    @Volatile var plan: Plan? = null
        private set

    /** L'IA propose un plan : il attend la validation du pilote. */
    fun proposePlan(goal: String, stepTexts: List<String>): String {
        val steps = stepTexts.map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        if (steps.isEmpty()) return "refusé : le plan n'a aucune étape"
        pending = null
        if (!confirm) {
            // Mode confiance : le plan est lancé tout de suite, sans attendre le pilote.
            plan = Plan(goal.ifBlank { "objectif du pilote" }, steps.map { PlanStep(it) }, approved = true)
            this.goal = goal
            paused = false
            idle = false
            lastText = "Plan lancé (mode confiance)"
            system("Mode confiance : plan lancé directement (${steps.size} étapes). Dis « stop » à tout moment.")
            if (needsSurvey()) {
                thread(isDaemon = true) {
                    val r = execute(mapOf("type" to "survey"))
                    onEvent?.invoke("Repérage fait avant ton plan : $r. Adapte le plan si besoin, puis commence l'étape 1.", true)
                }
                return "plan validé automatiquement (mode confiance). L'application fait d'abord un repérage des lieux (environ 30 s) : " +
                    "dis-le au pilote en une phrase et attends son résultat avant d'agir."
            }
            return "plan validé automatiquement (mode confiance) : explique-le en une phrase et commence l'étape 1 tout de suite."
        }
        plan = Plan(goal.ifBlank { "objectif du pilote" }, steps.map { PlanStep(it) })
        this.goal = goal
        lastText = "Plan proposé : attend ta validation"
        system("Plan proposé (${steps.size} étapes) : dis « oui » ou touche « Lancer le plan ».")
        return "en attente : le plan s'affiche chez le pilote, qui doit dire oui ou toucher « Lancer le plan ». " +
            "Explique-le-lui en une ou deux phrases, puis attends. N'agis pas avant sa validation."
    }

    fun approvePlan() {
        val p = plan ?: return
        if (p.approved || p.finished) return
        plan = p.copy(approved = true)
        paused = false
        idle = false
        lastText = "Plan lancé"
        system("Plan validé : l'IA pilote et exécute les étapes. Dis « stop » à tout moment.")
        if (needsSurvey()) {
            // Regarder avant d'agir : repérage complet avant la première étape
            thread(isDaemon = true) {
                val r = execute(mapOf("type" to "survey"))
                onEvent?.invoke("Le pilote a validé ton plan. L'application a d'abord repéré les lieux : $r. " +
                    "Adapte ton plan si ce repérage change quelque chose (dis-le), puis commence l'étape 1 et appelle plan_progress.", true)
            }
            return
        }
        onEvent?.invoke(
            "Le pilote a validé ton plan, tu peux piloter. Commence l'étape 1 : regarde l'image, agis, " +
                "puis vérifie sur la nouvelle image et appelle plan_progress.", true,
        )
    }

    fun rejectPlan() {
        val p = plan ?: return
        plan = null
        goal = ""
        system("Plan refusé.")
        onEvent?.invoke("Le pilote a refusé ton plan pour « ${p.goal} ». Demande-lui ce qu'il veut changer.", true)
    }

    /** Le pilote arrête le plan en cours. */
    fun cancelPlan() {
        val p = plan ?: return
        plan = null
        goal = ""
        paused = true
        pending = null
        thread(isDaemon = true) { runCatching { drone.cancelMove() } }
        system("Plan arrêté : l'IA est en pause, le drone reste sur place.")
        onEvent?.invoke("Le pilote a arrêté le plan « ${p.goal} ». Tu es en pause : ne bouge plus.", false)
    }

    /** L'IA signale l'avancement d'une étape (numérotées à partir de 1). */
    fun planProgress(step: Int, state: StepState, note: String): String {
        val p = plan ?: return "pas de plan en cours"
        if (step < 1 || step > p.steps.size) return "refusé : étape $step inexistante (1 à ${p.steps.size})"
        val steps = p.steps.mapIndexed { i, s -> if (i == step - 1) PlanStep(s.text, state, note) else s }
        plan = p.copy(steps = steps)
        if (state == StepState.FAILED) system("Étape $step en échec${if (note.isNotBlank()) " : $note" else ""}")
        changed()
        val next = steps.indexOfFirst { it.state == StepState.TODO }
        return if (next >= 0) "noté. Étape suivante : ${next + 1}. ${steps[next].text}"
        else "noté. Toutes les étapes sont traitées : appelle finish_plan."
    }

    fun finishPlan(success: Boolean, summary: String): String {
        val p = plan ?: return "pas de plan en cours"
        plan = p.copy(finished = true, success = success, summary = summary)
        goal = ""
        idle = true
        system((if (success) "Objectif atteint" else "Objectif non atteint") + if (summary.isNotBlank()) " : $summary" else "")
        changed()
        return "noté. Reste en stationnaire et attends les instructions du pilote."
    }

    /** Plan tenu à jour par une mission de recherche. */
    fun missionPlan(p: Plan) {
        plan = p
        changed()
    }

    /** Le pilote ferme un plan terminé. */
    fun clearPlan() {
        if (plan?.finished == true) {
            plan = null
            changed()
        }
    }

    /** Faut-il un repérage avant d'agir (en vol, pas de repérage récent ici) ? */
    private fun needsSurvey(): Boolean {
        val sc = scene ?: return false
        if (surveyor == null || !drone.flying) return false
        return !sc.surveyFresh(drone.positionNE())
    }

    /** Le plan pour drone_status. */
    fun planText(): String? = plan?.describe()

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
            checkBattery()
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
            if (checkBattery()) return
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
            pending = withView(action)
            lastText = "Propose : ${describe(action)}"
            changed()
            return
        }
        execute(withView(action))
    }

    private fun execute(action: Map<String, Any?>): String = synchronized(execLock) {
        steps++
        val type = action["type"] as? String
        currentAction = action
        if (type in LONG) {
            activity = describe(action)
            changed()
        }
        val result = try {
            when (action["type"] as? String) {
                "takeoff" -> drone.takeoff()
                "land" -> drone.land()
                "move" -> try {
                    drone.moveRef = action["_vue"] as? Double
                    val f0 = num(action, "forward")
                    val r0 = num(action, "right")
                    var up = num(action, "up")
                    val here = drone.positionNE()
                    // En intérieur, sans repérage récent : pas de déplacement à l'aveugle sur le côté ou en arrière
                    val blind = drone.indoor && scene != null && surveyor != null && !scene!!.surveyFresh(here) &&
                        (kotlin.math.abs(r0) > 0.05 || f0 < -0.35) // reculer de 30 cm (d'où il vient) reste permis
                    // Avant de descendre : regarder ce qu'il y a dessous
                    var belowNote = ""
                    if (up < -0.25 && surveyor != null) {
                        val below = scene?.belowFresh(here) ?: surveyor!!.lookDown()
                        if (below != null) {
                            val maxDown = (below - 0.3).coerceAtLeast(0.0)
                            if (-up > maxDown) {
                                up = -maxDown
                                belowNote = " (descente limitée à %.1f m : %.1f m libres dessous)".format(maxDown, below)
                            }
                        }
                    }
                    if (blind) "refusé : en intérieur, je ne vais pas sur le côté ni en arrière sans repérage récent " +
                        "(je ne vois pas ce qu'il y a). Fais d'abord un repérage (survey), ou avance vers ce que tu vois."
                    else if (up > -0.05 && num(action, "up") < -0.25 && kotlin.math.abs(f0) < 0.05 && kotlin.math.abs(r0) < 0.05 && num(action, "yaw") == 0.0)
                        "refusé : pas de place pour descendre (sol ou objet juste dessous)$belowNote"
                    else {
                    // Vérification par l'image seulement si la caméra regarde dans la direction du déplacement
                    // (le pilote peut regarder ailleurs avec le casque).
                    val dist = kotlin.math.hypot(f0, r0)
                    val motion = Math.toDegrees(kotlin.math.atan2(r0, f0))
                    val seen = dist >= 0.15 && kotlin.math.abs(com.bebopia.app.core.norm180(motion - drone.cameraOffset())) < 35
                    var (ok, refusal) = if (seen) safeForward(dist) else dist to null
                    // En intérieur, avant d'avancer vers ce qu'on voit : distance mesurée (parallaxe), plus fiable
                    var measuredNote = ""
                    if (refusal == null && seen && drone.indoor && f0 > 0.1) {
                        ranger?.let { rg ->
                            val d = rg.recent() ?: rg.measure()
                            val c = d?.center
                            if (c != null && c < com.bebopia.app.core.vision.Parallax.MAX_M) {
                                val room = c - 0.5
                                measuredNote = " (distance mesurée devant : %.1f m)".format(c)
                                if (room < 0.1) refusal = "refusé : obstacle mesuré à %.1f m devant. Recule, tourne ou monte.".format(c)
                                else if (room < ok) ok = room
                            }
                        }
                    }
                    if (refusal != null) refusal
                    else {
                        val k = if (dist > 0) (ok / dist).coerceIn(0.0, 1.0) else 1.0
                        val r = drone.move(f0 * k, r0 * k, up, num(action, "yaw")) + belowNote + measuredNote
                        if (k < 0.99) r + " (raccourci à %.1f m à cause d'un obstacle)".format(ok) else r
                    }
                    }
                } finally {
                    drone.moveRef = null
                }
                "tilt_camera" -> drone.tiltCamera(num(action, "degrees"))
                "hover" -> drone.hover(num(action, "seconds", 1.0))
                "return_home" -> drone.returnHome()
                "search_area" -> areaSearch?.run(
                    action["target"]?.toString() ?: "",
                    num(action, "radius_m").takeIf { it > 0 },
                    num(action, "altitude_m").takeIf { it > 0 },
                    action["approach"] == true,
                    action["classe"]?.toString(),
                ) ?: "refusé : la mission de recherche a besoin d'une clé Gemini"
                "search" -> finder?.search(action["target"]?.toString() ?: "", action["approach"] == true, action["classe"]?.toString())
                    ?: "refusé : la recherche d'objet a besoin d'une clé Gemini"
                "go_look" -> nav?.goToLook() ?: "refusé : navigation indisponible"
                "goto_point" -> nav?.goToPoint(action["point"]?.toString() ?: "") ?: "refusé : navigation indisponible"
                "tour" -> nav?.tour() ?: "refusé : navigation indisponible"
                "retrace" -> nav?.retrace(action["land"] == true) ?: "refusé : navigation indisponible"
                "survey" -> surveyor?.survey(action["target"]?.toString()?.takeIf { it.isNotBlank() },
                    num(action, "climb_m").takeIf { it > 0 })?.text ?: "refusé : le repérage a besoin d'une clé Gemini"
                "pass_door" -> {
                    val want = action["door"]?.toString().orEmpty()
                    val here = drone.positionNE()
                    val known = scene?.doors()?.let { ds ->
                        ds.firstOrNull { want.isNotBlank() && (it.label.contains(want, true) || want.contains(it.label, true)) }
                            ?: ds.minByOrNull { kotlin.math.hypot(it.n - here.first, it.e - here.second) }.takeIf { want.isBlank() }
                    }
                    doorPasser?.pass(want.ifBlank { known?.label ?: "porte" }, known) ?: "refusé : le passage de porte a besoin d'une clé Gemini"
                }
                "search_house" -> house?.run(action["target"]?.toString() ?: "") ?: "refusé : la recherche dans la maison a besoin d'une clé Gemini"
                "come_back" -> house?.comeBack() ?: "refusé : indisponible"
                "follow" -> {
                    onSnap?.invoke("suivi " + (action["target"] ?: ""))
                    follower?.follow(action["target"]?.toString() ?: "", num(action, "minutes").takeIf { it > 0 }, action["classe"]?.toString())
                        ?: "refusé : le suivi a besoin d'une clé Gemini"
                }
                "film" -> shots?.film(action["shot"]?.toString() ?: "", num(action, "distance_m").takeIf { it > 0 }, action["record"] != false)
                    ?: "refusé : plans de cinéma indisponibles"
                "done" -> {
                    idle = true
                    "mission terminée, j'attends tes instructions"
                }
                else -> drone.hover(1.0) + " (action inconnue « ${action["type"]} »)"
            }
        } catch (e: Exception) {
            "échec : ${e.message}"
        } finally {
            if (type in LONG) activity = null
            currentAction = null
        }
        if (type in LONG && (result.startsWith("trouvé") || result.startsWith("arrivé"))) onSnap?.invoke(result.take(40))
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

    /** Batterie : retour par le même chemin quand il est temps de rentrer, atterrissage sur place en dernier recours. */
    private fun checkBattery(): Boolean {
        val bat = drone.battery() ?: return false
        if (paused || !drone.flying || drone.manualControl || returning) return false
        val dist = drone.distanceFromHome()
        if (nav != null && dist > 5 && bat < AreaSearch.returnBattery(dist) && bat >= drone.limits.minBattery - 5) {
            autoReturn("batterie à $bat %")
            return true
        }
        if (bat < drone.limits.minBattery) {
            autoStop("batterie faible ($bat %), j'atterris")
            return true
        }
        return false
    }

    /**
     * Retour automatique au départ par le même chemin, puis atterrissage (batterie, signal radio faible).
     * Ce qui est en cours est arrêté d'abord. « stop » l'interrompt comme le reste.
     */
    fun autoReturn(reason: String) {
        val n = nav ?: return
        if (returning || !drone.flying || drone.manualControl) return
        returning = true
        pending = null
        system("↩ $reason : je rentre au départ par le même chemin, puis j'atterris. Dis « stop » pour m'arrêter.")
        speak("Je rentre.")
        onEvent?.invoke("L'application ramène le drone au départ par le même chemin ($reason). N'agis pas pendant le retour.", false)
        thread(isDaemon = true, name = "retour") {
            paused = true // arrête l'action en cours
            runCatching { drone.cancelMove() }
            runCatching { drone.stopVelocity() }
            synchronized(execLock) {
                try {
                    paused = false
                    activity = "Retour au départ par le même chemin"
                    changed()
                    val r = runCatching { n.retrace(land = true) }.getOrElse { "échec : ${it.message}" }
                    system("↩ $r")
                    onEvent?.invoke("Retour automatique terminé : $r", false)
                } finally {
                    activity = null
                    paused = true
                    returning = false
                    changed()
                }
            }
        }
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
