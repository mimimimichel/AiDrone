package com.bebopia.app

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.ai.SceneAnalyzer
import com.bebopia.app.core.ai.SceneMemory
import com.bebopia.app.core.ai.Surveyor
import com.bebopia.app.core.ai.ViewReport
import com.bebopia.app.core.norm180
import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.OpticalHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Drone qui tourne en continu (vitesse de rotation) avec une vraie boussole, comme le Mavic. */
class SpinDrone : DroneBase(Limits(maxAltitudeM = 10.0, maxDistanceM = 50.0)) {
    override val name = "spin"
    @Volatile private var yaw = 0.0
    @Volatile private var rate = 0.0
    @Volatile private var until = 0L
    @Volatile private var last = System.currentTimeMillis()
    @Volatile var moves = 0
    override val supportsVelocity = true
    override val reliableCompass = true
    init { flying = true; indoor = true }
    @Synchronized private fun step() {
        val now = System.currentTimeMillis()
        if (now < until) yaw += rate * (now - last) / 1000.0
        last = now
    }
    override fun compass(): Double { step(); return norm180(yaw) }
    override fun lookHeading() = compass()
    override fun velocity(forward: Double, right: Double, up: Double, yawRate: Double, holdMs: Long) { step(); rate = yawRate; until = System.currentTimeMillis() + holdMs }
    override fun stopVelocity() { step(); until = 0 }
    override fun connect() {}
    override fun disconnect() {}
    override fun doTakeoff() {}
    override fun doLand() {}
    override fun doEmergency() {}
    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) { moves++; yaw += Math.toDegrees(yawRad) }
    override fun doTilt(degrees: Int) {}
}

class SweepTest {
    private val analyzer = SceneAnalyzer { _, _, _, _ -> ViewReport(true, 3.0, null, emptyList(), null, null, null, "pièce") }

    @Test
    fun fullTurnIsOneSmoothRotation() {
        val d = SpinDrone()
        val headings = ArrayList<Double>()
        val sv = Surveyor(d, { headings.add(d.compass()); "img" }, analyzer, SceneMemory { true }, 72.0, false, { false }, {})
        val t0 = System.currentTimeMillis()
        val r = sv.survey(null, lookDown = false)
        val s = (System.currentTimeMillis() - t0) / 1000.0
        assertTrue(r.text, r.text.isNotBlank())
        assertEquals("8 vues", 8, headings.size)
        assertEquals("aucun arrêt pas à pas", 0, d.moves)
        assertTrue("rapide : $s s", s < 14)
        assertTrue("revenu face au départ : ${d.compass()}", abs(d.compass()) < 15)
    }

    @Test
    fun glanceLooksAheadOnly() {
        val d = SpinDrone()
        val headings = ArrayList<Double>()
        val sv = Surveyor(d, { headings.add(d.compass()); "img" }, analyzer, SceneMemory { true }, 72.0, false, { false }, {})
        sv.survey(null, lookDown = false, arcDeg = 120.0)
        assertEquals(3, headings.size)
        assertEquals(-60.0, headings[0], 8.0)
        assertEquals(60.0, headings[2], 8.0)
        assertTrue("revenu face au départ : ${d.compass()}", abs(d.compass()) < 8)
    }

    private fun scene(sx: Double): Gray {
        val w = 160; val h = 90
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x - sx; val v = y.toDouble()
            px[y * w + x] = (128 + 50 * sin(u * 0.31 + sin(v * 0.13) * 2) + 40 * sin(v * 0.27 + sin(u * 0.17) * 3)).toFloat()
        }
        return Gray(w, h, px)
    }

    @Test
    fun holdBringsDroneBackAfterTurn() {
        val hold = OpticalHold(72.0)
        repeat(4) { i -> hold.update(scene(0.0), 0.0, 0.0, 1000L + i * 150) }
        // Il tourne sur place (le décor change complètement) en glissant vers la gauche
        hold.suspend()
        // De retour face au même décor, décalé de 8 px : il a glissé de côté pendant le tour
        val c = hold.update(scene(8.0), 2.0, 0.0, 12_000)
        assertNotNull(c)
        assertTrue("le ramène vers la droite : ${c!!.right}", c.right > 0.05)
    }
}
