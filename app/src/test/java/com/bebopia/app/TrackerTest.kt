package com.bebopia.app

import com.bebopia.app.core.vision.Box
import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.TargetTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class TrackerTest {
    private val W = 320
    private val H = 180
    private val bg = FloatArray(W * H).also { val r = Random(1); for (i in it.indices) it[i] = 90f + r.nextFloat() * 60f }
    // Cible : un motif contrasté (damier irrégulier) de 30 x 40 pixels
    // Cible : taches irrégulières contrastées (comme un vêtement, un animal), 30 x 40 pixels
    private val blocks = Random(7).let { r -> Array(9) { FloatArray(7) { 20f + r.nextFloat() * 210f } } }
    private val tex = Array(40) { j -> FloatArray(30) { i -> blocks[j / 5][i / 5] } }

    private fun frame(cx: Double, cy: Double, scale: Double = 1.0, light: Float = 1f, offset: Float = 0f): Gray {
        val px = bg.copyOf()
        val w = 30 * scale; val h = 40 * scale
        for (y in 0 until H) for (x in 0 until W) {
            val u = (x - (cx - w / 2)) / scale; val v = (y - (cy - h / 2)) / scale
            if (u >= 0 && v >= 0 && u < 30 && v < 40) px[y * W + x] = tex[v.toInt()][u.toInt()]
            px[y * W + x] = px[y * W + x] * light + offset
        }
        return Gray(W, H, px)
    }

    @Test fun followsMovingTarget() {
        val t = TargetTracker()
        var cx = 100.0; var cy = 90.0
        t.start(frame(cx, cy), Box(cx / W, cy / H, 30.0 / W, 40.0 / H))
        repeat(40) { k ->
            cx += 4.0; cy += if (k % 2 == 0) 1.5 else -1.0
            val light = 1f + 0.01f * k   // la lumière change peu à peu
            val b = t.update(frame(cx, cy, 1.0 + k * 0.004, light, k.toFloat()))
            println("k=$k vrai=(%.1f, %.1f) suivi=(%.1f, %.1f) score=%.2f".format(cx, cy, (b?.cx ?: -1.0) * W, (b?.cy ?: -1.0) * H, t.score))
            assertNotNull("perdu à l'image $k (score ${t.score})", b)
            assertEquals("x à l'image $k", cx, b!!.cx * W, 3.0)
            assertEquals("y à l'image $k", cy, b.cy * H, 3.0)
        }
        println("score final %.2f, taille %.0f px (vraie %.0f)".format(t.score, t.box!!.h * H, 40 * (1 + 39 * 0.004)))
    }

    @Test fun speed() {
        val t = TargetTracker()
        val f = frame(160.0, 90.0)
        t.start(f, Box(0.5, 0.5, 30.0 / W, 40.0 / H))
        repeat(50) { t.update(f) }
        val t0 = System.nanoTime()
        repeat(200) { t.update(f) }
        println("suivi : %.2f ms par image (ordinateur)".format((System.nanoTime() - t0) / 1e6 / 200))
        // Grande cible (de près) : le coût doit rester du même ordre
        val big = TargetTracker()
        big.start(f, Box(0.5, 0.5, 0.5, 0.8))
        repeat(20) { big.update(f) }
        val t1 = System.nanoTime()
        repeat(100) { big.update(f) }
        println("grande cible : %.2f ms par image".format((System.nanoTime() - t1) / 1e6 / 100))
    }

    @Test fun reportsLostWhenTargetGone() {
        val t = TargetTracker()
        t.start(frame(160.0, 90.0), Box(0.5, 0.5, 30.0 / W, 40.0 / H))
        val empty = Gray(W, H, bg.copyOf())
        assertNull(t.update(empty))
    }

    @Test fun shiftCompensatesDroneTurn() {
        val t = TargetTracker()
        t.start(frame(160.0, 90.0), Box(0.5, 0.5, 30.0 / W, 40.0 / H))
        // Le drone tourne : la cible saute de 60 px vers la gauche ; on prévient le suivi.
        t.shift(-60.0 / W, 0.0)
        val b = t.update(frame(100.0, 90.0))
        assertNotNull(b)
        assertEquals(100.0, b!!.cx * W, 2.0)
    }
}
