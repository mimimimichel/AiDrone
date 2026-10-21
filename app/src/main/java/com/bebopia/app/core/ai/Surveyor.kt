package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.norm180
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

    fun survey(target: String?, climbM: Double? = null, lookDown: Boolean = true): SurveyResult {
        if (!drone.flying) return SurveyResult(null, null, null, "refusé : le drone est au sol, décolle d'abord", null)
        val wasPaused = drone.headPaused
        drone.headPaused = true
        val tilt0 = drone.cameraTilt.toDouble()
        return try {
            run(target, climbM, lookDown)
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
        Thread.sleep(if (simulation) 400 else 900) // image nette après la rotation
        var f = eyes.captureJpegBase64()
        var n = 0
        while (f == null && n++ < 5) { Thread.sleep(300); f = eyes.captureJpegBase64() }
        return f ?: throw Stop("repérage impossible : pas d'image de la caméra")
    }

    private fun run(target: String?, climbM: Double?, lookDown: Boolean): SurveyResult {
        // Dehors seulement : monter pour voir plus loin
        if (climbM != null && climbM > 0.3) {
            if (drone.indoor) report("🏠 En intérieur, je ne monte pas pour regarder : je reste à cette hauteur")
            else {
                report("🔭 Je monte de %.0f m pour voir plus loin".format(climbM))
                drone.move(0.0, 0.0, climbM, 0.0, maxStep = drone.limits.missionStepM)
            }
        }
        val views = if (drone.indoor) 8 else 6
        val step = 360.0 / views
        report("👀 Repérage : je regarde tout autour (${views} vues)" + (target?.let { ", en cherchant « $it »" } ?: ""))
        drone.tiltCamera(if (drone.indoor) 0.0 else -15.0)
        val pos = drone.positionNE()
        val jobs = ArrayList<Triple<Double, Future<ViewReport>, Int>>()
        for (i in 0 until views) {
            checkStop()
            val jpeg = capture()
            val heading = drone.lookHeading()
            jobs.add(Triple(heading, pool.submit<ViewReport> { analyzer.analyze(jpeg, target, false, hfovDeg) }, i))
            if (simulation) report("(Simulation) Tourne le téléphone de ${step.roundToInt()}° vers la droite")
            val r = drone.move(0.0, 0.0, 0.0, step, maxStep = drone.limits.missionStepM)
            if (r.startsWith("refusé") || r.startsWith("interrompu")) throw Stop("repérage arrêté : $r")
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
            v.freeAheadM?.let { sectors.add(heading to it) }
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
