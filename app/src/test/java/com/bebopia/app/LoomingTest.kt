package com.bebopia.app

import com.bebopia.app.core.ai.Agent
import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.LoomingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

class LoomingTest {
    private val w = 160
    private val h = 90

    private fun tex(u: Double, v: Double) = (128 + 50 * sin(u * 0.31 + sin(v * 0.13) * 2) + 40 * sin(v * 0.27 + sin(u * 0.17) * 3) +
        25 * sin((u + v) * 0.11)).toFloat()

    /** Image : zoom [zoomCore] au centre, zoom [zoomSides] sur les bords (x < 25 % ou > 75 %), décalage [shift]. */
    private fun frame(zoomCore: Double, zoomSides: Double = 1.0, shift: Double = 0.0): Gray {
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val side = x < w * 0.25 || x > w * 0.75
            val z = if (side) zoomSides else zoomCore
            val u = (x - w / 2.0) / z + w / 2.0 - shift
            val v = (y - h / 2.0) / z + h / 2.0
            px[y * w + x] = tex(u, v)
        }
        return Gray(w, h, px)
    }

    private fun run(frames: (Int) -> Gray, n: Int = 12): Boolean {
        val d = LoomingDetector(1.6)
        var danger = false
        for (i in 0 until n) if (d.update(frames(i), 1000L + i * 80).danger) danger = true
        return danger
    }

    @Test
    fun obstacleStraightAheadStops() {
        // Contact dans ~0,8 s : grossit de exp(0,08/0,8) par image
        assertTrue(run({ i -> frame(exp(i * 0.08 / 0.8)) }))
    }

    @Test
    fun sideEdgesGrowingDoNotStop() {
        // Passage de porte : les montants (bords de l'image) grossissent vite, le centre (pièce d'à côté) presque pas
        assertFalse(run({ i -> frame(exp(i * 0.08 / 6.0), zoomSides = exp(i * 0.08 / 0.6)) }))
    }

    @Test
    fun driftSidewaysDoesNotStop() {
        assertFalse(run({ i -> frame(1.0, shift = i * 1.5) }))
    }

    @Test
    fun voiceSafetyWords() {
        assertEquals("stop", Agent.urgentWordsAction("Stop !", true))
        assertEquals("stop", Agent.urgentWordsAction("arrête-toi", true))
        assertEquals("pause", Agent.urgentWordsAction("pause", true))
        assertNull(Agent.urgentWordsAction("ne t'arrête pas", true))
        assertNull(Agent.urgentWordsAction("si tu vois un mur devant toi tu t'arrêtes avant", true))
        // Se poser : en direct, c'est l'IA qui choisit l'endroit ; « tout de suite » reste immédiat
        assertNull(Agent.urgentWordsAction("pose-toi", true))
        assertEquals("land", Agent.urgentWordsAction("pose-toi", false))
        assertEquals("land_now", Agent.urgentWordsAction("atterris tout de suite", true))
        assertNull(Agent.urgentWordsAction("n'atterris pas ici", false))
    }
}
