package com.bebopia.app.core.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/** Image en niveaux de gris (0..255), ligne par ligne. */
class Gray(val w: Int, val h: Int, val px: FloatArray) {
    operator fun get(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** Valeur interpolée entre les pixels (bords prolongés). */
    fun sample(x: Float, y: Float): Float {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val a = this[x0, y0]
        val b = this[x0 + 1, y0]
        val c = this[x0, y0 + 1]
        val d = this[x0 + 1, y0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Image deux fois plus petite (moyenne de 2×2 pixels). */
    fun half(): Gray {
        val nw = maxOf(1, w / 2)
        val nh = maxOf(1, h / 2)
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            out[y * nw + x] = (this[2 * x, 2 * y] + this[2 * x + 1, 2 * y] + this[2 * x, 2 * y + 1] + this[2 * x + 1, 2 * y + 1]) / 4f
        }
        return Gray(nw, nh, out)
    }

    fun pyramid(levels: Int): List<Gray> {
        val p = ArrayList<Gray>(levels)
        p.add(this)
        while (p.size < levels) p.add(p.last().half())
        return p
    }
}

/** Suivi de points d'une image à la suivante (Lucas-Kanade pyramidal). */
object Tracker {
    private const val R = 4 // demi-fenêtre : 9×9 pixels

    /** Plus petite valeur propre du tenseur de structure autour de (x, y) : « ce coin est-il bien texturé ? ». */
    fun cornerScore(g: Gray, x: Int, y: Int, r: Int = 2): Float {
        var xx = 0f; var xy = 0f; var yy = 0f
        for (dy in -r..r) for (dx in -r..r) {
            val ix = (g[x + dx + 1, y + dy] - g[x + dx - 1, y + dy]) / 2f
            val iy = (g[x + dx, y + dy + 1] - g[x + dx, y + dy - 1]) / 2f
            xx += ix * ix; xy += ix * iy; yy += iy * iy
        }
        val n = ((2 * r + 1) * (2 * r + 1)).toFloat()
        xx /= n; xy /= n; yy /= n
        val tr = xx + yy
        val det = xx * yy - xy * xy
        return tr / 2 - sqrt(maxOf(0f, tr * tr / 4 - det))
    }

    /** Points bien texturés, répartis sur une grille, dans le rectangle donné. */
    fun pickPoints(g: Gray, x0: Int, y0: Int, x1: Int, y1: Int, step: Int, minScore: Float): List<FloatArray> {
        val pts = ArrayList<FloatArray>()
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                // Meilleur pixel de la case
                var best = 0f; var bx = x; var by = y
                for (dy in 0 until step step 2) for (dx in 0 until step step 2) {
                    val s = cornerScore(g, x + dx, y + dy)
                    if (s > best) { best = s; bx = x + dx; by = y + dy }
                }
                if (best >= minScore) pts.add(floatArrayOf(bx.toFloat(), by.toFloat()))
                x += step
            }
            y += step
        }
        return pts
    }

