package com.bebopia.app.core.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Boîte dans l'image, en fractions de la largeur et de la hauteur (0..1), centre et taille. */
data class Box(val cx: Double, val cy: Double, val w: Double, val h: Double) {
    /** Recouvrement (0 = aucun, 1 = identiques). */
    fun iou(o: Box): Double {
        val x0 = max(cx - w / 2, o.cx - o.w / 2); val x1 = min(cx + w / 2, o.cx + o.w / 2)
        val y0 = max(cy - h / 2, o.cy - o.h / 2); val y1 = min(cy + h / 2, o.cy + o.h / 2)
        val inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
        val union = w * h + o.w * o.h - inter
        return if (union <= 0) 0.0 else inter / union
    }
}

/**
 * Suivi visuel local, sans l'IA : une dizaine d'images par seconde sur le téléphone.
 *
 * On garde une « empreinte » de la cible (le contenu de sa boîte, en niveaux de gris) et, sur chaque image,
 * on la cherche autour de sa dernière position (corrélation normalisée, insensible aux changements de lumière),
 * à trois échelles pour suivre ses changements de taille. L'empreinte s'adapte doucement quand la cible change d'aspect,
 * tout en restant ancrée sur celle d'origine pour ne pas dériver vers le décor.
 */
class TargetTracker {
    companion object {
        private const val T = 24          // taille de l'empreinte (T x T échantillons)
        const val LOST = 0.45             // en dessous : cible non retrouvée sur cette image
        private val SCALES = doubleArrayOf(0.93, 1.0, 1.07)
    }

    private var tpl = FloatArray(T * T)
    private var anchor = FloatArray(T * T)
    // Position en pixels de l'image de suivi
    private var x = 0.0; private var y = 0.0; private var bw = 0.0; private var bh = 0.0
    private var vx = 0.0; private var vy = 0.0
    private var imgW = 1; private var imgH = 1

    @Volatile var box: Box? = null
        private set
    @Volatile var score = 0.0
        private set

    val active: Boolean get() = box != null

    fun reset() {
        box = null
        score = 0.0
    }

    /** Démarre (ou recale) le suivi sur [b] dans l'image [g]. */
    fun start(g: Gray, b: Box) {
        imgW = g.w; imgH = g.h
        x = b.cx * g.w; y = b.cy * g.h
        bw = max(6.0, b.w * g.w); bh = max(6.0, b.h * g.h)
        vx = 0.0; vy = 0.0
        tpl = patch(g, x, y, bw, bh)
        anchor = tpl.copyOf()
        score = 1.0
        box = b
    }

    /** Le drone a tourné ou incliné la caméra : la cible se décale dans l'image (fractions de largeur / hauteur). */
    fun shift(dx: Double, dy: Double) {
        if (box == null) return
        x += dx * imgW
        y += dy * imgH
        vx = 0.0; vy = 0.0
    }

