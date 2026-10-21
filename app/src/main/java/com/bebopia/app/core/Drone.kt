package com.bebopia.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Place libre autour du drone, d'après le dernier repérage (murs, meubles, plafond).
 * allowed : distance (m) qu'on peut parcourir dans une direction absolue depuis [pos] (marge comprise), null = inconnu.
 */
interface SpaceGuard {
    fun allowed(bearingDeg: Double, pos: Pair<Double, Double>): Double?
    /** Altitude du plafond (m), si on l'a estimée. */
    fun ceilingAlt(): Double?
}

/** Réglages de sécurité. Ajuste-les avant de voler. */
data class Limits(
    @Volatile var maxAltitudeM: Double = 3.0, // plafond (envoyé au drone et vérifié ici, réglable : 2 à 120 m, la limite légale)
    val minAltitudeM: Double = 0.6,   // l'IA ne descend pas plus bas en vol
    @Volatile var maxDistanceM: Double = 15.0, // rayon autour du décollage (estimation, réglable : 5 à 500 m)
    val missionStepM: Double = 20.0,  // longueur max d'un segment pendant une mission de recherche
    @Volatile var maxStepM: Double = 1.0, // déplacement max par action (réglable : 0,2 à 1 m)
    val maxYawDeg: Double = 90.0,     // rotation max par action
    val maxSteps: Int = 40,           // actions max par session IA
    val minBattery: Int = 25,         // en dessous (%) : atterrissage automatique
)

/**
 * Logique commune à tous les drones : limites de sécurité et position estimée (odométrie).
 *
 * Repère : forward > 0 avancer, right > 0 à droite, up > 0 monter,
 * yaw > 0 tourner à droite (degrés). Toutes les méthodes sont bloquantes :
 * ne jamais les appeler depuis le thread de l'interface.
 */
abstract class DroneBase(val limits: Limits) {
    @Volatile var x = 0.0          // mètres, devant au décollage
        protected set
    @Volatile var y = 0.0          // mètres, à droite au décollage
        protected set
    @Volatile var z = 0.0          // altitude estimée
        protected set
    @Volatile var heading = 0.0    // degrés par rapport au décollage
        protected set
    @Volatile var cameraTilt = 0
        protected set

    open var flying: Boolean = false

    /** En intérieur : pas courts, lents, jamais plus haut que le plafond estimé (réglé par l'application). */
    @Volatile var indoor = false

    /** Manœuvre automatique en cours (repérage, porte) : la tête du pilote ne commande plus la caméra ni le cap. */
    @Volatile var headPaused = false

    /** Place libre autour du drone (repérage) : les déplacements qui iraient dans un mur sont raccourcis ou refusés. */
    @Volatile var space: SpaceGuard? = null

    companion object {
        const val INDOOR_STEP = 0.3        // pas max des actions de l'IA en intérieur (m)
        const val INDOOR_LONG_STEP = 1.0   // pas max des manœuvres guidées (passage de porte…) en intérieur
        const val INDOOR_MAX_ALT = 2.0     // altitude max en intérieur tant que le plafond n'est pas estimé
        const val CEILING_MARGIN = 0.5     // marge sous le plafond (m)
    }

    /** Vrai pendant un déplacement vers l'avant (le détecteur d'obstacles surveille alors la vidéo). */
    @Volatile var movingForward = false
        protected set
    @Volatile private var cancelReason: String? = null
    @Volatile private var obstacleUntil = 0L

    abstract val name: String
    abstract fun connect()
    abstract fun disconnect()
    open fun battery(): Int? = null
    open fun altitude(): Double = z