    /**
     * Où le point (x, y) de [prev] se trouve dans [next]. null si perdu.
     * [prev] et [next] sont des pyramides de même taille.
     */
    fun track(prev: List<Gray>, next: List<Gray>, x: Float, y: Float): FloatArray? {
        var gx = 0f
        var gy = 0f
        for (level in prev.indices.reversed()) {
            val scale = (1 shl level).toFloat()
            val px = x / scale
            val py = y / scale
            val i = prev[level]
            val j = next[level]
            // Gradients de l'image de départ dans la fenêtre
            var gxx = 0f; var gxy = 0f; var gyy = 0f
            val n = (2 * R + 1) * (2 * R + 1)
            val ixs = FloatArray(n); val iys = FloatArray(n); val iv = FloatArray(n)
            var k = 0
            for (dy in -R..R) for (dx in -R..R) {
                val sx = px + dx; val sy = py + dy
                val ix = (i.sample(sx + 1, sy) - i.sample(sx - 1, sy)) / 2f
                val iy = (i.sample(sx, sy + 1) - i.sample(sx, sy - 1)) / 2f
                ixs[k] = ix; iys[k] = iy; iv[k] = i.sample(sx, sy)
                gxx += ix * ix; gxy += ix * iy; gyy += iy * iy
                k++
            }
            val det = gxx * gyy - gxy * gxy
            if (det < 1e-3f * n) return null // zone uniforme : impossible à suivre
            var vx = 0f
            var vy = 0f
            for (iter in 0 until 8) {
                var bx = 0f; var by = 0f
                k = 0
                for (dy in -R..R) for (dx in -R..R) {
                    val diff = iv[k] - j.sample(px + dx + gx + vx, py + dy + gy + vy)
                    bx += diff * ixs[k]; by += diff * iys[k]
                    k++
                }
                val ex = (gyy * bx - gxy * by) / det
                val ey = (gxx * by - gxy * bx) / det
                vx += ex; vy += ey
                if (abs(ex) < 0.01f && abs(ey) < 0.01f) break
            }
            if (level > 0) {
                gx = 2 * (gx + vx); gy = 2 * (gy + vy)
            } else {
                gx += vx; gy += vy
            }
        }
        val nx = x + gx
        val ny = y + gy
        val g0 = next[0]
        if (nx < 1 || ny < 1 || nx > g0.w - 2 || ny > g0.h - 2) return null
        // Vérification : la fenêtre doit encore se ressembler.
        var err = 0f
        val i0 = prev[0]
        for (dy in -R..R) for (dx in -R..R) err += abs(i0.sample(x + dx, y + dy) - g0.sample(nx + dx, ny + dy))
        if (err / ((2 * R + 1) * (2 * R + 1)) > 18f) return null
        return floatArrayOf(nx, ny)
    }
}

/**
 * Agrandissement (zoom) qui fait passer les points p aux points q : q ≈ a·p + t (similitude).
 * Renvoie l'échelle |a| et le nombre de points gardés, après rejet des points aberrants.
 */
fun similarityScale(p: List<FloatArray>, q: List<FloatArray>): Pair<Double, Int>? {
    var idx = p.indices.toList()
    var scale = 1.0
    repeat(3) { round ->
        if (idx.size < 6) return null
        var pcx = 0.0; var pcy = 0.0; var qcx = 0.0; var qcy = 0.0
        for (i in idx) { pcx += p[i][0]; pcy += p[i][1]; qcx += q[i][0]; qcy += q[i][1] }
        val n = idx.size.toDouble()
        pcx /= n; pcy /= n; qcx /= n; qcy /= n
        var re = 0.0; var im = 0.0; var den = 0.0
        for (i in idx) {
            val ax = p[i][0] - pcx; val ay = p[i][1] - pcy
            val bx = q[i][0] - qcx; val by = q[i][1] - qcy
            // (b) · conj(a)
            re += bx * ax + by * ay
            im += by * ax - bx * ay
            den += ax * ax + ay * ay
        }
        if (den < 1e-6) return null
        val ar = re / den; val ai = im / den
        scale = hypot(ar, ai)
        if (round == 2) return scale to idx.size
        // Écarts de chaque point au modèle ; on écarte ceux qui ne suivent pas (objets qui bougent, erreurs)
        val res = idx.map { i ->
            val ax = p[i][0] - pcx; val ay = p[i][1] - pcy
            val mx = ar * ax - ai * ay + qcx; val my = ar * ay + ai * ax + qcy
            i to hypot(q[i][0] - mx, q[i][1] - my)
        }
        val med = res.map { it.second }.sorted()[res.size / 2]
        val keep = res.filter { it.second <= maxOf(0.3, 2.5 * med) }.map { it.first }
        idx = keep
    }
    return scale to idx.size
}