    /** Cherche la cible dans la nouvelle image. Renvoie sa boîte, ou null si elle n'est pas retrouvée. */
    fun update(g: Gray): Box? {
        if (box == null) return null
        if (g.w != imgW || g.h != imgH) { reset(); return null }
        // Zone de recherche : autour de la position prévue (vitesse constante), assez large pour un mouvement rapide.
        val px = x + vx
        val py = y + vy
        val r = max(16.0, max(bw, bh))
        var best = -2.0; var bx = px; var by = py; var bs = 1.0
        // 1) grossier : au plus 25 x 25 positions, quelle que soit la taille de la cible
        val step = max(3.0, kotlin.math.ceil(r / 12))
        var sy = -r
        while (sy <= r) {
            var sx = -r
            while (sx <= r) {
                for (s in SCALES) {
                    val c = zncc(g, px + sx, py + sy, bw * s, bh * s, 2)
                    if (c > best) { best = c; bx = px + sx; by = py + sy; bs = s }
                }
                sx += step
            }
            sy += step
        }
        // 2) intermédiaire (grandes cibles), puis 3) fin, au pixel, empreinte complète
        var fine = step
        while (fine > 1.0) {
            val sub = if (fine > 3) kotlin.math.ceil(fine / 3) else 1.0
            val cx0 = bx; val cy0 = by
            best = -2.0
            var dy = -fine
            while (dy <= fine) {
                var dx = -fine
                while (dx <= fine) {
                    val c = zncc(g, cx0 + dx, cy0 + dy, bw * bs, bh * bs, if (sub > 1) 2 else 1)
                    if (c > best) { best = c; bx = cx0 + dx; by = cy0 + dy }
                    dx += sub
                }
                dy += sub
            }
            fine = if (sub > 1) sub else 0.0
        }
        score = best
        if (best < LOST) {
            // Pas retrouvée : on garde la dernière position (avec la vitesse) pour la prochaine image.
            vx *= 0.5; vy *= 0.5
            return null
        }
        vx = 0.6 * vx + 0.4 * (bx - x)
        vy = 0.6 * vy + 0.4 * (by - y)
        x = bx.coerceIn(0.0, imgW - 1.0); y = by.coerceIn(0.0, imgH - 1.0)
        bw = (bw * (1 + (bs - 1) * 0.5)).coerceIn(6.0, imgW.toDouble())
        bh = (bh * (1 + (bs - 1) * 0.5)).coerceIn(6.0, imgH.toDouble())
        // L'empreinte suit doucement l'aspect de la cible, rappelée vers celle d'origine.
        if (best > 0.7) {
            val now = patch(g, x, y, bw, bh)
            for (i in tpl.indices) tpl[i] = 0.85f * tpl[i] + 0.1f * now[i] + 0.05f * anchor[i]
        }
        val b = Box(x / imgW, y / imgH, bw / imgW, bh / imgH)
        box = b
        return b
    }

    /** Contenu de la boîte, rééchantillonné en T x T, centré et normalisé. */
    private fun patch(g: Gray, cx: Double, cy: Double, w: Double, h: Double): FloatArray {
        val out = FloatArray(T * T)
        var k = 0
        for (j in 0 until T) for (i in 0 until T) {
            out[k++] = g.sample((cx - w / 2 + (i + 0.5) * w / T).toFloat(), (cy - h / 2 + (j + 0.5) * h / T).toFloat())
        }
        return normalize(out)
    }

    private fun normalize(a: FloatArray): FloatArray {
        var m = 0.0
        for (v in a) m += v
        m /= a.size
        var s = 0.0
        for (v in a) s += (v - m) * (v - m)
        val sd = sqrt(s / a.size).coerceAtLeast(1.0)
        for (i in a.indices) a[i] = ((a[i] - m) / sd).toFloat()
        return a
    }

    /** Corrélation normalisée entre l'empreinte et la zone (cx, cy, w, h), un échantillon sur [stride]. */
    private fun zncc(g: Gray, cx: Double, cy: Double, w: Double, h: Double, stride: Int): Double {
        var n = 0
        var sum = 0.0; var sum2 = 0.0; var dot = 0.0; var st = 0.0; var st2 = 0.0
        var j = 0
        val x0 = cx - w / 2; val y0 = cy - h / 2
        val sx = w / T; val sy = h / T
        while (j < T) {
            var i = 0
            val yy = (y0 + (j + 0.5) * sy).toFloat()
            while (i < T) {
                val v = g.sample((x0 + (i + 0.5) * sx).toFloat(), yy).toDouble()
                val t = tpl[j * T + i]
                sum += v; sum2 += v * v; dot += v * t; st += t; st2 += t * t
                n++
                i += stride
            }
            j += stride
        }
        val mean = sum / n
        val varr = sum2 / n - mean * mean
        if (varr < 4.0) return -1.0 // zone uniforme : pas de cible là
        val tm = st / n
        val tv = st2 / n - tm * tm
        if (tv < 1e-6) return -1.0
        return (dot / n - mean * tm) / sqrt(varr * tv)
    }
}
