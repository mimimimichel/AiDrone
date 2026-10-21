package com.bebopia.app

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.norm180
import com.bebopia.app.core.ai.Detection
import com.bebopia.app.core.ai.DoorPasser
import com.bebopia.app.core.ai.HouseSearch
import com.bebopia.app.core.ai.ObjectDetector
import com.bebopia.app.core.ai.SceneAnalyzer
import com.bebopia.app.core.ai.SceneMemory
import com.bebopia.app.core.ai.Surveyor
import com.bebopia.app.core.ai.ViewItem
import com.bebopia.app.core.ai.ViewReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/** Drone de test, déplacements instantanés, altitude suivie. */
class RoomDrone : DroneBase(Limits(maxAltitudeM = 10.0, maxDistanceM = 50.0)) {
    override val name = "test"
    override fun connect() {}
    override fun disconnect() {}
    override fun doTakeoff() {}
    override fun doLand() {}
    override fun doEmergency() {}
    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {}
    override fun doTilt(degrees: Int) {}
    override fun pause(seconds: Double) {}
}

/**
 * Maison simulée (mètres nord/est depuis le décollage) : pièce 1 de -2 à 3 m au nord, mur à 3 m avec une porte
 * de 0,9 m centrée sur e = 1 ; pièce 2 au-delà, avec des clés à (6, -1).
 */
class House {
    val doorN = 3.0; val doorE = 1.0; val doorW = 0.9
    val keys = 6.0 to -1.0
    val hfov = 72.0
    fun room(n: Double) = if (n < doorN) 1 else 2

    fun ray(n0: Double, e0: Double, hDeg: Double): Double {
        val dn = Math.cos(Math.toRadians(hDeg)); val de = Math.sin(Math.toRadians(hDeg))
        var t = 0.0
        while (t < 15) {
            t += 0.02
            val n = n0 + dn * t; val e = e0 + de * t
            if (e < -3 || e > 3 || n < -2 || n > 8) return t
            val crossed = (n0 < doorN) != (n < doorN)
            if (crossed && abs(n - doorN) < 0.03 && abs(e - doorE) > doorW / 2) return t
        }
        return 15.0
    }

    /** « Photo » : position et cap au moment de la prise de vue. */
    fun eyes(d: DroneBase) = com.bebopia.app.core.ai.Eyes { val p = d.positionNE(); "${p.first};${p.second};${d.lookHeading()}" }
    private fun pose(j: String) = j.split(';').map { it.toDouble() }

    fun rel(j: String, n: Double, e: Double): Pair<Double, Double> {
        val (pn, pe, h) = pose(j)
        val b = Math.toDegrees(atan2(e - pe, n - pn))
        return norm180(b - h) to hypot(n - pn, e - pe)
    }

    fun analyzer(d: DroneBase) = SceneAnalyzer { j, target, down, hfovDeg ->
        if (down) return@SceneAnalyzer ViewReport(null, null, null, emptyList(), null, d.altitude(), true, "parquet")
        val (pn, pe, h) = pose(j)
        val p = pn to pe
        val items = ArrayList<ViewItem>()
        var tgt: ViewItem? = null
        // Distance libre droit devant : lancer de rayon dans les deux pièces (murs à e = ±3, n = -2 et n = 8, mur à n = 3 percé par la porte)
        val free = ray(pn, pe, h)
        val (rd, dd) = rel(j, doorN, doorE)
        if (abs(rd) < hfovDeg / 2) items.add(ViewItem("porte du couloir", "porte", 0.5 + rd / hfovDeg, dd, doorW, true))
        val (rk, dk) = rel(j, keys.first, keys.second)
        if (target != null && room(p.first) == 2 && abs(rk) < hfovDeg / 2) tgt = ViewItem("clés sur la commode", "cible", 0.5 + rk / hfovDeg, dk, null, null)
        ViewReport(true, free, 1.5, items, tgt, null, null, "vue")
    }

