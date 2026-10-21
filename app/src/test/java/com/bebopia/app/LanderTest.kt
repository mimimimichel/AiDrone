package com.bebopia.app

import com.bebopia.app.core.ai.Lander
import com.bebopia.app.core.ai.LandingAnalyzer
import com.bebopia.app.core.ai.LandingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

class LanderTest {
    private val hfov = 72.0
    private val vfov = hfov * 9 / 16

    /** Table basse de 1 m sur 1 m sous le point de décollage ; sol libre ailleurs. Bon endroit : 1 m à droite. */
    private fun analyzer(d: RoomDrone) = LandingAnalyzer { _, alt, _ ->
        val p = d.positionNE()
        val onTable = abs(p.first) < 0.5 && abs(p.second) < 0.5
        if (!onTable) return@LandingAnalyzer LandingView(true, 0.5, 0.5, "parquet dégagé", "sol plat")
        // Où se trouve le point (0, 1) dans l'image (caméra vers le bas, nacelle à -80°, cap 0)
        val fwd = 0.0 - p.first; val right = 1.0 - p.second
        val dep = Math.toDegrees(atan2(alt, fwd))
        val y = 0.5 + (dep - (-d.gimbalPitch())) / vfov
        val x = 0.5 + Math.toDegrees(atan(right / hypot(alt, fwd))) / hfov
        LandingView(false, x, y, "parquet à droite de la table", "table basse sous le drone")
    }

    @Test fun avoidsTheTableAndLandsBesideIt() {
        val d = RoomDrone().also { it.takeoff(); it.indoor = true }
        val l = Lander(d, { "x" }, null, analyzer(d), hfov, false, { println(it) })
        val r = l.land()
        println(r)
        assertFalse("posé", d.flying)
        assertTrue(r, r.startsWith("posé à l'endroit choisi"))
        assertEquals("à côté de la table", 1.0, d.positionNE().second, 0.15)
    }

    @Test fun landsStraightDownWhenTheFloorIsClear() {
        val d = RoomDrone().also { it.takeoff(); it.indoor = true }
        val l = Lander(d, { "x" }, null, { _, _, _ -> LandingView(true, 0.5, 0.5, "tapis ras", "plat et dégagé") }, hfov, false, {})
        l.land()
        assertFalse(d.flying)
        assertEquals(0.0, hypot(d.positionNE().first, d.positionNE().second), 0.05)
    }

    @Test fun staysUpWhenNothingIsSafe() {
        val d = RoomDrone().also { it.takeoff(); it.indoor = true }
        val l = Lander(d, { "x" }, null, { _, _, _ -> LandingView(false, null, null, "", "câbles partout") }, hfov, false, {})
        val r = l.land()
        println(r)
        assertTrue("reste en l'air", d.flying)
    }
}
