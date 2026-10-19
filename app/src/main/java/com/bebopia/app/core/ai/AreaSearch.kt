package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Mission de recherche autonome sur un terrain : « trouve une voiture », « cherche le chien ».
 *
 * 1. décoller et monter à l'altitude de recherche ;
 * 2. tour d'horizon depuis le point de départ (souvent suffisant sur un terrain dégagé) ;
 * 3. spirale carrée qui s'élargit autour du départ : à chaque point, tour complet, chaque image analysée ;
 *    une « piste » (objet incertain, souvent petit et loin) fait aller voir de plus près ;
 * 4. cible trouvée : face à elle, distance au sol estimée, approche avec une marge si demandé ;
 * 5. rien trouvé, batterie basse ou zone couverte : retour au point de départ.
 *
 * Le plan s'affiche et se met à jour au fil de la mission. Tous les déplacements passent par les
 * limites du drone (plafond, rayon, géobarrière) ; « stop » arrête tout.
 */
class AreaSearch(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val detector: ObjectDetector,
    private val hfovDeg: Double,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    private val onPlan: (Plan) -> Unit,
) {
    companion object {
        /** En dessous (%), on rentre : plus on est loin, plus il faut de marge pour revenir. */
        fun returnBattery(distanceM: Double) = (30 + distanceM / 25).coerceAtMost(60.0).toInt()
        const val MIN_START_BATTERY = 45
        const val SCAN_TILT = -30.0     // caméra pour regarder le terrain autour
        const val TRAVEL_TILT = -10.0   // caméra en route (le détecteur d'obstacles reste actif)
        const val KEEP_DISTANCE_M = 4.0 // marge gardée avec la cible
    }

    private class Abort(msg: String) : Exception(msg)

    private var plan = Plan("", emptyList())
    private var target = ""
    private var sightings = 0

    private fun setStep(i: Int, state: StepState, note: String = "") {
        plan = plan.copy(steps = plan.steps.mapIndexed { k, s -> if (k == i) PlanStep(s.text, state, note) else s })
        onPlan(plan)
    }

    fun run(targetName: String, radiusReq: Double?, altitudeReq: Double?, approach: Boolean): String {
        target = targetName.trim().ifEmpty { return "refusé : objet à chercher non précisé" }
        val l = drone.limits
        val bat = drone.battery()
        if (bat != null && bat < MIN_START_BATTERY && !simulation) {
            return "refusé : batterie à $bat %, il faut au moins $MIN_START_BATTERY % pour une mission de recherche"
        }
        val radius = (radiusReq ?: l.maxDistanceM).coerceIn(1.0, l.maxDistanceM * 0.9)
        // Plus la zone est grande, plus on monte (on voit plus loin) : de 8 à 40 m, jamais au-dessus du plafond.
        val alt = (altitudeReq ?: (radius / 5).coerceIn(8.0, 40.0)).coerceIn(1.2, l.maxAltitudeM)
        // Distance entre deux points de la spirale : à peu près ce que la caméra couvre depuis l'altitude choisie.
        val spacing = if (simulation) 1.0 else (alt * 1.6).coerceIn(4.0, 60.0)
        val points = spiral(spacing, radius)

        plan = Plan(
            "trouver « $target »",
            listOf(
                PlanStep("Décoller et monter à %.0f m".format(alt)),
                PlanStep("Tour d'horizon depuis le point de départ"),
                PlanStep("Spirale de ${points.size} points tous les %.0f m, jusqu'à %.0f m du départ".format(spacing, radius)),
                PlanStep(if (approach) "M'approcher de la cible (en gardant %.0f m)".format(KEEP_DISTANCE_M) else "Me placer face à la cible"),
                PlanStep("Revenir au point de départ si je ne trouve rien"),
            ),
            approved = true,
        )
        onPlan(plan)
        report("🛰 Mission : chercher « %s » dans un rayon de %.0f m, à %.0f m d'altitude".format(target, radius, alt))

        return try {
            // 1. décoller et monter
            setStep(0, StepState.DOING)
            if (!drone.flying) {
                val r = drone.takeoff()
                if (!drone.flying) throw Abort("décollage impossible : $r")
            }
            climbTo(alt)
            setStep(0, StepState.DONE)

            // 2. tour d'horizon
            setStep(1, StepState.DOING)
            scan()?.let { return foundTarget(it, approach) }
            setStep(1, StepState.DONE, "rien vu depuis le départ")

            // 3. spirale
            setStep(2, StepState.DOING)
            for ((i, p) in points.withIndex()) {
                checkBattery()
                setStep(2, StepState.DOING, "point ${i + 1}/${points.size}")
                if (!goTo(p.first, p.second)) continue
                scan()?.let { return foundTarget(it, approach) }
            }
            setStep(2, StepState.DONE, "zone couverte")
            setStep(3, StepState.SKIPPED)

            // 5. retour
            setStep(4, StepState.DOING)
            goHome()
            setStep(4, StepState.DONE)
            finish(false, "pas trouvé « %s » dans un rayon de %.0f m ; je suis revenu au départ".format(target, radius))
        } catch (e: Abort) {
            val i = plan.steps.indexOfFirst { it.state == StepState.DOING }
            if (i >= 0) setStep(i, StepState.FAILED, e.message ?: "")
            finish(false, e.message ?: "mission interrompue")
        }
    }

    private fun finish(success: Boolean, summary: String): String {
        plan = plan.copy(finished = true, success = success, summary = summary)
        onPlan(plan)
        return summary
    }

    // ---- déplacements -------------------------------------------------------------------------
    private fun checkStop() {
        if (shouldStop()) throw Abort("mission arrêtée par le pilote (le drone reste sur place)")
        if (!drone.flying) throw Abort("mission arrêtée : le drone n'est plus en vol")
    }

    private fun checkBattery() {
        val b = drone.battery() ?: return
        if (b < returnBattery(hypot(drone.x, drone.y)) && !simulation) {
            report("🔋 Batterie à $b % : je rentre")
            val i = plan.steps.indexOfFirst { it.state == StepState.DOING }
            if (i >= 0) setStep(i, StepState.FAILED, "batterie faible")
            setStep(4, StepState.DOING)
            goHome()
            setStep(4, StepState.DONE)
            throw Abort("batterie faible ($b %) : je suis revenu au point de départ sans avoir trouvé « $target »")
        }
    }

    private fun mv(f: Double = 0.0, r: Double = 0.0, u: Double = 0.0, yaw: Double = 0.0): String {
        checkStop()
        val res = drone.move(f, r, u, yaw, maxStep = drone.limits.missionStepM)
        if (res.startsWith("interrompu")) throw Abort("mission arrêtée : $res")
        return res
    }

    private fun climbTo(alt: Double) {
        repeat(10) {
            val du = alt - drone.altitude()
            if (abs(du) < 0.3) return
            if (simulation) report("(Simulation) Lève le téléphone : le drone monte à %.0f m".format(alt))
            val r = mv(u = du)
            if (r.startsWith("refusé")) throw Abort("montée impossible : $r")
        }
    }

    private fun turn(deg: Double) {
        if (abs(deg) < 2) return
        if (simulation) report("(Simulation) Tourne le téléphone de ${abs(deg).roundToInt()}° vers la ${if (deg > 0) "droite" else "gauche"}")
        // Une action tourne de 90° au plus : on découpe les grandes rotations.
        var left = deg
        while (abs(left) >= 2) {
            val part = left.coerceIn(-drone.limits.maxYawDeg, drone.limits.maxYawDeg)
            val r = mv(yaw = part)
            if (r.startsWith("refusé")) throw Abort("rotation impossible : $r")
            left -= part
        }
    }

    /** Va au point (x devant, y à droite du décollage). false si le point est inaccessible (on passe au suivant). */
    private fun goTo(x: Double, y: Double): Boolean {
        val dx = x - drone.x
        val dy = y - drone.y
        val dist = hypot(dx, dy)
        if (dist < 0.3) return true
        val bearing = Math.toDegrees(atan2(dy, dx))
        turn(normalize(bearing - drone.heading))
        drone.tiltCamera(TRAVEL_TILT)
        var left = dist
        while (left > 0.2) {
            val step = minOf(left, drone.limits.missionStepM)
            if (simulation) report("(Simulation) Avance le téléphone d'environ ${(step * 100).roundToInt()} cm")
            var r = mv(f = step)
            if (r.startsWith("refusé : obstacle")) {
                report("⚠ Obstacle devant : j'attends un peu")
                Thread.sleep(5500)
                r = mv(f = step)
            }
            if (r.startsWith("refusé")) {
                report("Point inaccessible ($r) : je passe au suivant")
                return false
            }
            left -= step
        }
        return true
    }

    private fun goHome() {
        if (hypot(drone.x, drone.y) < 0.5) return
        report("🏠 Retour au point de départ")
        goTo(0.0, 0.0)
        turn(normalize(-drone.heading))
    }

    // ---- observation ----------------------------------------------------------------------------
    /** Tour complet (4 vues à 90°). Renvoie la cible si elle est vue, avec le cap auquel elle a été vue. */
    private fun scan(): Detection? {
        drone.tiltCamera(SCAN_TILT)
        if (simulation) report("(Simulation) Incline un peu le téléphone vers le bas")
        for (i in 0 until 4) {
            val d = look()
            if (d.found && d.confidence >= 0.5) return d
            if (d.found && d.confidence >= 0.25) {
                // Piste : quelque chose qui pourrait être la cible. On va voir de plus près.
                checkTrack(d)?.let { return it }
            } else {
                report("🔎 ${(drone.heading).roundToInt()}° : ${d.description.take(70)}")
            }
            turn(90.0)
        }
        return null
    }

    /** Une piste incertaine : se tourner vers elle, s'en rapprocher et regarder à nouveau. */
    private fun checkTrack(d: Detection): Detection? {
        sightings++
        report("🤔 Peut-être « $target » : ${d.description.take(80)} — je vais voir")
        turn(offset(d))
        val dist = groundDistance(d)
        if (dist != null && dist > 3) {
            drone.tiltCamera(TRAVEL_TILT)
            val go = minOf(dist * 0.6, drone.limits.missionStepM * 2)
            var left = go
            while (left > 0.2) {
                val step = minOf(left, drone.limits.missionStepM)
                val r = mv(f = step)
                if (r.startsWith("refusé")) break
                left -= step
            }
            drone.tiltCamera(SCAN_TILT)
        }
        val again = look()
        if (again.found && again.confidence >= 0.45) return again
        report("Fausse piste : ${again.description.take(70)}")
        return null
    }

    private fun look(): Detection {
        checkStop()
        Thread.sleep(if (simulation) 400 else 1000) // stabilisation, et la vidéo rattrape son retard
        var frame = eyes.captureJpegBase64()
        var tries = 0
        while (frame == null && tries < 5) {
            Thread.sleep(300)
            frame = eyes.captureJpegBase64()
            tries++
        }
        if (frame == null) throw Abort("mission arrêtée : plus d'image de la caméra")
        checkStop()
        return try {
            detector.find(frame, target)
        } catch (e: Exception) {
            throw Abort("mission arrêtée : analyse d'image impossible (${e.message})")
        }
    }

    private fun offset(d: Detection) = (d.cx - 0.5) * hfovDeg

    /** Distance au sol estimée d'après l'altitude et la position de la cible dans l'image. */
    private fun groundDistance(d: Detection): Double? {
        val vfov = hfovDeg * 9 / 16
        val below = -drone.cameraTilt + (d.cy - 0.5) * vfov // degrés sous l'horizon
        if (below < 4) return null
        return drone.altitude() / tan(Math.toRadians(below))
    }

    private fun foundTarget(first: Detection, approach: Boolean): String {
        var d = first
        val i = plan.steps.indexOfFirst { it.state == StepState.DOING }
        if (i >= 0) setStep(i, StepState.DONE, "cible repérée")
        report("✅ Trouvé : ${d.description}")
        setStep(3, StepState.DOING)
        turn(offset(d))
        look().takeIf { it.found }?.let { d = it }
        var dist = groundDistance(d)
        if (approach && dist != null && dist > KEEP_DISTANCE_M + 1) {
            drone.tiltCamera(TRAVEL_TILT)
            var left = dist - KEEP_DISTANCE_M
            while (left > 0.3) {
                val step = minOf(left, drone.limits.missionStepM)
                val r = mv(f = step)
                if (r.startsWith("refusé")) {
                    report("Approche arrêtée : $r")
                    break
                }
                left -= step
            }
            drone.tiltCamera(SCAN_TILT)
            look().takeIf { it.found }?.let {
                d = it
                turn(offset(it))
            }
            dist = groundDistance(d)
        }
        val where = "à environ %.0f m du départ, cap %d°".format(hypot(drone.x, drone.y), drone.heading.roundToInt())
        val howFar = dist?.let { " ; cible à environ %.0f m devant moi".format(it) } ?: ""
        setStep(3, StepState.DONE, d.description.take(80))
        plan = plan.copy(steps = plan.steps.map { if (it.state == StepState.TODO) PlanStep(it.text, StepState.SKIPPED) else it })
        onPlan(plan)
        return finish(true, "trouvé « $target » : ${d.description}. Je suis $where, face à la cible$howFar")
    }

    // ---- géométrie --------------------------------------------------------------------------------
    private fun normalize(deg: Double): Double = ((deg + 540.0) % 360.0) - 180.0

    /** Spirale carrée autour du départ : côtés 1, 1, 2, 2, 3, 3… fois l'espacement, un point à chaque espacement. */
    private fun spiral(spacing: Double, radius: Double): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        var dir = 0 // 0 devant, 1 droite, 2 derrière, 3 gauche
        var leg = 1
        while (pts.size < 200) {
            repeat(2) {
                repeat(leg) {
                    when (dir) {
                        0 -> x += spacing
                        1 -> y += spacing
                        2 -> x -= spacing
                        else -> y -= spacing
                    }
                    if (hypot(x, y) <= radius) pts.add(x to y)
                }
                dir = (dir + 1) % 4
            }
            if (leg * spacing > radius * 1.5) break
            leg++
        }
        return pts
    }
}
