package com.bebopia.app.core.vision

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

/**
 * Maintien optique : quand le drone n'a ni GPS ni capteurs du dessous (il dérive, « mode ATTI »),
 * on suit le décor dans la vidéo et on compense la dérive.
 * - le décor glisse sur le côté (sans que le drone ait tourné) -> le drone a glissé de l'autre côté ;
 * - le décor grossit -> le drone a avancé ; il rapetisse -> il a reculé.
 * Caméra vers le bas : le sol qui défile vers le bas de l'image -> le drone a avancé.
 * Les mesures sont des angles (pas des mètres) : il suffit de ramener le décor à sa place.
 */
class OpticalHold(private val hfovDeg: Double) {
    /** Commande (m/s, repère du drone) : avant, droite. */
    class Command(val forward: Double, val right: Double, val points: Int)

    private var refPyr: List<Gray>? = null
    private var refPts: List<FloatArray> = emptyList()
    private var prevPyr: List<Gray>? = null
    private var curPts: List<FloatArray?> = emptyList()
    private var refYaw = 0.0
    private var refPitch = 0.0
    // Écart déjà accumulé avant le dernier changement de référence
    private var baseLat = 0.0
    private var baseFwd = 0.0
    private var lastLat = 0.0
    private var lastFwd = 0.0
    private var lastT = 0L
    var lastPoints = 0
        private set

    fun reset() {
        refPyr = null; prevPyr = null; refPts = emptyList(); curPts = emptyList()
        baseLat = 0.0; baseFwd = 0.0; lastLat = 0.0; lastFwd = 0.0; lastT = 0L; lastPoints = 0
    }

    /**
     * Nouvelle image. [yawDeg] : cap du drone ; [pitchDeg] : inclinaison de la caméra (négatif = vers le bas).
     * Renvoie la commande à appliquer, ou null s'il n'y a pas assez de décor à suivre (on ne corrige pas).
     */
    fun update(g: Gray, yawDeg: Double, pitchDeg: Double, tMs: Long): Command? {
        val pyr = g.pyramid(3)
        val ref = refPyr
        // Caméra inclinée d'un coup (tête, ordre) : la référence ne vaut plus rien
        if (ref == null || abs(pitchDeg - refPitch) > 3 || ref[0].w != g.w || ref[0].h != g.h) {
            rekey(pyr, g, yawDeg, pitchDeg, keepOffset = ref != null && abs(pitchDeg - refPitch) <= 3)
            return null
        }
        // Suivi image après image (petits déplacements, plus fiable)
        val prev = prevPyr ?: pyr
        curPts = curPts.map { p -> p?.let { Tracker.track(prev, pyr, it[0], it[1]) } }
        prevPyr = pyr
        val pairs = refPts.indices.mapNotNull { i -> curPts[i]?.let { refPts[i] to it } }
        lastPoints = pairs.size
        if (pairs.size < 6) {
            rekey(pyr, g, yawDeg, pitchDeg, keepOffset = true)
            return null
        }
        val f = (g.w / 2.0) / tan(Math.toRadians(hfovDeg / 2))
        val fit = fit(pairs, g.w / 2.0, g.h / 2.0) ?: run { rekey(pyr, g, yawDeg, pitchDeg, keepOffset = true); return null }
        val (s, tx, ty) = fit
        val down = pitchDeg < -50
        val lat: Double
        val fwd: Double
        if (down) {
            // Caméra vers le sol : le sol défile vers le bas quand le drone avance
            lat = tx / f
            fwd = ty / f
        } else {
            // Rotation du drone : le décor glisse de f·tan(rotation) vers la gauche ; on l'enlève
            val dyaw = com.bebopia.app.core.norm180(yawDeg - refYaw)
            lat = (tx + f * tan(Math.toRadians(dyaw))) / f
            fwd = ln(s)
        }
        val totLat = baseLat + lat
        val totFwd = baseFwd + fwd
        // Trop loin de la référence : on a perdu le fil, on repart d'ici
        if (abs(totLat) > 0.5 || abs(totFwd) > 0.5) { reset(); return null }
        val dt = if (lastT == 0L) 0.0 else (tMs - lastT) / 1000.0
        val dLat = if (dt in 0.03..1.0) (totLat - lastLat) / dt else 0.0
        val dFwd = if (dt in 0.03..1.0) (totFwd - lastFwd) / dt else 0.0
        lastLat = totLat; lastFwd = totFwd; lastT = tMs
        // Décor parti vers la droite (lat > 0) = drone parti vers la gauche -> aller à droite.
        // Décor grossi (fwd > 0) = drone avancé -> reculer. Sol descendu (vers le bas) = drone avancé -> reculer.
        var right = K_LAT * dead(totLat, 0.008) + D_LAT * dLat
        var forward = -(if (down) K_LAT else K_FWD) * dead(totFwd, if (down) 0.008 else 0.006) - (if (down) D_LAT else D_FWD) * dFwd
        right = right.coerceIn(-MAX, MAX)
        forward = forward.coerceIn(-MAX, MAX)
        // Plus assez de points répartis : on garde l'écart, on repart avec de nouveaux points
        if (pairs.size < refPts.size / 2) {
            baseLat = totLat; baseFwd = totFwd
            refPyr = pyr; refPts = Tracker.pickPoints(g, 6, 6, g.w - 7, g.h - 7, maxOf(8, g.w / 10), 4f)
            curPts = refPts.map { it }; refYaw = yawDeg
        }
        return Command(forward, right, pairs.size)
    }

