package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.vision.Box
import com.bebopia.app.core.vision.GrayFrames
import com.bebopia.app.core.vision.Marks
import com.bebopia.app.core.vision.TargetTracker
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Ce que montre une vue pour atterrir : le point sous le drone (centre de l'image vers le bas) est-il sûr, et le meilleur endroit. */
class LandingView(val safeBelow: Boolean, val spotX: Double?, val spotY: Double?, val spotDesc: String, val reason: String)

/** Juge un endroit pour se poser sur une image (JPEG base64). Bloquant. */
fun interface LandingAnalyzer { fun analyze(jpegBase64: String, altitudeM: Double, downward: Boolean): LandingView }

/**
 * Atterrissage soigné en intérieur : regarder dessous, choisir un endroit plat et dégagé tout près (pas un bord de table,
 * pas de câble, ni personne ni animal), s'y placer, puis descendre en se recalant sur l'endroit à l'image tous les 20 cm
 * (le drone dérive un peu en descendant). Sous 50 cm, atterrissage normal.
 */
class Lander(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val frames: GrayFrames?,
    private val analyzer: LandingAnalyzer,
    private val hfovDeg: Double,
    private val simulation: Boolean,
    private val report: (String) -> Unit,
) {
    /** « stop » : on reste sur place. */
    @Volatile var abort = false

    private class Stop(msg: String) : Exception(msg)
    private val vfovDeg get() = hfovDeg * 9 / 16
    private val DOWN = -80.0 // nacelle vers le bas (le Mini va jusqu'à -90)

    fun land(): String {
        if (!drone.flying) return "déjà au sol"
        abort = false
        val wasPaused = drone.headPaused
        drone.headPaused = true
        return try {
            run()
        } catch (e: Stop) {
            e.message ?: "atterrissage interrompu"
        } finally {
            drone.headPaused = wasPaused
            Marks.clear("landing")
        }
    }

    private fun checkStop() {
        if (abort) throw Stop("atterrissage arrêté : je reste sur place")
        if (!drone.flying) throw Stop("posé")
    }

    private fun look(downward: Boolean): LandingView? {
        checkStop()
        Thread.sleep(if (simulation) 400 else 900)
        val jpeg = eyes.captureJpegBase64() ?: return null
        return runCatching { analyzer.analyze(jpeg, drone.altitude(), downward) }.getOrNull()
    }

    /** Point de l'image (x, y de 0 à 1) -> déplacement au sol (avant, droite) en mètres, d'après l'altitude et la nacelle. */
    private fun groundOffset(x: Double, y: Double): Pair<Double, Double>? {
        val alt = drone.altitude().coerceAtLeast(0.3)
        val depression = -drone.gimbalPitch() + (y - 0.5) * vfovDeg // degrés sous l'horizon
        if (depression < 8) return null // au-dessus de l'horizon : pas un endroit au sol
        val fwd = alt / tan(Math.toRadians(depression))
        val slant = alt / sin(Math.toRadians(depression))
        val right = slant * tan(Math.toRadians((x - 0.5) * hfovDeg))
        return fwd to right
    }

    private fun mark(v: LandingView, label: String) {
        if (v.spotX != null && v.spotY != null) Marks.show("landing", listOf(Marks.Mark(Box(v.spotX, v.spotY, 0.25, 0.25), label, Marks.Kind.FOUND)), 4000)
    }

    private fun run(): String {
        report("🛬 Je cherche où me poser")
        drone.tiltCamera(DOWN)
        if (simulation) report("(Simulation) Pointe le téléphone vers le sol")
        var v = look(true)
        if (v == null) return plainLand("pas d'analyse possible")
        if (!v.safeBelow) {
            // Pas sous moi : le meilleur endroit visible dessous, sinon on regarde autour, caméra à 45°
            var target: Pair<Double, Double>? = v.spotX?.let { x -> groundOffset(x, v!!.spotY ?: 0.5) }
            var turned = 0.0
            if (target == null) {
                drone.tiltCamera(-45.0)
                for (i in 0 until 4) {
                    val around = look(false)
                    if (around?.spotX != null && around.spotY != null) {
                        mark(around, "ICI ?")
                        target = groundOffset(around.spotX, around.spotY)
                        if (target != null) { report("🛬 Endroit repéré : ${around.spotDesc.take(70)}"); break }
                    }
                    if (simulation) report("(Simulation) Tourne le téléphone de 90° vers la droite")
                    drone.move(0.0, 0.0, 0.0, 90.0)
                    turned += 90
                }
            } else {
                mark(v, "ICI")
                report("🛬 Pas juste dessous (${v.reason.take(60)}) : je vais à côté, ${v.spotDesc.take(50)}")
            }
            val t = target ?: return "pas d'endroit sûr repéré autour de moi pour me poser : je reste en l'air. " +
                "Dis « atterris tout de suite » pour me poser ici quand même, ou guide-moi."
            goTo(t.first, t.second)
            drone.tiltCamera(DOWN)
            v = look(true) ?: return plainLand("plus d'analyse possible")
            if (!v.safeBelow) return "l'endroit choisi ne convient pas finalement (${v.reason.take(80)}) : je reste en l'air"
        }
        report("🛬 Endroit sûr : ${v.spotDesc.ifBlank { "sol dégagé" }.take(70)}. Je descends en me recalant")
        mark(LandingView(true, 0.5, 0.5, "", ""), "ATTERRISSAGE")
        descend()
        return plainLand(null)
    }

    /** Se déplacer de (avant, droite) mètres, en petits pas. */
    private fun goTo(fwd: Double, right: Double) {
        var f = fwd; var r = right
        var n = 0
        while (hypot(f, r) > 0.08 && n++ < 20) {
            checkStop()
            val d = hypot(f, r)
            val k = minOf(1.0, (if (drone.indoor) 0.3 else 1.0) / d)
            if (simulation) report("(Simulation) Déplace le téléphone de ${(f * k * 100).roundToInt()} cm en avant et ${(r * k * 100).roundToInt()} cm à droite")
            val res = drone.move(f * k, r * k, 0.0, 0.0)
            if (!res.startsWith("déplacé")) throw Stop("je ne peux pas aller jusqu'à l'endroit choisi : $res")
            f -= f * k; r -= r * k
        }
    }

    /** Descente par paliers de 20 cm, recalée sur l'endroit à l'image (suivi visuel). */
    private fun descend() {
        val tracker = TargetTracker()
        val patch = Box(0.5, 0.5, 0.3, 0.3)
        frames?.grab()?.let { tracker.start(it, patch) }
        var n = 0
        while (drone.altitude() > 0.5 && n++ < 40) {
            checkStop()
            val step = minOf(0.2, drone.altitude() - 0.45)
            if (simulation) report("(Simulation) Descends le téléphone de ${(step * 100).roundToInt()} cm")
            drone.move(0.0, 0.0, -step, 0.0)
            Thread.sleep(400)
            val g = frames?.grab() ?: continue
            val b = tracker.update(g)
            if (b == null) { tracker.start(g, patch); continue }
            // L'endroit a glissé dans l'image : le drone a dérivé, on se recale
            val off = groundOffset(b.cx, b.cy)
            val center = groundOffset(0.5, 0.5)
            if (off != null && center != null) {
                val df = off.first - center.first
                val dr = off.second - center.second
                if (hypot(df, dr) > 0.05) drone.move(df.coerceIn(-0.2, 0.2), dr.coerceIn(-0.2, 0.2), 0.0, 0.0)
            }
            tracker.start(g, patch)
            Marks.show("landing", listOf(Marks.Mark(patch, "ATTERRISSAGE", Marks.Kind.FOUND)), 1500)
        }
    }

    private fun plainLand(why: String?): String {
        checkStop()
        why?.let { report("🛬 $it : je me pose ici") }
        val r = drone.land()
        return if (why == null) "posé à l'endroit choisi ($r)" else r
    }
}
