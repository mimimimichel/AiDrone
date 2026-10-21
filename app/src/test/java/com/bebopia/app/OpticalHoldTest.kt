package com.bebopia.app

import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.OpticalHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.tan

class OpticalHoldTest {
    private val w = 160
    private val h = 90

    /** Décor irrégulier, vu décalé de (sx, sy) pixels et zoomé de [zoom] autour du centre. */
    private fun scene(sx: Double, sy: Double, zoom: Double = 1.0): Gray {
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x - w / 2.0) / zoom + w / 2.0 - sx
            val v = (y - h / 2.0) / zoom + h / 2.0 - sy
            px[y * w + x] = (128 + 50 * sin(u * 0.31 + sin(v * 0.13) * 2) + 40 * sin(v * 0.27 + sin(u * 0.17) * 3) +
                25 * sin((u + v) * 0.11)).toFloat()
        }
        return Gray(w, h, px)
    }

    private fun run(hold: OpticalHold, frames: List<Gray>, yaws: List<Double> = frames.map { 0.0 }): OpticalHold.Command? {
        var c: OpticalHold.Command? = null
        frames.forEachIndexed { i, g -> c = hold.update(g, yaws[i], 0.0, 1000L + i * 150) }
        return c
    }

    @Test
    fun driftLeftCommandsRight() {
        // Le décor glisse vers la droite : le drone a dérivé vers la gauche
        val c = run(OpticalHold(72.0), (0..6).map { scene(it * 1.0, 0.0) })
        assertNotNull(c)
        assertTrue("droite attendue : ${c!!.right}", c.right > 0.03)
        assertEquals(0.0, c.forward, 0.03)
    }

    @Test
    fun forwardDriftCommandsBack() {
        // Le décor grossit : le drone a avancé
        val c = run(OpticalHold(72.0), (0..6).map { scene(0.0, 0.0, 1.0 + it * 0.012) })
        assertNotNull(c)
        assertTrue("recul attendu : ${c!!.forward}", c.forward < -0.03)
        assertEquals(0.0, c.right, 0.03)
    }

    @Test
    fun yawAloneIsNotDrift() {
        // Le drone tourne à droite : le décor glisse vers la gauche, ce n'est pas une dérive
        val f = (w / 2.0) / tan(Math.toRadians(36.0))
        val yaws = (0..6).map { it * 0.6 }
        val frames = yaws.map { y -> scene(-f * tan(Math.toRadians(y)), 0.0) }
        val c = run(OpticalHold(72.0), frames, yaws)
        assertNotNull(c)
        assertEquals(0.0, c!!.right, 0.03)
    }

    @Test
    fun stillSceneNoCommand() {
        val c = run(OpticalHold(72.0), (0..6).map { scene(0.0, 0.0) })
        assertNotNull(c)
        assertEquals(0.0, c!!.right, 0.01)
        assertEquals(0.0, c.forward, 0.01)
    }

    @Test
    fun closedLoopHoldsAgainstWind() {
        // Décor à 2 m ; vent latéral de 0,15 m/s vers la gauche ; le drone répond à la commande en ~0,5 s
        val f = (w / 2.0) / tan(Math.toRadians(36.0))
        val hold = OpticalHold(72.0)
        var x = 0.0 // position latérale (m), + = droite
        var v = 0.0
        val dt = 0.15
        var maxOff = 0.0
        for (i in 0 until 200) {
            val g = scene(-f * x / 2.0, 0.0) // drone à droite -> décor vers la gauche
            val c = hold.update(g, 0.0, 0.0, 1000L + (i * dt * 1000).toLong())
            val cmd = c?.right ?: 0.0
            v += (cmd - 0.15 - v) * (dt / 0.5)
            x += v * dt
            if (i > 60) maxOff = maxOf(maxOff, kotlin.math.abs(x))
        }
        assertTrue("dérive contenue : ${"%.2f".format(maxOff)} m", maxOff < 0.35)
    }
}