    protected abstract fun doTakeoff()
    protected abstract fun doLand()
    protected abstract fun doEmergency()
    protected abstract fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double)
    protected abstract fun doTilt(degrees: Int)
    protected open fun doCancelMove() {}

    /** Renvoie le plafond et la distance max au drone après un changement de réglage. */
    open fun applyLimits() {}

    /**
     * Orientation de la tête du pilote (casque), environ 10 fois par seconde.
     * pitch : vers le haut > 0 ; yaw : vers la droite > 0 (degrés, par rapport au recentrage).
     * follow : le drone tourne pour suivre la tête ; recenter : la direction actuelle devient « devant ».
     */
    open fun headLook(pitch: Double, yaw: Double, follow: Boolean, recenter: Boolean) {}

    /** Le pilote a repris la main avec sa radiocommande : l'application ne commande plus les déplacements. */
    open val manualControl: Boolean get() = false

    /** Donne (true) ou rend (false) les commandes au pilote. */
    open fun setManual(on: Boolean) {}

    /**
     * Télémétrie pour le cockpit du casque. Par défaut : estimation interne (odométrie).
     * heading : cap (°) ; homeDist / homeBearing : distance (m) et cap absolu (°) du point de départ.
     */
    open fun telemetry(): Map<String, Any?> {
        val toHome = Math.toDegrees(kotlin.math.atan2(-y, -x))
        return mapOf(
            "heading" to heading,
            "alt" to altitude(),
            "homeDist" to distanceFromHome(),
            "homeBearing" to toHome,
            "homeSource" to "estimation",
            "gimbal" to cameraTilt.toDouble(),
            "travelHeading" to travelHeading(),
            "battery" to battery(),
            "flying" to flying,
        )
    }
    protected open fun pause(seconds: Double) = Thread.sleep((seconds * 1000).toLong())

    // ---- position absolue, regard du pilote, vitesses continues -----------------------------------
    /** Cap boussole au décollage (pour passer du repère du décollage au repère nord/est). */
    @Volatile protected var takeoffCompass = 0.0

    /** Cap absolu (°, 0 = nord si le drone a une boussole ; sinon par rapport au décollage). */
    open fun compass(): Double = norm180(heading + takeoffCompass)

    /** Position (nord, est) en mètres par rapport au point de décollage. GPS si disponible, sinon estimation. */
    open fun positionNE(): Pair<Double, Double> {
        val c = Math.toRadians(takeoffCompass)
        return (x * cos(c) - y * sin(c)) to (x * sin(c) + y * cos(c))
    }

    /** Vrai si [positionNE] vient du GPS (précise à quelques mètres, sans dérive). */
    open val hasGps: Boolean get() = false

    /** Position GPS (lat, lon) du drone et du point de départ, si disponibles. */
    open fun gps(): Pair<Double, Double>? = null
    open fun homeGps(): Pair<Double, Double>? = null

    @Volatile private var headPitch = 0.0
    @Volatile private var headYaw = 0.0
    @Volatile private var headAt = 0L

    /** Dernière orientation de la tête du pilote (casque), notée par l'application. */
    fun noteHead(pitch: Double, yaw: Double) {
        headPitch = pitch
        headYaw = yaw
        headAt = System.currentTimeMillis()
    }
    protected val headRecent: Boolean get() = System.currentTimeMillis() - headAt < 1500
    protected val notedHeadYaw: Double get() = headYaw

    /** Inclinaison actuelle de la caméra (°, négatif = vers le bas). */
    open fun gimbalPitch(): Double = if (headRecent) headPitch.coerceIn(-80.0, 15.0) else cameraTilt.toDouble()

    /** Direction (cap absolu) où regarde la caméra, donc le pilote quand il a le casque. */
    open fun lookHeading(): Double = compass()

    /**
     * Cap absolu de la vue au moment de l'ordre (« avance », « à droite » = par rapport à ce que le pilote voyait alors).
     * Le déplacement garde cette direction même si le pilote tourne la tête pendant qu'il s'exécute. null = vue actuelle.
     */
    @Volatile var moveRef: Double? = null

    /** Le drone applique lui-même la direction de la vue au moment de l'ordre (DJI) ; sinon c'est fait ici. */
    protected open val handlesViewFrame: Boolean get() = false

    /** Cap absolu du « devant » du déplacement en cours : la vue au moment de l'ordre, sinon la vue actuelle. */
    open fun bodyCompass(): Double = moveRef ?: lookHeading()

    /** Écart entre la caméra maintenant et le « devant » du déplacement (°, positif = la caméra regarde plus à droite). */
    fun cameraOffset(): Double = norm180(lookHeading() - bodyCompass())

    /** Cap du « devant » du déplacement, dans le repère de la position estimée. */
    protected open fun moveFrameHeading(): Double = norm180(heading + (moveRef?.let { norm180(it - compass()) } ?: 0.0))

    /** Le drone sait-il tenir une vitesse continue (suivi, plans de cinéma) ? Sinon : petits déplacements. */
    open val supportsVelocity: Boolean get() = false

    /**
     * Vitesse continue (m/s et °/s), tenue pendant [holdMs] puis arrêt automatique si rien ne la renouvelle.
     * forward > 0 avancer, right > 0 à droite, up > 0 monter, yawRate > 0 tourner à droite.
     */
    open fun velocity(forward: Double, right: Double, up: Double, yawRate: Double, holdMs: Long = 600) {}

    /** Arrête toute vitesse continue. */
    open fun stopVelocity() {}

    /** Cap de vol aux manettes quand il diffère de la caméra (le pilote tourne la tête), sinon null. */
    open fun travelHeading(): Double? = null

    /** Le drone se pilote aux manettes du casque (vitesses continues). */
    open val supportsSticks: Boolean get() = supportsVelocity

    /**
     * Manettes du casque : vitesses voulues (m/s, °/s, repère de la vue), renouvelées ~20 fois par seconde.
     * Si plus rien n'arrive pendant 0,3 s, le drone s'arrête et reste sur place.
     */
    open fun sticks(forward: Double, right: Double, up: Double, yawRate: Double) {
        if (supportsVelocity) velocity(forward, right, up, yawRate, holdMs = 300)
    }

    /** Avance bloquée (obstacle vu il y a moins de 5 s). */
    fun forwardBlocked(): Boolean = System.currentTimeMillis() < obstacleUntil

    /** Pour les vitesses continues : le détecteur d'obstacles surveille quand on avance. */
    protected fun setForward(on: Boolean) { movingForward = on }

    /** Met à jour la position estimée pendant une vitesse continue (repère du drone). */
    protected fun integrate(forward: Double, right: Double, up: Double, yawDeg: Double, dtS: Double) {
        val h = Math.toRadians(heading)
        x += (forward * cos(h) - right * sin(h)) * dtS
        y += (forward * sin(h) + right * cos(h)) * dtS
        z = (z + up * dtS).coerceAtLeast(0.0)
        heading = norm180(heading + yawDeg * dtS)
    }

    /** Démarre / arrête l'enregistrement vidéo sur le drone. null = réussi, sinon la raison. */
    open fun startRecording(): String? = "enregistrement vidéo non disponible sur ce drone"
    open fun stopRecording(): String? = null
    open val recording: Boolean get() = false

    /** Qualité de la liaison radio (0 à 100), si le drone la donne. */
    open fun signalQuality(): Int? = null


    fun takeoff(): String {
        if (flying) return "déjà en vol"
        doTakeoff()
        flying = true
        x = 0.0; y = 0.0; heading = 0.0
        z = 1.0 // le Bebop se stabilise vers 1 m
        takeoffCompass = takeoffHeading()
        return "décollage effectué"
    }

    fun land(): String {
        if (!flying) return "déjà au sol"
        doLand()
        flying = false
        z = 0.0
        return "atterrissage effectué"
    }

    /** Recale le cap estimé sur la boussole, si le drone en a une (le casque a pu le faire tourner). */
    protected open fun syncHeading() {}

    protected fun setHeadingFromCompass(c: Double) { heading = norm180(c - takeoffCompass) }

    /** Cap boussole au moment du décollage (0 sans boussole). */
    protected open fun takeoffHeading(): Double = 0.0

    /** Coupe les moteurs : le drone TOMBE. Dernier recours. */
    fun emergency(): String {
        doEmergency()
        flying = false
        z = 0.0
        return "arrêt d'urgence"
    }

    fun cancelMove() {
        cancelReason = "interrompu"
        doCancelMove()
    }

    /** Obstacle vu alors que le drone n'avance pas : on bloque seulement l'avance quelques secondes. */
    fun obstacleAhead() {
        obstacleUntil = System.currentTimeMillis() + 5000
    }

    /** Appelé par le détecteur d'obstacles : arrête le déplacement et bloque l'avance quelques secondes. */
    fun stopForObstacle() {
        cancelReason = "interrompu : obstacle détecté devant"
        obstacleUntil = System.currentTimeMillis() + 5000
        doCancelMove()
    }

    fun hover(seconds: Double): String {
        val s = seconds.coerceIn(0.5, 5.0)
        pause(s)
        return "stationnaire %.1f s".format(s)
    }

    fun tiltCamera(degrees: Double): String {
        val d = degrees.coerceIn(-80.0, 15.0).roundToInt()
        doTilt(d)
        cameraTilt = d
        return "caméra inclinée à $d°"
    }

    /** [maxStep] : plus grand pendant une mission de recherche (segments de quelques mètres). */
    fun move(forward: Double, right: Double, up: Double, yaw: Double, maxStep: Double = limits.maxStepM): String {
        if (!flying) return "refusé : le drone est au sol (décolle d'abord)"
        syncHeading()
        val l = limits
        // En intérieur : pas courts (les manœuvres guidées ont droit à un peu plus)
        val step = if (indoor) minOf(maxStep, if (maxStep <= l.maxStepM) INDOOR_STEP else INDOOR_LONG_STEP) else maxStep
        var f = forward.coerceIn(-step, step)
        var r = right.coerceIn(-step, step)
        var u = up.coerceIn(-step, step)
        val yw = yaw.coerceIn(-l.maxYawDeg, l.maxYawDeg)
        var note = ""
        // Place libre d'après le repérage : on ne va pas dans un mur
        val horiz = hypot(f, r)
        if (horiz > 0.05) {
            val bearing = bodyCompass() + Math.toDegrees(kotlin.math.atan2(r, f))
            space?.allowed(bearing, positionNE())?.let { free ->
                if (free < 0.1) return "refusé : pas de place dans cette direction (mur ou obstacle à moins de %.1f m d'après le repérage). Tourne-toi ou repère les lieux".format(free.coerceAtLeast(0.0) + 0.5)
                if (horiz > free) {
                    f *= free / horiz; r *= free / horiz
                    note = " (raccourci à %.1f m : mur ou obstacle d'après le repérage)".format(free)
                }
            }
        }
        // Plafond : en intérieur, jamais à moins de 50 cm du plafond estimé (2 m max s'il est inconnu)
        if (u > 0) {
            val top = if (indoor) (space?.ceilingAlt()?.minus(CEILING_MARGIN) ?: INDOOR_MAX_ALT) else Double.MAX_VALUE
            val room = top - altitude()
            if (room < 0.05) return "refusé : trop près du plafond (altitude %.1f m, plafond estimé à %.1f m)".format(altitude(), top + CEILING_MARGIN)
            if (u > room) { u = room; note += " (montée limitée par le plafond)" }
        }

        if (f > 0.05 && System.currentTimeMillis() < obstacleUntil) {
            return "refusé : obstacle détecté devant il y a quelques secondes. Recule, tourne ou monte avant d'avancer"
        }

        val newZ = (z + u).coerceIn(l.minAltitudeM, l.maxAltitudeM)
        val du = newZ - z

        val h = Math.toRadians(moveFrameHeading())
        val nx = x + f * cos(h) - r * sin(h)
        val ny = y + f * sin(h) + r * cos(h)
        if (hypot(nx, ny) > l.maxDistanceM) {
            return "refusé : sortirait du rayon de %.0f m (distance actuelle %.1f m)".format(l.maxDistanceM, hypot(x, y))
        }
        val done = moveTracked(f, r, du, yw, nx, ny, newZ)
        if (done < 1.0) {
            return "%s (fait environ %.0f %% du déplacement)".format(cancelReason ?: "interrompu", done * 100)
        }
        return "déplacé (avant %+.1f m, droite %+.1f m, haut %+.1f m, rotation %+.0f°)".format(f, r, du, yw) + note
    }

    /** Revient au-dessus du point de décollage (estimation, pas GPS). */
    fun returnHome(): String {
        if (!flying) return "refusé : le drone est au sol"
        syncHeading()
        val h = Math.toRadians(moveFrameHeading())
        val dx = -x
        val dy = -y
        val f = dx * cos(h) + dy * sin(h)
        val r = -dx * sin(h) + dy * cos(h)
        if (hypot(f, r) < 0.2) return "déjà au point de départ"
        val done = moveTracked(f, r, 0.0, 0.0, 0.0, 0.0, z)
        if (done < 1.0) return "retour au départ ${cancelReason ?: "interrompu"}"
        return "retour au départ"
    }

    /** Renvoie la part du déplacement effectuée (1 = en entier, moins s'il a été interrompu). */
    private fun moveTracked(f: Double, r: Double, u: Double, yaw: Double, nx: Double, ny: Double, nz: Double): Double {
        val dist = sqrt(f * f + r * r + u * u)
        val waitS = 1.0 + dist / 0.6 + abs(yaw) / 40.0
        cancelReason = null
        // Le détecteur d'obstacles ne surveille que si la caméra regarde dans la direction du déplacement.
        val motion = Math.toDegrees(kotlin.math.atan2(r, f))
        movingForward = hypot(f, r) > 0.05 && abs(norm180(motion - cameraOffset())) < 35
        val t0 = System.currentTimeMillis()
        // Drone qui ne gère pas lui-même la vue de l'ordre (Bebop : caméra orientée par la tête) : on tourne le vecteur.
        var bf = f
        var br = r
        val ref = moveRef
        if (!handlesViewFrame && ref != null) {
            val dv = Math.toRadians(norm180(ref - compass()))
            bf = f * cos(dv) - r * sin(dv)
            br = f * sin(dv) + r * cos(dv)
        }
        try {
            doMove(bf, br, -u, Math.toRadians(yaw), waitS)
        } finally {
            movingForward = false
        }
        // Interrompu : position estimée au prorata du temps écoulé (estimation grossière).
        val done = if (cancelReason == null) 1.0
        else ((System.currentTimeMillis() - t0) / 1000.0 / (waitS - 0.5)).coerceIn(0.0, 1.0)
        x += (nx - x) * done; y += (ny - y) * done; z += (nz - z) * done
        heading = (((heading + yaw * done + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
        return if (cancelReason == null) 1.0 else minOf(done, 0.99)
    }

    fun distanceFromHome(): Double = if (hasGps) positionNE().let { hypot(it.first, it.second) } else hypot(x, y)

    fun state(): Map<String, Any?> = mapOf(
        "en_vol" to flying,
        "altitude_m" to (altitude() * 100).roundToInt() / 100.0,
        "batterie_pct" to battery(),
        "position_estimee_m" to mapOf(
            "x_devant" to (x * 10).roundToInt() / 10.0,
            "y_droite" to (y * 10).roundToInt() / 10.0,
        ),
        "distance_depart_m" to (distanceFromHome() * 10).roundToInt() / 10.0,
        "cap_deg" to heading.roundToInt(),
        "inclinaison_camera_deg" to cameraTilt,
        "pas_max_m" to limits.maxStepM,
    )
}

fun norm180(a: Double): Double = (((a + 180.0) % 360.0) + 360.0) % 360.0 - 180.0

/** Drone fictif : rien ne vole, les commandes sont seulement journalisées. */
class SimDrone(limits: Limits, private val log: (String) -> Unit = {}) : DroneBase(limits) {
    override val name = "Simulation"
    @Volatile private var batteryLevel = 100.0

    override fun connect() = log("[SIM] drone simulé prêt")
    override fun disconnect() = log("[SIM] déconnecté")
    override fun battery(): Int = batteryLevel.toInt()

    private fun drain(amount: Double) {
        batteryLevel = (batteryLevel - amount).coerceAtLeast(0.0)
    }

    override fun doTakeoff() { log("[SIM] décollage"); drain(1.0); Thread.sleep(1000) }
    override fun doLand() { log("[SIM] atterrissage"); Thread.sleep(1000) }
    override fun doEmergency() = log("[SIM] ARRÊT D'URGENCE")
    @Volatile private var moving = java.util.concurrent.CountDownLatch(0)

    override fun doCancelMove() {
        log("[SIM] annulation du déplacement")
        moving.countDown()
    }

    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {
        log("[SIM] moveBy(dx=%.2f, dy=%.2f, dz=%.2f, dpsi=%.2f rad)".format(forward, right, down, yawRad))
        drain(0.5)
        // Dure comme un vrai déplacement (on peut tester le détecteur en avançant le téléphone vers un mur).
        val latch = java.util.concurrent.CountDownLatch(1)
        moving = latch
        latch.await((minOf(waitS, 4.0) * 1000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    override fun doTilt(degrees: Int) = log("[SIM] caméra -> $degrees°")
    override fun pause(seconds: Double) = Thread.sleep((minOf(seconds, 1.0) * 1000).toLong())
}
