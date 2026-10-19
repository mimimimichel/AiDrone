package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ce que le modèle de vision a vu. Coordonnées de 0 à 1 (0,0 = en haut à gauche de l'image). */
class Detection(
    val found: Boolean,
    val confidence: Double,
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
    val description: String,
)

/** Ce qu'il y a sur la trajectoire juste devant le drone. */
class PathResult(val clear: Boolean, val nearestM: Double?, val description: String)

/** Vérifie, sur l'image, s'il y a un obstacle dans l'axe avant d'avancer de [forwardM] mètres. */
fun interface PathChecker { fun check(jpegBase64: String, forwardM: Double): PathResult }

/** Repère un objet sur une image JPEG (base64). Bloquant, peut lever une exception. */
fun interface ObjectDetector { fun find(jpegBase64: String, target: String): Detection }

/**
 * Recherche active d'un objet : le drone tourne sur lui-même par pas de 45°, une image nette est
 * analysée à chaque arrêt ; s'il ne trouve rien à hauteur, il recommence caméra inclinée vers le bas.
 * Une fois trouvé, il se tourne face à l'objet et, si demandé, s'en approche pas à pas.
 * Tous les déplacements passent par les limites du drone (et le détecteur d'obstacles).
 */
class ObjectFinder(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val detector: ObjectDetector,
    /** Champ de vision horizontal de la caméra (degrés). */
    private val hfovDeg: Double,
    /** En simulation, c'est le pilote qui tourne le téléphone : on le lui dit à chaque pas. */
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private class Abort(msg: String) : Exception(msg)

    fun search(target: String, approach: Boolean): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val t = target.trim().ifEmpty { return "refusé : objet à chercher non précisé" }
        return try {
            report("🔎 Je cherche « $t »")
            val startTilt = drone.cameraTilt.toDouble()
            val tilts = if (startTilt > -25) listOf(startTilt, -40.0) else listOf(startTilt)
            for ((pass, tilt) in tilts.withIndex()) {
                if (pass > 0) {
                    report("🔎 Rien à hauteur : je regarde plus bas")
                    drone.tiltCamera(tilt)
                    if (simulation) report("(Simulation) Incline le téléphone vers le bas")
                }
                for (i in 0 until 8) {
                    val d = look(t)
                    if (d.found && d.confidence >= 0.45) return found(t, d, approach)
                    report("🔎 ${i * 45}° : pas de « $t » (${d.description.take(80)})")
                    turn(45.0)
                }
            }
            if (drone.cameraTilt.toDouble() != startTilt) drone.tiltCamera(startTilt)
            "pas trouvé : j'ai fait le tour complet${if (tilts.size > 1) " (à hauteur puis vers le bas)" else ""} sans voir « $t »"
        } catch (e: Abort) {
            e.message ?: "recherche interrompue"
        }
    }

    private fun checkStop() {
        if (shouldStop()) throw Abort("recherche interrompue par le pilote")
        if (!drone.flying) throw Abort("recherche arrêtée : le drone n'est plus en vol")
    }

    /** Image nette (après stabilisation) analysée par le modèle de vision. */
    private fun look(target: String): Detection {
        checkStop()
        Thread.sleep(if (simulation) 400 else 900) // le drone se stabilise, la vidéo rattrape son retard
        var frame = eyes.captureJpegBase64()
        var tries = 0
        while (frame == null && tries < 5) {
            Thread.sleep(300)
            frame = eyes.captureJpegBase64()
            tries++
        }
        if (frame == null) throw Abort("recherche impossible : pas d'image de la caméra")
        checkStop()
        return try {
            detector.find(frame, target)
        } catch (e: Exception) {
            throw Abort("recherche impossible : ${e.message}")
        }
    }

    private fun turn(deg: Double) {
        checkStop()
        if (simulation) {
            val side = if (deg > 0) "droite" else "gauche"
            report("(Simulation) Tourne le téléphone de ${abs(deg).roundToInt()}° vers la $side")
        }
        val r = drone.move(0.0, 0.0, 0.0, deg)
        if (r.startsWith("refusé") || r.startsWith("interrompu")) throw Abort("recherche arrêtée : $r")
    }

    /** Écart (degrés) entre le centre de l'image et l'objet ; positif = à droite. */
    private fun offset(d: Detection) = (d.cx - 0.5) * hfovDeg

    private fun found(target: String, first: Detection, approach: Boolean): String {
        var d = first
        report("✅ Trouvé : ${d.description}")
        val off = offset(d)
        if (abs(off) > 6) {
            turn(off)
            val again = look(target)
            if (again.found) d = again
        }
        if (!approach) {
            return "trouvé : ${d.description}. Je suis tourné face à l'objet " +
                "(il occupe environ ${(d.h * 100).roundToInt()} % de la hauteur de l'image)"
        }
        val step = minOf(drone.limits.maxStepM, 0.5)
        repeat(10) {
            if (d.h > 0.45 || d.w > 0.4) return "arrivé près de « $target » : ${d.description}"
            checkStop()
            val r = drone.move(step, 0.0, 0.0, 0.0)
            if (r.startsWith("refusé") || r.startsWith("interrompu")) {
                return "trouvé mais approche arrêtée : $r. Objet : ${d.description}"
            }
            if (simulation) report("(Simulation) Avance le téléphone de ${(step * 100).roundToInt()} cm vers l'objet")
            val next = look(target)
            if (!next.found) return "approche : j'ai perdu « $target » de vue après avoir avancé"
            d = next
            val o = offset(d)
            if (abs(o) > 8) turn(o)
        }
        return "approche : toujours pas tout près de « $target » après 10 pas, je m'arrête là"
    }
}