    private fun dead(v: Double, d: Double) = if (abs(v) < d) 0.0 else v - Math.signum(v) * d

    private fun rekey(pyr: List<Gray>, g: Gray, yawDeg: Double, pitchDeg: Double, keepOffset: Boolean) {
        if (keepOffset) { baseLat = lastLat; baseFwd = lastFwd } else { baseLat = 0.0; baseFwd = 0.0; lastLat = 0.0; lastFwd = 0.0 }
        refPyr = pyr; prevPyr = pyr
        refPts = Tracker.pickPoints(g, 6, 6, g.w - 7, g.h - 7, maxOf(8, g.w / 10), 4f)
        curPts = refPts.map { it }
        refYaw = yawDeg; refPitch = pitchDeg
    }

    /**
     * Zoom et glissement qui amènent les points de référence sur les points actuels (moindres carrés, deux passes
     * pour écarter les points aberrants : objet qui bouge, reflet). Renvoie (zoom, glissement x, glissement y).
     */
    private fun fit(pairs: List<Pair<FloatArray, FloatArray>>, cx: Double, cy: Double): Triple<Double, Double, Double>? {
        var use = pairs
        var result: Triple<Double, Double, Double>? = null
        repeat(2) { pass ->
            if (use.size < 5) return result
            val ax = use.map { it.first[0] - cx }; val ay = use.map { it.first[1] - cy }
            val bx = use.map { it.second[0] - cx }; val by = use.map { it.second[1] - cy }
            val max = ax.average(); val may = ay.average(); val mbx = bx.average(); val mby = by.average()
            var num = 0.0; var den = 0.0
            for (i in use.indices) {
                val px = ax[i] - max; val py = ay[i] - may
                num += px * (bx[i] - mbx) + py * (by[i] - mby)
                den += px * px + py * py
            }
            if (den < 1e-6) return null
            val s = (num / den).coerceIn(0.5, 2.0)
            val tx = mbx - s * max
            val ty = mby - s * may
            result = Triple(s, tx, ty)
            if (pass == 0) {
                val res = use.indices.map { i -> abs(bx[i] - (s * ax[i] + tx)) + abs(by[i] - (s * ay[i] + ty)) }
                val med = res.sorted()[res.size / 2]
                val lim = maxOf(1.5, med * 2.5)
                use = use.filterIndexed { i, _ -> res[i] <= lim }
            }
        }
        return result
    }

    companion object {
        /** Gains : m/s par radian d'écart, et par radian/s (amortissement). Doux : le drone est en intérieur. */
        const val K_LAT = 1.2
        const val D_LAT = 0.5
        const val K_FWD = 1.5
        const val D_FWD = 0.6
        const val MAX = 0.25
    }
}
