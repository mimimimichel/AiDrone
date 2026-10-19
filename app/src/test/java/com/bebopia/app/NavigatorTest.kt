package com.bebopia.app

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.ai.Navigator
import com.bebopia.app.core.ai.Shots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Drone de test : les déplacements sont instantanés, la position estimée suit. */
class FastDrone(limits: Limits, private val compass0: Double = 30.0) : DroneBase(limits) {
    override val name = "test"
    override fun connect() {}
    override fun disconnect() {}
    override fun doTakeoff() {}
    override fun doLand() {}
    override fun doEmergency() {}
    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {}
    override fun doTilt(degrees: Int) {}
    override fun pause(seconds: Double) {}
    override fun takeoffHeading() = compass0
    var alt = 4.0
    override fun altitude() = alt
}

class NavigatorTest {
    private fun setup(): Pair<FastDrone, Navigator> {
        val d = FastDrone(Limits(maxAltitudeM = 30.0, maxDistanceM = 100.0))
        d.takeoff()
        val n = Navigator(d, simulation = false, shouldStop = { false }, report = { println(it) }, save = {})
        n.tick()
        return d to n
    }

    @Test fun markAndReturnToPoint() {
        val (d, n) = setup()
        d.move(8.0, 0.0, 0.0, 0.0, maxStep = 20.0)
        d.move(0.0, 0.0, 0.0, 90.0, maxStep = 20.0)
        d.move(5.0, 0.0, 0.0, 0.0, maxStep = 20.0)
        n.tick()
        println(n.markPoint("arbre"))
        d.returnHome()
        val r = n.goToPoint("arbre")
        println(r)
        assertTrue(r, r.startsWith("arrivé"))
        val p = d.positionNE()
        // Point = 8 m devant puis 5 m à droite, avec un cap de départ de 30°
        val c = Math.toRadians(30.0)
        val en = 8 * Math.cos(c) - 5 * Math.sin(c)
        val ee = 8 * Math.sin(c) + 5 * Math.cos(c)
        assertEquals(en, p.first, 0.3)
        assertEquals(ee, p.second, 0.3)
    }

    @Test fun retraceComesHome() {
        val (d, n) = setup()
        repeat(4) { d.move(3.0, 0.0, 0.0, 0.0, maxStep = 20.0); n.tick() }
        d.move(0.0, 0.0, 0.0, 90.0, maxStep = 20.0)
        repeat(3) { d.move(2.0, 0.0, 0.0, 0.0, maxStep = 20.0); n.tick() }
        val r = n.retrace(land = false)
        println(r)
        val p = d.positionNE()
        assertTrue(r, hypot(p.first, p.second) < 0.3)
    }

    @Test fun goToLookUsesCameraAngle() {
        val (d, n) = setup()
        d.tiltCamera(-45.0) // 4 m d'altitude, 45° : point visé à 4 m devant
        val r = n.goToLook()
        println(r)
        val p = d.positionNE()
        assertEquals(4.0 - 0.3, hypot(p.first, p.second), 0.3)
    }

    @Test fun goToLookRefusesHorizon() {
        val (d, n) = setup()
        d.tiltCamera(0.0)
        assertTrue(n.goToLook().startsWith("refusé"))
    }

    @Test fun orbitWithoutVelocityComesBack() {
        val (d, _) = setup()
        val s = Shots(d, simulation = false, shouldStop = { false }, report = { println(it) }, pathCheck = { null })
        val r = s.film("orbite", 3.0, record = false)
        println(r)
        val p = d.positionNE()
        assertTrue(r, hypot(p.first, p.second) < 0.5)
    }
}