    /** « Où est la porte ? » sur l'image, d'après la géométrie. */
    fun detector(d: DroneBase) = ObjectDetector { j, _ ->
        val (r, dist) = rel(j, doorN, doorE)
        if (abs(r) > hfov / 2) Detection(false, 0.1, 0.5, 0.5, 0.0, 0.0, "mur")
        else Detection(true, 0.9, 0.5 + r / hfov, 0.55, doorW / (2 * tan(Math.toRadians(hfov / 2)) * dist), 0.6, "porte ouverte")
    }
}

class IndoorTest {
    private fun flying(): RoomDrone = RoomDrone().also { it.takeoff(); it.indoor = true }

    @Test fun indoorStepsAreShortAndCeilingRespected() {
        val d = flying()
        val r = d.move(1.0, 0.0, 0.0, 0.0)
        assertTrue(r, r.contains("avant +0.3"))
        // plafond inconnu : 2 m max
        d.move(0.0, 0.0, 0.3, 0.0); d.move(0.0, 0.0, 0.3, 0.0); d.move(0.0, 0.0, 0.3, 0.0); d.move(0.0, 0.0, 0.3, 0.0)
        assertTrue(d.altitude() <= 2.0 + 1e-6)
        val up = d.move(0.0, 0.0, 0.3, 0.0)
        assertTrue(up, up.startsWith("refusé : trop près du plafond"))
    }

    @Test fun freeSpaceStopsAtWalls() {
        val d = flying()
        val mem = SceneMemory { true }
        d.space = mem
        mem.setFreeSpace(d.positionNE(), listOf(0.0 to 0.7, 90.0 to 3.0))
        val r1 = d.move(0.3, 0.0, 0.0, 0.0)
        assertTrue(r1, r1.contains("raccourci"))
        val r2 = d.move(0.3, 0.0, 0.0, 0.0)
        assertTrue(r2, r2.startsWith("refusé : pas de place"))
        val r3 = d.move(0.0, 0.3, 0.0, 0.0) // à droite (est) : libre
        assertTrue(r3, r3.startsWith("déplacé") && !r3.contains("raccourci"))
    }

    @Test fun surveyFindsTheDoorAndFreeSpace() {
        val h = House()
        val d = flying()
        val mem = SceneMemory { true }
        val sv = Surveyor(d, h.eyes(d), h.analyzer(d), mem, h.hfov, false, { false }, { println(it) })
        val res = sv.survey("clés")
        println(res.text)
        val door = mem.doors().firstOrNull()
        assertNotNull(door)
        assertEquals(3.0, door!!.n, 0.3)
        assertEquals(1.0, door.e, 0.3)
        assertEquals(null, res.targetBearing) // les clés sont dans l'autre pièce
        assertNotNull(mem.ceilingAlt())
    }

    @Test fun passesTheDoor() {
        val h = House()
        val d = flying()
        val mem = SceneMemory { true }
        d.space = mem
        Surveyor(d, h.eyes(d), h.analyzer(d), mem, h.hfov, false, { false }, {}).survey(null)
        val r = DoorPasser(d, h.eyes(d), h.detector(d), mem, h.hfov, false, { false }, { println(it) }).pass("porte du couloir", mem.doors().first())
        println(r)
        assertTrue(r, r.startsWith("porte franchie"))
        val p = d.positionNE()
        assertTrue("de l'autre côté : $p", p.first > h.doorN)
        assertEquals("aligné sur la porte", h.doorE, p.second, 0.3)
    }

    @Test fun findsKeysInTheOtherRoomAndComesBack() {
        val h = House()
        val d = flying()
        val mem = SceneMemory { true }
        d.space = mem
        val sv = Surveyor(d, h.eyes(d), h.analyzer(d), mem, h.hfov, false, { false }, {})
        val dp = DoorPasser(d, h.eyes(d), h.detector(d), mem, h.hfov, false, { false }, {})
        val hs = HouseSearch(d, sv, dp, { null }, mem, { false }, { println(it) }, {})
        val r = hs.run("clés")
        println(r)
        assertTrue(r, r.contains("trouvé") && r.contains("pièce 2"))
        val back = hs.comeBack()
        println(back)
        assertTrue(back, back.startsWith("revenu"))
        assertTrue("revenu dans la pièce 1", d.positionNE().first < h.doorN)
    }
}
