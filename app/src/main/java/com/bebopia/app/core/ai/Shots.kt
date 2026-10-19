package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Plans de cinéma automatiques, filmés sur la carte du drone :
 * orbite autour de ce qui est au centre de l'image, dronie (recule en montant), panorama,
 * révélation (la caméra se relève en avançant), fusée (monte à la verticale caméra vers le sol).
 */
class Shots(
    private val drone: DroneBase,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    /** Vérification de l'avancée par l'image (distance permise, ou refus). */
    private val pathCheck: () -> ((Double) -> Pair<Double, String?>)?,
) {
    private class Abort(msg: String) : Exception(msg)

    val kinds = listOf("orbite", "dronie", "panorama", "revelation", "fusee")

    fun film(kindName: String, radiusReq: Double?, record: Boolean): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val kind = kindName.lowercase().replace("é", "e").let { k -> kinds.firstOrNull { k.contains(it) } }
            ?: return "refusé : plan inconnu « $kindName » (possibles : ${kinds.joinToString()})"
        var recNote = ""
        val startedRec = record && !drone.recording
        if (startedRec) {
            val err = drone.startRecording()
            recNote = if (err == null) " (filmé sur la carte du drone)" else " (pas d'enregistrement : $err)"
            if (err == null) Thread.sleep(800)
        }
        report("🎬 Plan « $kind »$recNote")
        return try {
            val r = when (kind) {
                "orbite" -> orbit(radiusReq)
                "dronie" -> dronie(radiusReq)
                "panorama" -> panorama()
                "revelation" -> reveal(radiusReq)
                else -> rocket(radiusReq)
            }
            "$r$recNote"
        } catch (e: Abort) {
            (e.message ?: "plan interrompu") + recNote
        } finally {
            drone.stopVelocity()
            if (startedRec && drone.recording) {
                Thread.sleep(500)
                drone.stopRecording()
            }
        }
    }

    private fun checkStop() {
        if (shouldStop()) throw Abort("plan arrêté par le pilote")
        if (!drone.flying) throw Abort("plan arrêté : le drone n'est plus en vol")
    }

    /** Tient une vitesse pendant [seconds] (renouvelée toutes les 0,3 s), en surveillant stop / rayon. */
    private fun hold(f: Double, r: Double, u: Double, yaw: Double, seconds: Double, tilt: ((Double) -> Double?)? = null) {
        val t0 = System.currentTimeMillis()
        var lastTilt = Double.NaN
        while (true) {
            checkStop()
            val el = (System.currentTimeMillis() - t0) / 1000.0
            if (el >= seconds) break
            if (drone.distanceFromHome() > drone.limits.maxDistanceM - 1 && (f != 0.0 || r != 0.0)) throw Abort("plan arrêté : limite du rayon atteinte")
            if (u > 0 && drone.altitude() > drone.limits.maxAltitudeM - 0.3) throw Abort("plan arrêté : plafond atteint")
            drone.velocity(f, r, u, yaw, holdMs = 700)
            tilt?.invoke(el / seconds)?.let { t -> if (abs(t - lastTilt) >= 3) { drone.tiltCamera(t); lastTilt = t } }
            Thread.sleep(300)
        }
        drone.stopVelocity()
        Thread.sleep(800)
    }

    private fun step(f: Double, r: Double, u: Double, yaw: Double) {
        checkStop()
        if (simulation) report("(Simulation) Déplace le téléphone (avant %+.1f m, droite %+.1f m, haut %+.1f m, rotation %+.0f°)".format(f, r, u, yaw))
        val res = drone.move(f, r, u, yaw, maxStep = drone.limits.missionStepM)
        if (res.startsWith("refusé") || res.startsWith("interrompu")) throw Abort("plan arrêté : $res")
    }

    /** Distance au sol du centre de l'image (d'après l'altitude et l'inclinaison de la caméra). */
    private fun centerDistance(): Double? {
        val below = -drone.gimbalPitch()
        if (below < 6) return null
        return drone.altitude() / tan(Math.toRadians(below))
    }

    private fun orbit(radiusReq: Double?): String {
        val rad = (radiusReq ?: centerDistance() ?: 5.0).coerceIn(1.5, 40.0)
        if (drone.distanceFromHome() + 2 * rad > drone.limits.maxDistanceM + 1) {
            throw Abort("refusé : une orbite de %.0f m de rayon sortirait du rayon autorisé".format(rad))
        }
        report("🎬 Orbite de %.0f m autour du centre de l'image".format(rad))
        if (drone.supportsVelocity) {
            val v = (rad * 0.25).coerceIn(0.4, if (drone.hasGps) 2.5 else 0.6)
            val yawRate = -Math.toDegrees(v / rad) // le sujet reste au centre
            hold(0.0, v, 0.0, yawRate, 2 * PI * rad / v)
        } else {
            repeat(12) { step(0.0, 2 * rad * sin(Math.toRadians(15.0)), 0.0, -30.0) }
        }
        return "orbite complète de %.0f m de rayon".format(rad)
    }

    private fun dronie(distReq: Double?): String {
        val dist = (distReq ?: if (drone.hasGps) 25.0 else 2.0).coerceIn(1.0, 60.0)
        val climb = (dist * 0.5).coerceAtMost(drone.limits.maxAltitudeM - drone.altitude() - 0.3).coerceAtLeast(0.0)
        val subject = centerDistance() ?: 3.0
        val alt0 = drone.altitude()
        // La caméra descend pour garder le sujet au centre pendant qu'on s'éloigne.
        val tiltAt = { k: Double -> -Math.toDegrees(atan2(alt0 + climb * k, subject + dist * k)) }
        report("🎬 Dronie : je recule de %.0f m en montant de %.0f m".format(dist, climb))
        if (drone.supportsVelocity) {
            val v = if (drone.hasGps) 2.0 else 0.4
            val t = dist / v
            hold(-v, 0.0, climb / t, 0.0, t, tilt = tiltAt)
        } else {
            val n = maxOf(1, (dist / 1.0).roundToInt().coerceAtMost(10))
            for (i in 1..n) {
                step(-dist / n, 0.0, climb / n, 0.0)
                drone.tiltCamera(tiltAt(i.toDouble() / n))
            }
        }
        return "dronie terminé : %.0f m en arrière, %.0f m plus haut".format(dist, climb)
    }

    private fun panorama(): String {
        report("🎬 Panorama : un tour complet, lentement")
        if (drone.supportsVelocity) hold(0.0, 0.0, 0.0, 15.0, 24.0)
        else repeat(8) { step(0.0, 0.0, 0.0, 45.0); Thread.sleep(1200) }
        return "panorama à 360° terminé"
    }

    private fun reveal(distReq: Double?): String {
        val dist = (distReq ?: if (drone.hasGps) 10.0 else 1.0).coerceIn(0.5, 30.0)
        var go = dist
        pathCheck()?.let { check ->
            drone.tiltCamera(0.0)
            Thread.sleep(800)
            val (ok, refusal) = check(dist)
            if (refusal != null) throw Abort("révélation impossible : $refusal")
            go = ok
        }
        drone.tiltCamera(-80.0)
        Thread.sleep(1500)
        report("🎬 Révélation : la caméra se relève pendant que j'avance de %.0f m".format(go))
        if (drone.supportsVelocity) {
            val v = if (drone.hasGps) 1.2 else 0.3
            hold(v, 0.0, 0.0, 0.0, go / v, tilt = { k -> -80.0 + 80.0 * k })
        } else {
            val n = 6
            for (i in 1..n) {
                step(go / n, 0.0, 0.0, 0.0)
                drone.tiltCamera(-80.0 + 80.0 * i / n)
            }
        }
        drone.tiltCamera(0.0)
        return "révélation terminée"
    }

    private fun rocket(heightReq: Double?): String {
        val up = (heightReq ?: 15.0).coerceAtMost(drone.limits.maxAltitudeM - drone.altitude() - 0.3)
        if (up < 1) throw Abort("refusé : déjà près du plafond (%.0f m)".format(drone.limits.maxAltitudeM))
        drone.tiltCamera(-90.0)
        Thread.sleep(1200)
        report("🎬 Fusée : je monte de %.0f m caméra vers le sol".format(up))
        if (drone.supportsVelocity) hold(0.0, 0.0, 1.5, 0.0, up / 1.5)
        else {
            val n = maxOf(1, (up / 1.0).roundToInt().coerceAtMost(15))
            repeat(n) { step(0.0, 0.0, up / n, 0.0) }
        }
        Thread.sleep(1500)
        drone.tiltCamera(-30.0)
        return "fusée terminée : %.0f m plus haut".format(up)
    }
}
