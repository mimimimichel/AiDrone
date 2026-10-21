package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.norm180
import com.bebopia.app.core.vision.Box
import com.bebopia.app.core.vision.Marks
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Passage d'une porte, en douceur : se tourner vers elle, se mettre à mi-hauteur, vérifier qu'elle est assez large,
 * s'en approcher par petits pas en se recentrant sur elle à chaque pas, puis la franchir tout droit, lentement.
 */
class DoorPasser(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val detector: ObjectDetector,
    private val memory: SceneMemory,
    private val hfovDeg: Double,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
) {
    companion object {
        const val MIN_WIDTH = 0.6      // ouverture minimale (m) : drone + cage + marge
        const val DOOR_WIDTH = 0.8     // largeur d'une porte, si on ne la connaît pas
        const val PASS_ALT = 1.1       // hauteur de passage (une porte fait environ 2 m)
    }

    private class Stop(msg: String) : Exception(msg)

    fun pass(desc: String, known: SceneItem? = null): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val wasPaused = drone.headPaused
        drone.headPaused = true
        // La porte est dans un mur : le garde-fou de place libre (repérage) verrait ce mur et bloquerait l'approche.
        // Pendant le passage, c'est la porte elle-même qui guide (vue et recentrée à chaque pas), avec le détecteur d'obstacles.
        val guard = drone.space
        drone.space = null
        return try {
            run(desc.ifBlank { known?.label ?: "porte" }, known)
        } catch (e: Stop) {
            e.message ?: "passage interrompu"
        } finally {
            drone.space = guard
            drone.headPaused = wasPaused
            Marks.clear("door")
        }
    }

    private fun checkStop() {
        if (shouldStop()) throw Stop("passage arrêté par le pilote (le drone reste sur place)")
        if (!drone.flying) throw Stop("passage arrêté : le drone n'est plus en vol")
    }

    private fun mv(f: Double = 0.0, r: Double = 0.0, u: Double = 0.0, yaw: Double = 0.0, long: Boolean = false): String {
        checkStop()
        if (simulation && (abs(f) > 0.01 || abs(yaw) > 1)) {
            report("(Simulation) " + if (abs(yaw) > 1) "Tourne le téléphone de ${abs(yaw).roundToInt()}° vers la ${if (yaw > 0) "droite" else "gauche"}"
            else "Avance le téléphone de ${(f * 100).roundToInt()} cm")
        }
        val res = drone.move(f, r, u, yaw, maxStep = if (long) drone.limits.missionStepM else drone.limits.maxStepM)
        if (res.startsWith("interrompu")) throw Stop("passage arrêté : $res")
        return res
    }

    private fun look(desc: String): Detection {
        checkStop()
        Thread.sleep(if (simulation) 400 else 800)
        val jpeg = eyes.captureJpegBase64() ?: throw Stop("passage impossible : pas d'image de la caméra")
        val d = try {
            detector.find(jpeg, "$desc (porte ou passage OUVERT par lequel on peut passer : encadre l'ouverture libre)")
        } catch (e: Exception) {
            throw Stop("passage impossible : analyse d'image impossible (${e.message})")
        }
        if (d.found) Marks.show("door", listOf(Marks.Mark(Box(d.cx, d.cy, d.w, d.h), desc, Marks.Kind.FOLLOWING)), 3000)
        return d
    }

    private fun run(desc: String, known: SceneItem?): String {
        drone.tiltCamera(0.0)
        // 1. Se tourner vers la porte (si on sait où elle est)
        if (known != null) {
            val p = drone.positionNE()
            val b = Math.toDegrees(atan2(known.e - p.second, known.n - p.first))
            var left = norm180(b - drone.compass())
            report("🚪 Je me tourne vers « ${known.label} »")
            while (abs(left) > 5) {
                val part = left.coerceIn(-drone.limits.maxYawDeg, drone.limits.maxYawDeg)
                mv(yaw = part)
                left -= part
            }
        }
        // 2. La trouver sur l'image (sinon regarder un peu à gauche et à droite)
        var d = look(desc)
        if (!d.found) {
            for (yaw in listOf(-30.0, 60.0, -90.0, 120.0)) {
                mv(yaw = yaw)
                d = look(desc)
                if (d.found) break
            }
        }
        if (!d.found) {
            known?.failed = true
            return "porte non trouvée : je ne vois pas « $desc » d'ici. Fais un repérage ou rapproche-moi"
        }
        report("🚪 Porte en vue : ${d.description.take(70)}")
        // 3. Hauteur de passage : à mi-hauteur de la porte
        val dz = PASS_ALT - drone.altitude()
        if (abs(dz) > 0.25) mv(u = dz)
        val widthM = (known?.widthM ?: DOOR_WIDTH)
        if (widthM < MIN_WIDTH) {
            known?.failed = true
            return "refusé : ouverture trop étroite (~%.1f m) pour passer sans toucher".format(widthM)
        }
        // 4. Approche par petits pas, recentré sur la porte à chaque pas
        var lost = 0
        repeat(30) {
            val off = (d.cx - 0.5) * hfovDeg
            if (abs(off) > 4) mv(yaw = off)
            // Distance d'après la largeur apparente de l'ouverture
            val dist = widthM / (2 * tan(Math.toRadians(hfovDeg / 2)) * d.w.coerceAtLeast(0.02))
            if (dist <= 1.3 || d.w > 0.5) return through(desc, dist, known)
            val step = minOf(0.3, dist - 1.1).coerceAtLeast(0.15)
            val r = mv(f = step)
            if (r.startsWith("refusé")) {
                known?.failed = true
                return "approche de la porte arrêtée : $r"
            }
            val again = look(desc)
            if (!again.found) {
                if (++lost >= 3) { known?.failed = true; return "j'ai perdu la porte de vue en m'approchant" }
            } else { lost = 0; d = again }
        }
        return "approche trop longue : je m'arrête devant la porte"
    }

    /** Dernier mètre : recentrage fin, puis tout droit à travers l'ouverture, lentement. */
    private fun through(desc: String, dist: Double, known: SceneItem?): String {
        val d = look(desc)
        if (d.found) {
            val off = (d.cx - 0.5) * hfovDeg
            if (abs(off) > 3) mv(yaw = off)
            // Porte vue trop haut ou trop bas : on corrige la hauteur
            if (d.cy < 0.35) mv(u = 0.2) else if (d.cy > 0.75) mv(u = -0.2)
        }
        report("🚪 Je passe la porte, tout droit, doucement")
        var left = dist + 0.8
        while (left > 0.05) {
            val s = minOf(left, 0.5)
            val r = mv(f = s, long = true)
            if (r.startsWith("refusé")) return "passage arrêté au milieu : $r. Recule ou recentre-moi"
            left -= s
        }
        known?.traversed = true
        // Nouvelle pièce : l'ancienne place libre ne vaut plus
        memory.setFreeSpace(drone.positionNE(), emptyList())
        val p = drone.positionNE()
        memory.all().filter { (it.kind == "porte" || it.kind == "passage") && hypot(it.n - p.first, it.e - p.second) < 1.5 }.forEach { it.traversed = true }
        return "porte franchie : je suis de l'autre côté de « $desc »"
    }
}
