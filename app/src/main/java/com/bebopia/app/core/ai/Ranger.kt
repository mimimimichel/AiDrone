package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.vision.DepthReading
import com.bebopia.app.core.vision.GrayFrames
import com.bebopia.app.core.vision.Parallax
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Mesure des distances devant le drone par parallaxe : image, montée (ou descente) de 25 cm (50 cm dehors), image,
 * retour à la hauteur de départ. Environ 3 secondes. Le résultat ne sert qu'à réduire les distances estimées par l'IA :
 * un mur blanc uni ne donne aucun point, ce qui ne veut pas dire « libre ».
 */
class Ranger(
    private val drone: DroneBase,
    private val frames: GrayFrames,
    private val hfovDeg: Double,
    private val report: (String) -> Unit,
) {
    @Volatile var last: DepthReading? = null
        private set
    private var lastAt = 0L
    private var lastPos = 0.0 to 0.0
    private var lastHeading = 0.0

    /** Dernière mesure si elle vaut encore ici (moins de 15 s, moins de 30 cm parcourus, même cap). */
    fun recent(): DepthReading? {
        val p = drone.positionNE()
        if (System.currentTimeMillis() - lastAt > 15_000) return null
        if (hypot(p.first - lastPos.first, p.second - lastPos.second) > 0.3) return null
        if (abs(com.bebopia.app.core.norm180(drone.lookHeading() - lastHeading)) > 10) return null
        return last
    }

    /** Écart vertical possible pour la mesure (+ = monter), null si pas la place. */
    fun baseline(): Double? {
        val b = if (drone.indoor) 0.25 else 0.5
        val top = if (drone.indoor) (drone.space?.ceilingAlt()?.minus(0.5) ?: 2.0) else drone.limits.maxAltitudeM - 0.3
        return when {
            drone.altitude() + b <= top -> b
            drone.altitude() - b >= drone.limits.minAltitudeM + 0.1 -> -b
            else -> null
        }
    }

    fun measure(): DepthReading? {
        if (!drone.flying) return null
        val b = baseline() ?: return null
        val wasPaused = drone.headPaused
        drone.headPaused = true
        val tilt0 = drone.cameraTilt.toDouble()
        try {
            if (abs(drone.gimbalPitch()) > 8) { drone.tiltCamera(0.0); Thread.sleep(700) }
            Thread.sleep(500)
            val g1 = frames.grab() ?: return null
            val alt1 = drone.altitude()
            val r = drone.move(0.0, 0.0, b, 0.0, maxStep = drone.limits.missionStepM)
            if (!r.startsWith("déplacé")) return null
            Thread.sleep(700)
            val g2 = frames.grab()
            val dz = drone.altitude() - alt1
            drone.move(0.0, 0.0, -b, 0.0, maxStep = drone.limits.missionStepM)
            if (g2 == null) return null
            // Hauteur réellement prise si le drone la mesure bien, sinon celle demandée
            val base = if (abs(dz - b) < 0.12 && abs(dz) > 0.1) dz else b
            val d = Parallax.depth(g1, g2, base, hfovDeg)
            last = d
            lastAt = System.currentTimeMillis()
            lastPos = drone.positionNE()
            lastHeading = drone.lookHeading()
            report("📏 Distances mesurées devant : $d")
            return d
        } finally {
            drone.headPaused = wasPaused
            if (abs(drone.cameraTilt - tilt0) > 1) runCatching { drone.tiltCamera(tilt0) }
        }
    }
}
