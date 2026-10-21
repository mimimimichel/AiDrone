package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.norm180
import com.bebopia.app.core.vision.DepthReading
import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.GrayFrames
import com.bebopia.app.core.vision.Parallax
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Résultat d'un repérage : la cible (cap absolu, distance) si elle a été vue, et la description pour l'IA. */
class SurveyResult(val targetBearing: Double?, val targetDistance: Double?, val targetDesc: String?, val text: String, val indoor: Boolean?)

/**
 * Repérage, comme le ferait quelqu'un avant de se déplacer : le drone tourne sur lui-même et regarde dans toutes
 * les directions (8 vues en intérieur, 6 dehors), puis sous lui. Chaque vue est analysée (portes, murs, meubles,
 * place libre, plafond, cible) et rangée dans la mémoire de la scène. Dehors, il peut d'abord monter pour voir plus loin ;
 * en intérieur jamais.
 */
class Surveyor(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val analyzer: SceneAnalyzer,
    private val memory: SceneMemory,
    private val hfovDeg: Double,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    /** Ce que les vues disent du lieu (intérieur ou non). */
    private val onIndoor: (Boolean) -> Unit = {},
    /** Vidéo en niveaux de gris pour mesurer les distances par parallaxe (null : seulement les estimations de l'IA). */
    private val frames: GrayFrames? = null,
    private val ranger: Ranger? = null,
) {
    private class Stop(msg: String) : Exception(msg)
    private val pool = Executors.newFixedThreadPool(3)

    /** Regarde sous le drone (caméra vers le sol) : hauteur libre, null si impossible. */
    fun lookDown(): Double? {
        if (!drone.flying) return null
        val wasPaused = drone.headPaused
        drone.headPaused = true
        val tilt0 = drone.cameraTilt.toDouble()
        return try {
            report("👇 Je regarde sous moi avant de descendre")
            drone.tiltCamera(-80.0)
            if (simulation) report("(Simulation) Pointe le téléphone vers le sol")
            val v = runCatching { analyzer.analyze(capture(), null, true, hfovDeg) }.getOrNull() ?: return null
            memory.setBelowAt(drone.positionNE(), v.belowM, (if (v.belowClear == false) "attention : " else "") + v.summary)
            if (v.belowClear == false) (v.belowM ?: 0.0).coerceAtMost(0.3) else v.belowM
        } catch (e: Stop) {
            null
        } finally {
            drone.headPaused = wasPaused
            runCatching { drone.tiltCamera(tilt0) }
        }
    }

    /**
     * [arcDeg] : 360 = tour complet ; moins = simple coup d'œil devant (3 vues sur l'arc, par ex. 120° = -60°, 0°, +60°).
     * [measure] : second passage 25 cm plus haut pour mesurer les distances (long : désactivé par défaut,
     * les distances sont mesurées juste avant d'avancer).
     */
    fun survey(target: String?, climbM: Double? = null, lookDown: Boolean = true, arcDeg: Double = 360.0, measure: Boolean = false): SurveyResult {
        if (!drone.flying) return SurveyResult(null, null, null, "refusé : le drone est au sol, décolle d'abord", null)
        val wasPaused = drone.headPaused
        drone.headPaused = true
        val tilt0 = drone.cameraTilt.toDouble()
        return try {
            run(target, climbM, lookDown, arcDeg.coerceIn(60.0, 360.0), measure)
        } catch (e: Stop) {
            SurveyResult(null, null, null, e.message ?: "repérage interrompu", null)
        } finally {
            drone.headPaused = wasPaused
            runCatching { drone.tiltCamera(tilt0) }
        }
    }

    private fun checkStop() {
        if (shouldStop()) throw Stop("repérage arrêté par le pilote")
        if (!drone.flying) throw Stop("repérage arrêté : le drone n'est plus en vol")
    }

    private fun capture(): String {
        Thread.sleep(if (simulation) 400 else 500) // image nette après la rotation
        var f = eyes.captureJpegBase64()
        var n = 0
        while (f == null && n++ < 5) { Thread.sleep(300); f = eyes.captureJpegBase64() }
        return f ?: throw Stop("repérage impossible : pas d'image de la caméra")
    }

    /** Image tout de suite (pendant une rotation continue : pas d'attente). */
    private fun captureNow(): String {
        var f = eyes.captureJpegBase64()
        var n = 0
        while (f == null && n++ < 5) { Thread.sleep(150); f = eyes.captureJpegBase64() }
        return f ?: throw Stop("repérage impossible : pas d'image de la caméra")
    }

    /**
     * Rotation continue et fluide (une dizaine de secondes pour un tour) avec les images prises au vol, au lieu de
     * s'arrêter à chaque vue : beaucoup plus court, donc beaucoup moins de dérive et de batterie.
     * [startDeg] : d'où partir (par rapport au cap actuel) ; [count] vues espacées de [step]. [full] : finir le tour
     * pour revenir face au point de départ. Renvoie false si le drone ne tourne pas (on repasse alors au pas à pas).
     */
    private fun sweep(startDeg: Double, count: Int, step: Double, full: Boolean, onView: (Int) -> Unit): Boolean {
        drone.rotatingInPlace = true
        try {
            if (abs(startDeg) > 1) {
                val r = drone.move(0.0, 0.0, 0.0, startDeg, maxStep = drone.limits.missionStepM)
                if (r.startsWith("refusé") || r.startsWith("interrompu")) throw Stop("repérage arrêté : $r")
                Thread.sleep(250)
            }
            val rate = if (drone.indoor) 36.0 else 45.0
            var last = drone.compass()
            var turned = 0.0
            var next = 0
            val t0 = System.currentTimeMillis()
            val end = t0 + ((count * step + 20) / rate * 1000 + 8000).toLong()
            onView(next++)
            val goal = if (full) 360.0 - 6 else (count - 1) * step
            while (System.currentTimeMillis() < end) {
                checkStop()
                drone.velocity(0.0, 0.0, 0.0, rate, holdMs = 450)
                Thread.sleep(40)
                val h = drone.compass()
                turned += norm180(h - last)
                last = h
                if (System.currentTimeMillis() - t0 > 3000 && turned < 5) { drone.stopVelocity(); return false }
                if (next < count && turned >= next * step - 1.5) onView(next++)
                if (next >= count && turned >= goal) break
            }
            drone.stopVelocity()
            Thread.sleep(400)
            // Coup d'œil : revenir face à la direction de départ
            if (!full) drone.move(0.0, 0.0, 0.0, -(startDeg + turned), maxStep = drone.limits.missionStepM)
            return next >= count
        } finally {
            drone.stopVelocity()
            drone.rotatingInPlace = false
        }
    }

    private fun run(target: String?, climbM: Double?, lookDown: Boolean, arcDeg: Double, measure: Boolean): SurveyResult {
        // Dehors seulement : monter pour voir plus loin
        if (climbM != null && climbM > 0.3) {
            if (drone.indoor) report("🏠 En intérieur, je ne monte pas pour regarder : je reste à cette hauteur")
            else {
                report("🔭 Je monte de %.0f m pour voir plus loin".format(climbM))
                drone.move(0.0, 0.0, climbM, 0.0, maxStep = drone.limits.missionStepM)
            }
        }
        val full = arcDeg >= 359.0
        val views = if (!full) 3 else if (drone.indoor) 8 else 6
        val step = if (full) 360.0 / views else arcDeg / (views - 1)
        val start = if (full) 0.0 else -arcDeg / 2
        report((if (full) "👀 Repérage : un tour sur place (${views} vues)" else "👀 Coup d'œil autour (${arcDeg.roundToInt()}°)") +
            (target?.let { ", en cherchant « $it »" } ?: ""))
        drone.tiltCamera(if (drone.indoor) 0.0 else -15.0)
        val pos = drone.positionNE()
        val jobs = ArrayList<Triple<Double, Future<ViewReport>, Int>>()
        val low = arrayOfNulls<Gray>(views)
        fun view(i: Int, jpeg: String) {
            low[i] = frames?.grab()
            val heading = drone.lookHeading()
            jobs.add(Triple(heading, pool.submit<ViewReport> { analyzer.analyze(jpeg, target, false, hfovDeg) }, i))
        }
        // Drone qui sait se tourner en continu et connaît son cap (boussole) : un seul mouvement fluide
        val swept = drone.supportsVelocity && drone.reliableCompass && !simulation &&
            sweep(start, views, step, full) { i -> view(i, captureNow()) }
        if (!swept) {
            jobs.clear()
            if (abs(start) > 1) drone.move(0.0, 0.0, 0.0, start, maxStep = drone.limits.missionStepM)
            drone.rotatingInPlace = true
            try {
                for (i in 0 until views) {
                    checkStop()
                    view(i, capture())
                    if (i == views - 1 && !full) break
                    if (simulation) report("(Simulation) Tourne le téléphone de ${step.roundToInt()}° vers la droite")
                    val r = drone.move(0.0, 0.0, 0.0, step, maxStep = drone.limits.missionStepM)
                    if (r.startsWith("refusé") || r.startsWith("interrompu")) throw Stop("repérage arrêté : $r")
                }
                if (!full) drone.move(0.0, 0.0, 0.0, -(start + (views - 1) * step), maxStep = drone.limits.missionStepM)
            } finally {
                drone.rotatingInPlace = false
            }
        }
        // Second tour, 25 cm plus haut (ou plus bas) : les distances se mesurent au décalage entre les deux images.
        // Long : seulement sur demande (sinon les distances sont mesurées juste avant d'avancer).
        val depths = arrayOfNulls<DepthReading>(views)
        val base = if (measure && full && frames != null && !simulation) ranger?.baseline() else null
        if (base != null) {
            checkStop()
            report("📏 Deuxième tour, ${(kotlin.math.abs(base) * 100).roundToInt()} cm plus ${if (base > 0) "haut" else "bas"}, pour mesurer les distances")
            val alt1 = drone.altitude()
            drone.move(0.0, 0.0, base, 0.0, maxStep = drone.limits.missionStepM)
            val dz = drone.altitude() - alt1
            val b = if (kotlin.math.abs(dz - base) < 0.12 && kotlin.math.abs(dz) > 0.1) dz else base
            for (i in 0 until views) {
                checkStop()
                Thread.sleep(700)
                val g2 = frames!!.grab()
                val g1 = low[i]
                if (g1 != null && g2 != null) depths[i] = Parallax.depth(g1, g2, b, hfovDeg)
                drone.move(0.0, 0.0, 0.0, step, maxStep = drone.limits.missionStepM)
            }
            drone.move(0.0, 0.0, -base, 0.0, maxStep = drone.limits.missionStepM)
        }
        var below: ViewReport? = null
        if (lookDown) {
            checkStop()
            drone.tiltCamera(-80.0)
            if (simulation) report("(Simulation) Pointe le téléphone vers le sol")
            val jpeg = capture()
            below = runCatching { analyzer.analyze(jpeg, null, true, hfovDeg) }.getOrNull()
        }
        // Résultats
        val sectors = ArrayList<Pair<Double, Double>>()
        var tBearing: Double? = null
        var tDist: Double? = null
        var tDesc: String? = null
        val indoorVotes = ArrayList<Boolean>()
        val ceilings = ArrayList<Double>()
        val alt = drone.altitude()
        val summaries = ArrayList<String>()
        for ((heading, f, i) in jobs) {
            val v = runCatching { f.get(40, TimeUnit.SECONDS) }.getOrNull() ?: continue
            v.indoor?.let { indoorVotes.add(it) }
            v.ceilingAboveM?.let { ceilings.add(alt + it) }
            // Place libre : l'estimation de l'IA, réduite par la mesure si elle voit plus près
            val dep = depths[i]
            val measured = dep?.center
            v.freeAheadM?.let { sectors.add(heading to (if (measured != null) minOf(it, measured) else it)) }
            if (v.freeAheadM == null && measured != null && measured < Parallax.MAX_M) sectors.add(heading to measured)
            dep?.left?.takeIf { it < Parallax.MAX_M }?.let { sectors.add(heading - hfovDeg / 3 to it) }
            dep?.right?.takeIf { it < Parallax.MAX_M }?.let { sectors.add(heading + hfovDeg / 3 to it) }
            summaries.add("${(i * step).roundToInt()}° : ${v.summary}")
            for (it in v.items) {
                val b = heading + (it.x - 0.5) * hfovDeg
                val d = it.distanceM ?: continue
                val (n, e) = SceneMemory.project(pos, b, d)
                memory.add(SceneItem(it.label, it.kind, n, e, it.widthM, it.open, System.currentTimeMillis()))
                // Murs, meubles, obstacles, portes fermées : place libre limitée dans leur direction
                if (it.kind in setOf("mur", "meuble", "obstacle", "escalier") || (it.kind == "porte" && it.open == false)) sectors.add(b to d)
            }
            v.target?.let { t ->
                if (tBearing == null || (t.distanceM ?: 99.0) < (tDist ?: 99.0)) {
                    tBearing = heading + (t.x - 0.5) * hfovDeg
                    tDist = t.distanceM
                    tDesc = t.label
                }
            }
        }
        memory.setFreeSpace(pos, sectors)
        val types = jobs.mapNotNull { runCatching { it.second.get(1, TimeUnit.SECONDS).roomType }.getOrNull() }
        memory.recordRoom(pos, sectors, types.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        if (ceilings.isNotEmpty()) memory.setCeiling(ceilings.sorted()[ceilings.size / 2])
        below?.let { memory.setBelowAt(drone.positionNE(), it.belowM, (if (it.belowClear == false) "attention : " else "") + it.summary) }
        val indoor = if (indoorVotes.isEmpty()) null else indoorVotes.count { it } * 2 > indoorVotes.size
        indoor?.let(onIndoor)
        tBearing?.let { b ->
            val (n, e) = SceneMemory.project(pos, b, tDist ?: 1.0)
            memory.add(SceneItem(tDesc ?: (target ?: "cible"), "cible", n, e, null, null, System.currentTimeMillis()))
        }
        val doors = memory.doors().size
        report("👀 Repérage fini : ${memory.all().size} éléments en mémoire, $doors porte(s) ou passage(s) ouverts" +
            (tBearing?.let { " — « $target » repéré !" } ?: ""))
        val head = drone.lookHeading()
        val tgt = tBearing?.let { b ->
            val r = norm180(b - head).roundToInt()
            "OBJET CHERCHÉ VU : $tDesc, " + (if (abs(r) <= 10) "devant" else if (r > 0) "$r° à droite" else "${-r}° à gauche") +
                (tDist?.let { d -> ", à environ %.1f m".format(d) } ?: "") + ". "
        } ?: (target?.let { "« $it » pas vu pendant ce repérage. " } ?: "")
        val text = tgt + (if (indoor == true) "Lieu : intérieur. " else if (indoor == false) "Lieu : extérieur. " else "") +
            memory.describe(drone.positionNE(), head) + " Vues : " + summaries.joinToString(" | ")
        return SurveyResult(tBearing, tDist, tDesc, text, indoor)
    }
}