/**
 * Détecteur d'obstacle par « effet de zoom » : quand le drone avance vers un objet,
 * l'objet grossit dans l'image d'autant plus vite qu'il est proche.
 * Le temps avant contact vaut à peu près 1 / (vitesse de grossissement).
 *
 * On mesure ce grossissement au centre de l'image (là où le drone va), sur la zone entière
 * et sur 4 morceaux (pour un poteau ou un objet plus petit que la zone).
 */
class LoomingDetector(
    /** En dessous de ce temps avant contact (secondes), c'est un danger. */
    var ttcLimitS: Double = 2.0,
) {
    class Result(val ttcS: Double?, val danger: Boolean, val points: Int)

    private var prev: List<Gray>? = null
    private var prevT = 0L
    private var rate = 0.0       // grossissement lissé, par seconde
    private var dangerFrames = 0

    fun reset() {
        prev = null
        rate = 0.0
        dangerFrames = 0
    }

    fun update(frame: Gray, tMs: Long): Result {
        val pyr = frame.pyramid(3)
        val p = prev
        val dt = (tMs - prevT) / 1000.0
        prev = pyr
        prevT = tMs
        if (p == null || p[0].w != frame.w || p[0].h != frame.h || dt <= 0.0 || dt > 0.5) {
            rate = 0.0
            dangerFrames = 0
            return Result(null, false, 0)
        }
        val w = frame.w
        val h = frame.h
        // Zone devant le drone : le cœur de l'image, là où il va. Les bords (cadres de porte, meubles sur le côté)
        // grossissent aussi quand on avance mais ne sont pas sur le trajet. Sans le bas (le sol qui défile).
        val x0 = (w * 0.33).toInt(); val x1 = (w * 0.67).toInt()
        val y0 = (h * 0.15).toInt(); val y1 = (h * 0.75).toInt()
        val step = maxOf(4, w / 32)
        val from = Tracker.pickPoints(p[0], x0, y0, x1, y1, step, minScore = 6f)
        val a = ArrayList<FloatArray>()
        val b = ArrayList<FloatArray>()
        for (pt in from) {
            val q = Tracker.track(p, pyr, pt[0], pt[1]) ?: continue
            a.add(pt); b.add(q)
        }
        var best = 0.0
        var used = 0
        fun consider(sel: List<Int>, minPts: Int) {
            if (sel.size < minPts) return
            val r = similarityScale(sel.map { a[it] }, sel.map { b[it] }) ?: return
            if (r.second < minPts) return
            val g = ln(r.first) / dt
            if (g > best) best = g
            used = maxOf(used, r.second)
        }
        consider(a.indices.toList(), 10)
        // Trois bandes verticales dans le cœur (poteau, personne, pied de table droit devant), assez de points chacune
        val bw = (x1 - x0) / 3f
        for (c in 0 until 3) consider(a.indices.filter { ((a[it][0] - x0) / bw).toInt() == c }, 9)
        // Lissage : il faut que ça dure (3 images de suite, ~0,25 s) pour déclencher.
        rate = 0.5 * rate + 0.5 * best
        val ttc = if (rate > 1e-3) 1.0 / rate else null
        val danger = ttc != null && ttc < ttcLimitS
        dangerFrames = if (danger) dangerFrames + 1 else 0
        return Result(ttc, dangerFrames >= 3, used)
    }

    companion object {
        /** Pour les tests : zoom d'une image autour de son centre. */
        fun zoomed(src: Gray, factor: Double, shiftX: Float = 0f, shiftY: Float = 0f): Gray {
            val out = FloatArray(src.w * src.h)
            val cx = src.w / 2f; val cy = src.h / 2f
            for (y in 0 until src.h) for (x in 0 until src.w) {
                val sx = cx + (x - cx - shiftX) / factor.toFloat()
                val sy = cy + (y - cy - shiftY) / factor.toFloat()
                out[y * src.w + x] = src.sample(sx, sy)
            }
            return Gray(src.w, src.h, out)
        }

        @Suppress("unused")
        fun expectedScale(ttcS: Double, dtS: Double) = exp(dtS / ttcS)
    }
}
