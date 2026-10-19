package com.bebopia.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Vrai pendant un déplacement vers l'avant (le détecteur d'obstacles surveille alors la vidéo). */
    @Volatile var movingForward = false
        private set
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
            "battery" to battery(),
            "flying" to flying,
        )
    }
    protected open fun pause(seconds: Double) = Thread.sleep((seconds * 1000).toLong())

    fun takeoff(): String {
        if (flying) return "déjà en vol"
        doTakeoff()
        flying = true
        x = 0.0; y = 0.0; heading = 0.0
        z = 1.0 // le Bebop se stabilise vers 1 m
        return "décollage effectué"
    }

    fun land(): String {
        if (!flying) return "déjà au sol"
        doLand()
        flying = false
        z = 0.0
        return "atterrissage effectué"
    }

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
        val l = limits
        val f = forward.coerceIn(-maxStep, maxStep)
        val r = right.coerceIn(-maxStep, maxStep)
        val u = up.coerceIn(-maxStep, maxStep)
        val yw = yaw.coerceIn(-l.maxYawDeg, l.maxYawDeg)

        if (f > 0.05 && System.currentTimeMillis() < obstacleUntil) {
            return "refusé : obstacle détecté devant il y a quelques secondes. Recule, tourne ou monte avant d'avancer"
        }

        val newZ = (z + u).coerceIn(l.minAltitudeM, l.maxAltitudeM)
        val du = newZ - z

        val h = Math.toRadians(heading)
        val nx = x + f * cos(h) - r * sin(h)
        val ny = y + f * sin(h) + r * cos(h)
        if (hypot(nx, ny) > l.maxDistanceM) {
            return "refusé : sortirait du rayon de %.0f m (distance actuelle %.1f m)".format(l.maxDistanceM, hypot(x, y))
        }
        val done = moveTracked(f, r, du, yw, nx, ny, newZ)
        if (done < 1.0) {
            return "%s (fait environ %.0f %% du déplacement)".format(cancelReason ?: "interrompu", done * 100)
        }
        return "déplacé (avant %+.1f m, droite %+.1f m, haut %+.1f m, rotation %+.0f°)".format(f, r, du, yw)
    }

    /** Revient au-dessus du point de décollage (estimation, pas GPS). */
    fun returnHome(): String {
        if (!flying) return "refusé : le drone est au sol"
        val h = Math.toRadians(heading)
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
        movingForward = f > 0.05
        val t0 = System.currentTimeMillis()
        try {
            doMove(f, r, -u, Math.toRadians(yaw), waitS)
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

    fun distanceFromHome(): Double = hypot(x, y)

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
