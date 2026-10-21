package com.bebopia.app.core.vision

import kotlin.math.abs
import kotlin.math.tan

/** Distances mesurées (m) dans trois bandes de l'image : gauche, centre, droite. null = pas assez de points. */
class DepthReading(val left: Double?, val center: Double?, val right: Double?, val points: Int) {
    override fun toString(): String {
        fun f(v: Double?) = v?.let { "%.1f m".format(it) } ?: "?"
        return "gauche ${f(left)}, centre ${f(center)}, droite ${f(right)} ($points points)"
    }
}

/**
 * Mesure des distances par parallaxe, comme avec deux yeux : le drone prend une image, monte (ou descend) de quelques
 * centimètres, en prend une seconde. Ce qui est proche se décale beaucoup dans l'image, ce qui est loin presque pas :
 * distance = focale (pixels) × déplacement (m) / décalage (pixels). La caméra est stabilisée (nacelle), seul le
 * décalage vertical compte. On suit des points bien texturés d'une image à l'autre (Lucas-Kanade).
 */
object Parallax {
    const val MAX_M = 20.0

    /**
     * [a] : image du bas ; [b] : image prise après être monté de [baselineUpM] mètres (négatif = descendu).
     * Ne garde que la bande autour de l'horizon (à hauteur du drone), où sont les obstacles du trajet.
     */
    fun depth(a: Gray, b: Gray, baselineUpM: Double, hfovDeg: Double): DepthReading {
        if (a.w != b.w || a.h != b.h || abs(baselineUpM) < 0.05) return DepthReading(null, null, null, 0)
        val focal = (a.w / 2.0) / tan(Math.toRadians(hfovDeg / 2))
        val y0 = (a.h * 0.22).toInt()
        val y1 = (a.h * 0.78).toInt()
        val step = maxOf(6, a.w / 40)
        val pts = Tracker.pickPoints(a, 8, y0, a.w - 9, y1, step, 6f)
        val bands = Array(3) { ArrayList<Double>() }
        var n = 0
        // Le drone monte -> la scène descend dans l'image (y vers le bas). Décalage max : objet à 0,4 m.
        val sgn = if (baselineUpM > 0) 1 else -1
        val maxDy = (focal * abs(baselineUpM) / 0.4).toInt().coerceAtMost(a.h / 2)
        for (p in pts) {
            val dy = matchVertical(a, b, p[0].toInt(), p[1].toInt(), sgn, maxDy) ?: continue
            if (dy < 0.4) { bands[band(p[0], a.w)].add(MAX_M); n++; continue } // très loin (décalage nul)
            bands[band(p[0], a.w)].add((focal * abs(baselineUpM) / dy).coerceAtMost(MAX_M))
            n++
        }
        // Le plus proche de façon fiable : 20e centile (ignore un point aberrant)
        fun near(l: List<Double>): Double? = if (l.size < 4) null else l.sorted()[(l.size * 0.2).toInt()]
        return DepthReading(near(bands[0]), near(bands[1]), near(bands[2]), n)
    }

    /**
     * Décalage vertical (pixels, dans le sens [sgn]) du petit carré autour de (x, y) entre [a] et [b] :
     * recherche le long de la verticale (± 3 px de côté), corrélation normalisée, affinée au dixième de pixel.
     * null si rien ne ressemble assez (zone uniforme, objet caché).
     */
    private fun matchVertical(a: Gray, b: Gray, x: Int, y: Int, sgn: Int, maxDy: Int): Double? {
        val r = 6
        val n = (2 * r + 1) * (2 * r + 1)
        val ta = FloatArray(n)
        var k = 0; var m = 0.0
        for (j in -r..r) for (i in -r..r) { ta[k] = a[x + i, y + j]; m += ta[k]; k++ }
        m /= n
        var va = 0.0
        for (v in ta) va += (v - m) * (v - m)
        if (va / n < 25) return null
        val scores = DoubleArray(maxDy + 4)
        var best = -2.0; var bestDy = 0; var bestDx = 0
        for (d in -3..maxDy) {
            val yy = y + sgn * d
            if (yy - r < 0 || yy + r >= b.h) { if (d >= 0) scores[d] = -2.0; continue }
            var rowBest = -2.0
            for (dx in -3..3) {
                var mb = 0.0; k = 0
                for (j in -r..r) for (i in -r..r) { mb += b[x + dx + i, yy + j]; k++ }
                mb /= n
                var vb = 0.0; var cov = 0.0; k = 0
                for (j in -r..r) for (i in -r..r) {
                    val q = b[x + dx + i, yy + j] - mb
                    vb += q * q; cov += (ta[k] - m) * q; k++
                }
                if (vb < 1e-6) continue
                val c = cov / Math.sqrt(va * vb)
                if (c > rowBest) rowBest = c
                if (c > best) { best = c; bestDy = d; bestDx = dx }
            }
            if (d >= 0) scores[d] = rowBest
        }
        if (best < 0.85) return null
        // Affinage : parabole sur les trois scores autour du meilleur
        if (bestDy in 1 until maxDy) {
            val s0 = scores[bestDy - 1]; val s1 = scores[bestDy]; val s2 = scores[bestDy + 1]
            val den = s0 - 2 * s1 + s2
            if (den < -1e-6) return bestDy + 0.5 * (s0 - s2) / den
        }
        return bestDy.toDouble()
    }

    private fun band(x: Float, w: Int) = when {
        x < w / 3f -> 0
        x < 2 * w / 3f -> 1
        else -> 2
    }
}
