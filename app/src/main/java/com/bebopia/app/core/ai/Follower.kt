package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.vision.Box
import com.bebopia.app.core.vision.CocoClasses
import com.bebopia.app.core.vision.FastDetector
import com.bebopia.app.core.vision.Marks
import com.bebopia.app.core.vision.Seen
import com.bebopia.app.core.vision.GrayFrames
import com.bebopia.app.core.vision.TargetTracker
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Suivi d'une cible (« suis le chien », « suis-moi »), piloté par l'application et aidé par l'IA :
 *
 * - l'IA repère la cible au départ (elle comprend « le chien noir », « la personne en veste rouge ») ;
 * - ensuite, le téléphone suit la cible lui-même sur la vidéo, une dizaine d'images par seconde (suivi visuel local),
 *   et commande le drone à chaque image : rotation pour la garder au centre, avance ou recul pour garder sa taille ;
 * - toutes les 3 s, l'IA vérifie en arrière-plan que c'est bien la cible, et recale le suivi si besoin ;
 * - cible perdue : stationnaire, puis l'IA la recherche (en tournant si nécessaire).
 *
 * Le rayon, le détecteur d'obstacles, la batterie et « stop » restent prioritaires.
 */
class Follower(
    private val drone: DroneBase,
    private val eyes: Eyes,
    private val detector: ObjectDetector,
    /** Vidéo en niveaux de gris pour le suivi local (null : suivi par l'IA seule, plus lent). */
    private val frames: GrayFrames?,
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
        const val CHECK_MS = 3000L     // vérification par l'IA
    }

    private class Stop(msg: String) : Exception(msg)
    private class Check(val det: Detection, val trackedAtCapture: Box?)

    private val tracker = TargetTracker()

    /** Détecteur d'objets du téléphone (null : l'IA seule repère la cible). */
    var fast: FastDetector? = null

    /** Catégories du détecteur qui correspondent à la cible (null : il ne la connaît pas). */
    private var classes: Set<String>? = null

    private fun candidates(tiled: Boolean = false): List<Seen> {
        val f = fast ?: return emptyList()
        val c = classes ?: return emptyList()
        return runCatching { f.detect(tiled) }.getOrDefault(emptyList()).filter { it.label in c && it.score >= 0.35 }
    }

    @Volatile var target: String? = null
        private set

    /** Boîte suivie (pour l'affichage dans le casque), et si l'IA vient de la confirmer. */
    val box: Box? get() = if (target != null) tracker.box else null
    @Volatile var confirmedAt = 0L
        private set

    private val vfovDeg get() = hfovDeg * 9 / 16

    fun follow(targetName: String, minutes: Double?, hint: String? = null): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val t = targetName.trim().ifEmpty { return "refusé : cible non précisée" }
        target = t
        classes = if (fast != null && frames != null) CocoClasses.forTarget(t, hint) else null
        tracker.reset()
        val end = System.currentTimeMillis() + ((minutes ?: MAX_MINUTES.toDouble()).coerceIn(0.5, MAX_MINUTES.toDouble()) * 60_000).toLong()
        return try {
            report("🎯 Je cherche « $t » pour la suivre (dis « stop » pour arrêter)")
            val first = acquire(t) ?: return "pas trouvé « $t » autour de moi : suivi annulé"
            val refSize = sizeOf(first).coerceIn(0.08, 0.5)
            report("🎯 Je suis « $t » : le téléphone la suit sur la vidéo" +
                (if (classes != null) " et la reconnaît (${classes!!.first()})" else "") + ", l'IA vérifie toutes les 3 s")
            onSeen(t)
            if (frames == null) followByAi(t, refSize, end) else followLocal(t, refSize, end)
        } catch (e: Stop) {
            e.message ?: "suivi arrêté"
        } finally {
            drone.stopVelocity()
            target = null
            tracker.reset()
            Marks.clear("follow")
        }
    }

    private fun sizeOf(b: Box) = maxOf(b.h, b.w * 0.8)
    private fun sizeOf(d: Detection) = maxOf(d.h, d.w * 0.8)

    private fun checkStop(end: Long) {
        if (shouldStop()) throw Stop("suivi arrêté par le pilote")
        if (!drone.flying) throw Stop("suivi arrêté : le drone n'est plus en vol")
        if (System.currentTimeMillis() > end) throw Stop("suivi terminé : durée maximale atteinte")
        drone.battery()?.let { b -> if (b < drone.limits.minBattery + 10 && !simulation) throw Stop("suivi arrêté : batterie à $b %") }
    }

    /** L'IA cherche la cible ; si besoin, le drone tourne par pas de 45°. Démarre le suivi local dessus. */
    private fun acquire(t: String): Detection? {
        for (i in 0 until 8) {
            if (shouldStop()) throw Stop("suivi arrêté par le pilote")
            // Le détecteur du téléphone d'abord : une seule cible possible (et demande simple) -> pas besoin d'attendre l'IA.
            val local = candidates().ifEmpty { candidates(tiled = true) }
            Marks.show("follow", local.map { Marks.Mark(it.box, CocoClasses.french(it.label), Marks.Kind.CANDIDATE) }, 2500)
            if (local.size == 1 || (local.isNotEmpty() && CocoClasses.isGeneric(t))) {
                val best = local.maxBy { it.score }
                if (best.score >= 0.5) {
                    frames?.grab()?.let { g -> tracker.start(g, best.box) }
                    report("🎯 Repérée par le téléphone (${best.label}, ${(best.score * 100).toInt()} %) : l'IA va confirmer")
                    return Detection(true, best.score, best.box.cx, best.box.cy, best.box.w, best.box.h, best.label)
                }
            }
            val jpeg = eyes.captureJpegBase64() ?: run { Thread.sleep(300); null } ?: continue
            val d = try { detector.find(jpeg, t) } catch (e: Exception) { throw Stop("suivi impossible : analyse d'image impossible (${e.message})") }
            if (d.found && d.confidence >= 0.4) {
                // Plusieurs candidats : l'IA a dit lequel ; on prend la boîte du détecteur qui lui correspond (plus précise).
                val gb = Box(d.cx, d.cy, d.w, d.h)
                val match = local.maxByOrNull { it.box.iou(gb) }?.takeIf { it.box.iou(gb) > 0.3 }?.box ?: gb
                frames?.grab()?.let { g -> tracker.start(g, match) }
                Marks.show("follow", listOf(Marks.Mark(match, t, Marks.Kind.FOLLOWING)), 1500)
                confirmedAt = System.currentTimeMillis()
                return d
            }
            if (i == 0) report("🎯 Je ne vois pas « $t » : je regarde autour")
            turn(45.0)
            Thread.sleep(if (simulation) 300 else 800)
        }
        return null
    }

    // ---- suivi par le téléphone ------------------------------------------------------------------
    private fun followLocal(t: String, refSize: Double, end: Long): String {
        val src = frames!!
        val pendingCheck = AtomicReference<Check?>(null)
        // Détecteur du téléphone en continu, dans son propre fil (5 à 15 images/s selon le téléphone)
        val latestSeen = AtomicReference<List<Seen>?>(null)
        val detecting = java.util.concurrent.atomic.AtomicBoolean(classes != null)
        val detThread = if (detecting.get()) Thread({
            while (detecting.get() && target != null) {
                val l = candidates()
                latestSeen.set(l)
                if (l.isEmpty()) Thread.sleep(40)
            }
        }, "suivi-detecteur").apply { isDaemon = true; start() } else null
        var lastPos: Box? = tracker.box
        try {
        val checking = java.util.concurrent.atomic.AtomicBoolean(false)
        var lastCheck = System.currentTimeMillis()
        var lostSince = 0L
        var aiMisses = 0
        var lastSide = 1.0
        var lastStep = 0L
        var seen = 1
        var simTalk = 0L
        while (true) {
            checkStop(end)
            val t0 = System.currentTimeMillis()
            val g = src.grab()
            if (g == null) {
                drone.stopVelocity()
                Thread.sleep(100)
                continue
            }
            var b = tracker.update(g)

            // Ce que voit le détecteur du téléphone : recale le suivi, ou le retrouve tout de suite s'il a décroché.
            latestSeen.getAndSet(null)?.let { seen ->
                val ref = b ?: lastPos
                if (ref != null && seen.isNotEmpty()) {
                    val near = seen.maxBy { it.box.iou(ref) - 0.5 * (abs(it.box.cx - ref.cx) + abs(it.box.cy - ref.cy)) }
                    val iou = near.box.iou(ref)
                    val dist = abs(near.box.cx - ref.cx) + abs(near.box.cy - ref.cy)
                    if (b != null && iou in 0.35..0.75) {
                        tracker.start(g, near.box) // même cible, boîte plus juste
                        b = tracker.box
                    } else if (b == null && (iou > 0.2 || dist < 0.2)) {
                        tracker.start(g, near.box)
                        b = tracker.box
                        if (lostSince != 0L && t0 - lostSince > 300) report("🎯 « $t » retrouvée par le téléphone")
                        lostSince = 0L
                    }
                }
            }
            if (b != null) lastPos = b
            // Cadre sur la vidéo : bleu plein si l'IA a confirmé la cible il y a peu, sinon suivi par le téléphone seul
            if (b != null) Marks.show("follow", listOf(Marks.Mark(b, t,
                if (System.currentTimeMillis() - confirmedAt < 4000) Marks.Kind.FOLLOWING else Marks.Kind.TRACKING)), 600)

            // Vérification par l'IA, en arrière-plan (ne ralentit pas le suivi)
            if (!checking.get() && t0 - lastCheck > CHECK_MS) {
                checking.set(true)
                lastCheck = t0
                val at = tracker.box
                Thread({
                    try {
                        val jpeg = eyes.captureJpegBase64()
                        if (jpeg != null) pendingCheck.set(Check(detector.find(jpeg, t), at))
                    } catch (_: Exception) {
                    } finally {
                        checking.set(false)
                    }
                }, "suivi-verif").apply { isDaemon = true; start() }
            }
            pendingCheck.getAndSet(null)?.let { c ->
                val d = c.det
                if (d.found && d.confidence >= 0.4) {
                    aiMisses = 0
                    confirmedAt = System.currentTimeMillis()
                    seen++
                    if (seen % 5 == 0) onSeen(t)
                    val db = Box(d.cx, d.cy, d.w, d.h)
                    // Le suivi a perdu la cible, ou suit autre chose que ce que voit l'IA : on le recale.
                    if (b == null || c.trackedAtCapture == null || c.trackedAtCapture.iou(db) < 0.15) {
                        tracker.start(g, db)
                        b = tracker.box
                        if (lostSince != 0L) report("🎯 « $t » retrouvée")
                        lostSince = 0L
                    }
                } else if (++aiMisses >= 2 && b != null) {
                    // Deux vérifications sans la voir : le suivi s'est peut-être accroché au décor.
                    report("🎯 L'IA ne voit plus « $t » là où je regarde : je vérifie")
                    tracker.reset()
                    b = null
                    aiMisses = 0
                }
            }

            if (b == null) {
                drone.stopVelocity()
                if (lostSince == 0L) lostSince = t0
                if (t0 - lostSince > 1500) {
                    report("🎯 J'ai perdu « $t » de vue : je la cherche")
                    turn(25.0 * lastSide)
                    acquire(t) ?: return "suivi terminé : « $t » perdue de vue"
                    lostSince = 0L
                    report("🎯 « $t » retrouvée")
                }
                Thread.sleep(80)
                continue
            }
            lostSince = 0L

            val offDeg = (b.cx - 0.5) * hfovDeg
            lastSide = if (offDeg >= 0) 1.0 else -1.0
            val err = (refSize - sizeOf(b)) / refSize // > 0 : trop loin
            val outside = drone.distanceFromHome() > drone.limits.maxDistanceM - 2
            if (drone.supportsVelocity) {
                val vmax = if (drone.hasGps) MAX_SPEED else INDOOR_SPEED
                var f = if (abs(err) < 0.12) 0.0 else (err * vmax * 1.5).coerceIn(-vmax * 0.6, vmax)
                if (outside && f > 0) f = 0.0
                val yaw = if (abs(offDeg) < 2) 0.0 else (offDeg * 1.2).coerceIn(-45.0, 45.0)
                drone.velocity(f, 0.0, 0.0, yaw, holdMs = 500)
            } else if (t0 - lastStep > 1200) {
                // Drone sans vitesse continue : petits déplacements, la cible se décale d'autant dans l'image.
                lastStep = t0
                val talk = simulation && t0 - simTalk > 4000
                if (abs(offDeg) > 8) {
                    if (talk) { simTalk = t0; report("(Simulation) Tourne le téléphone de ${abs(offDeg).roundToInt()}° vers la ${if (offDeg > 0) "droite" else "gauche"}") }
                    drone.move(0.0, 0.0, 0.0, offDeg)
                    if (!simulation) tracker.shift(-offDeg / hfovDeg, 0.0)
                }
                val step = when {
                    abs(err) < 0.2 -> 0.0
                    err > 0 -> minOf(drone.limits.maxStepM * 2, 2.0)
                    else -> -minOf(drone.limits.maxStepM, 1.0)
                }
                if (step != 0.0 && !(outside && step > 0)) {
                    if (talk) { simTalk = t0; report("(Simulation) ${if (step > 0) "Avance" else "Recule"} le téléphone de ${(abs(step) * 100).roundToInt()} cm") }
                    val r = drone.move(step, 0.0, 0.0, 0.0, maxStep = 2.0)
                    if (r.startsWith("interrompu")) throw Stop("suivi arrêté : $r")
                }
            }
            // Cible trop haut / trop bas : on incline la caméra (et on décale le suivi d'autant).
            if (b.cy > 0.75 && drone.cameraTilt > -80) {
                drone.tiltCamera(drone.cameraTilt - 6.0)
                if (!simulation) tracker.shift(0.0, -6.0 / vfovDeg)
            } else if (b.cy < 0.25 && drone.cameraTilt < 0) {
                drone.tiltCamera(drone.cameraTilt + 6.0)
                if (!simulation) tracker.shift(0.0, 6.0 / vfovDeg)
            }
            // Une dizaine d'images par seconde
            val wait = 90 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
        } finally {
            detecting.set(false)
            detThread?.join(500)
        }
    }

    // ---- secours : l'IA seule, image par image (pas de vidéo en niveaux de gris) ---------------
    private fun followByAi(t: String, refSize: Double, end: Long): String {
        var lost = 0
        var lastSide = 1.0
        while (true) {
            checkStop(end)
            val frame = eyes.captureJpegBase64() ?: run { Thread.sleep(300); null } ?: continue
            val d = try { detector.find(frame, t) } catch (e: Exception) { throw Stop("suivi arrêté : analyse d'image impossible (${e.message})") }
            if (!d.found || d.confidence < 0.35) {
                drone.stopVelocity()
                if (++lost > 3) {
                    turn(35.0 * lastSide)
                    if (lost > 14) return "suivi terminé : « $t » perdue de vue"
                }
                continue
            }
            lost = 0
            confirmedAt = System.currentTimeMillis()
            val offDeg = (d.cx - 0.5) * hfovDeg
            lastSide = if (offDeg >= 0) 1.0 else -1.0
            val err = (refSize - sizeOf(d)) / refSize
            if (drone.supportsVelocity) {
                val vmax = if (drone.hasGps) MAX_SPEED else INDOOR_SPEED
                val f = if (abs(err) < 0.15) 0.0 else (err * vmax * 1.5).coerceIn(-vmax * 0.6, vmax)
                drone.velocity(f, 0.0, 0.0, (offDeg / 1.2).coerceIn(-45.0, 45.0), holdMs = 1800)
            } else {
                if (abs(offDeg) > 8) turn(offDeg)
                if (abs(err) >= 0.2) drone.move(if (err > 0) 1.0 else -0.5, 0.0, 0.0, 0.0, maxStep = 2.0)
            }
        }
    }

    private fun turn(deg: Double) {
        if (simulation) report("(Simulation) Tourne le téléphone de ${abs(deg).roundToInt()}° vers la ${if (deg > 0) "droite" else "gauche"}")
        if (drone.supportsVelocity) {
            drone.velocity(0.0, 0.0, 0.0, deg.coerceIn(-45.0, 45.0), holdMs = 1000)
            Thread.sleep(1000)
        } else {
            drone.move(0.0, 0.0, 0.0, deg)
        }
    }
}
