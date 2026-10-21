package com.bebopia.app

import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.Parallax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan
import kotlin.random.Random

class ParallaxTest {
    private val W = 320
    private val H = 180
    private val hfov = 72.0
    private val focal = (W / 2.0) / tan(Math.toRadians(hfov / 2))
    // Textures irrégulières (taches de 4 px) pour chaque surface
    private val tex = Array(3) { k -> val r = Random(k + 3); Array(80) { FloatArray(100) { 30f + r.nextFloat() * 200f } } }
    private fun texel(k: Int, x: Double, y: Double): Float {
        val i = Math.floorMod(Math.floor(y / 4).toInt(), 80); val j = Math.floorMod(Math.floor(x / 4).toInt(), 100)
        return tex[k][i][j]
    }

    /** Scène : gauche à [zl] m, centre à [zc] m, droite à [zr] m ; [up] = de combien la caméra est montée. */
    private fun render(zl: Double, zc: Double, zr: Double, up: Double): Gray {
        val px = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val (k, z) = when { x < W / 3 -> 0 to zl; x < 2 * W / 3 -> 1 to zc; else -> 2 to zr }
            val dy = focal * up / z // la scène descend dans l'image quand la caméra monte
            px[y * W + x] = texel(k, x.toDouble(), y - dy)
        }
        return Gray(W, H, px)
    }

    @Test fun measuresDistancesFromASmallClimb() {
        val a = render(1.5, 3.0, 12.0, 0.0)
        val b = render(1.5, 3.0, 12.0, 0.25)
        val d = Parallax.depth(a, b, 0.25, hfov)
        println("mesuré : $d")
        assertNotNull(d.left); assertNotNull(d.center); assertNotNull(d.right)
        assertEquals(1.5, d.left!!, 0.25)
        assertEquals(3.0, d.center!!, 0.5)
        assertTrue("loin : ${d.right}", d.right!! > 7)
    }

    @Test fun worksWhenDescending() {
        val a = render(2.0, 2.0, 2.0, 0.0)
        val b = render(2.0, 2.0, 2.0, -0.3)
        val d = Parallax.depth(a, b, -0.3, hfov)
        println("en descendant : $d")
        assertEquals(2.0, d.center!!, 0.3)
    }
}
