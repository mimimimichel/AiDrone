package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Suivi d'une cible (« suis le chien », « suis-moi ») : le modèle de vision repère la cible sur chaque image,
 * le drone la garde au centre (rotation) et à la même taille apparente (avance / recule).
 *
 * Drone qui tient une vitesse continue (DJI) : commandes en vitesse, renouvelées à chaque image analysée.
 * Sinon : petits déplacements. Le rayon, le détecteur d'obstacles, la batterie et « stop » restent prioritaires.
 */
class Follower(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val detector: ObjectDetector,
    private val hfovDeg: Double,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    /** La cible a été vue : repère sur la carte. */
    private val onSeen: (String) -> Unit = {},
) {
    companion object {
        const val MAX_SPEED = 3.0      // m/s, dehors
        const val INDOOR_SPEED = 0.6   // m/s, sans GPS (en intérieur)
        const val MAX_MINUTES = 10
    }

    @Volatile var target: String? = null
        private set

    fun follow(targetName: String, minutes: Double?): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val t = targetName.trim().ifEmpty { return "refusé : cible non précisée" }
        target = t
        report("🎯 Je suis « $t » (dis « stop » pour arrêter)")
        val end = System.currentTimeMillis() + ((minutes ?: MAX_MINUTES.toDouble()).coerceIn(0.5, MAX_MINUTES.toDouble()) * 60_000).toLong()
        var refH: Double? = null
        var lost = 0
        var seen = 0
        var lastSide = 1.0
        try {
            while (System.currentTimeMillis() < end) {
                if (shouldStop()) return "suivi arrêté par le pilote (vu $seen fois)"
                if (!drone.flying) return "suivi arrêté : le drone n'est plus en vol"
                drone.battery()?.let { b -> if (b < drone.limits.minBattery + 10 && !simulation) return "suivi arrêté : batterie à $b %" }
                val frame = eyes.captureJpegBase64()
                if (frame == null) {
                    drone.stopVelocity()
                    Thread.sleep(300)
                    if (++lost > 20) return "suivi arrêté : plus d'image de la caméra"
                    continue
                }
                val d = try {
                    detector.find(frame, t)
                } catch (e: Exception) {
                    drone.stopVelocity()
                    return "suivi arrêté : analyse d'image impossible (${e.message})"
                }
                if (shouldStop()) return "suivi arrêté par le pilote (vu $seen fois)"
                if (!d.found || d.confidence < 0.35) {
                    lost++
                    drone.stopVelocity()
                    if (lost == 2) report("🎯 Je ne vois plus « $t » : je cherche")
                    if (lost >= 3) {
                        // On tourne du côté où elle est partie.
                        if (lost > 14) return "suivi terminé : « $t » perdu de vue (vu $seen fois)"
                        turn(35.0 * lastSide)
                    }
                    continue
                }
                if (lost >= 2) report("🎯 « $t » retrouvé")
                lost = 0
                seen++
                if (seen == 1 || seen % 15 == 0) onSeen(t)
                val size = maxOf(d.h, d.w * 0.8)
                val ref = refH ?: size.coerceIn(0.12, 0.5).also { refH = it }
                val offDeg = (d.cx - 0.5) * hfovDeg
                lastSide = if (offDeg >= 0) 1.0 else -1.0
                // Trop loin -> avance ; trop près -> recule. Zone morte de 15 %.
                val err = (ref - size) / ref
                val outside = drone.distanceFromHome() > drone.limits.maxDistanceM - 2
                if (drone.supportsVelocity) {
                    val vmax = if (drone.hasGps) MAX_SPEED else INDOOR_SPEED
                    var f = if (abs(err) < 0.15) 0.0 else (err * vmax * 1.5).coerceIn(-vmax * 0.6, vmax)
                    if (outside && f > 0) {
                        f = 0.0
                        if (seen % 5 == 1) report("🎯 Limite du rayon : je ne la suis plus plus loin, je la garde en vue")
                    }
                    val yaw = (offDeg / 1.2).coerceIn(-45.0, 45.0)
                    drone.velocity(f, 0.0, 0.0, yaw, holdMs = 1800)
                } else {
                    if (abs(offDeg) > 8) turn(offDeg)
                    val step = when {
                        abs(err) < 0.2 -> 0.0
                        err > 0 -> minOf(drone.limits.maxStepM * 2, 2.0)
                        else -> -minOf(drone.limits.maxStepM, 1.0)
                    }
                    if (step != 0.0 && !(outside && step > 0)) {
                        if (simulation) report("(Simulation) ${if (step > 0) "Avance" else "Recule"} le téléphone de ${(abs(step) * 100).roundToInt()} cm")
                        val r = drone.move(step, 0.0, 0.0, 0.0, maxStep = 2.0)
                        if (r.startsWith("interrompu")) return "suivi arrêté : $r"
                    }
                }
                // Cible trop haut / trop bas dans l'image : on incline la caméra.
                if (d.cy > 0.72 && drone.cameraTilt > -80) drone.tiltCamera(drone.cameraTilt - 8.0)
                else if (d.cy < 0.28 && drone.cameraTilt < 0) drone.tiltCamera(drone.cameraTilt + 8.0)
            }
            return "suivi terminé (durée maximale atteinte), « $t » vu $seen fois"
        } finally {
            drone.stopVelocity()
            target = null
        }
    }

    private fun turn(deg: Double) {
        if (simulation) report("(Simulation) Tourne le téléphone de ${abs(deg).roundToInt()}° vers la ${if (deg > 0) "droite" else "gauche"}")
        if (drone.supportsVelocity) {
            drone.velocity(0.0, 0.0, 0.0, (deg / 1.0).coerceIn(-45.0, 45.0), holdMs = 1000)
            Thread.sleep(1000)
        } else {
            drone.move(0.0, 0.0, 0.0, deg)
        }
    }
}
