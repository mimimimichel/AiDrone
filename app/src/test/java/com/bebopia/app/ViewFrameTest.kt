package com.bebopia.app

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.norm180
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drone dont la caméra peut regarder ailleurs que son nez (comme le Bebop avec le casque). */
class LookDrone : DroneBase(Limits(maxDistanceM = 50.0)) {
    override val name = "test"
    var look = 0.0
    var sent = DoubleArray(2)
    override fun connect() {}
    override fun disconnect() {}
    override fun doTakeoff() {}
    override fun doLand() {}
    override fun doEmergency() {}
    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) { sent = doubleArrayOf(forward, right) }
    override fun doTilt(degrees: Int) {}
    override fun lookHeading() = norm180(compass() + look)
}

class ViewFrameTest {
    @Test fun rightIsRightOfTheViewWhenOrderWasGiven() {
        val d = LookDrone()
        d.takeoff()
        d.look = 90.0                 // le pilote regarde à droite…
        val vue = d.lookHeading()     // … et dit « à droite »
        d.look = 0.0                  // puis tourne la tête avant l'exécution
        d.moveRef = vue
        d.move(0.0, 1.0, 0.0, 0.0)
        d.moveRef = null
        // À droite de la vue à 90°, c'est derrière le nez du drone
        assertEquals(-1.0, d.sent[0], 1e-6)
        assertEquals(0.0, d.sent[1], 1e-6)
        val p = d.positionNE()
        assertEquals(-1.0, p.first, 1e-6)
        assertEquals(0.0, p.second, 1e-6)
    }

    @Test fun withoutHeadsetNothingChanges() {
        val d = LookDrone()
        d.takeoff()
        d.move(0.5, 0.3, 0.0, 0.0)
        assertEquals(0.5, d.sent[0], 1e-6)
        assertEquals(0.3, d.sent[1], 1e-6)
    }
}
