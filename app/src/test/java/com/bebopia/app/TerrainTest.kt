package com.bebopia.app

import com.bebopia.app.core.ai.Terrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainTest {
    /** Colline qui monte vers le nord : 100 m au départ, +1 m tous les 5 m vers le nord. */
    private fun hill() = Terrain { url ->
        val lats = Regex("lat=([^&]+)").find(url)!!.groupValues[1].split("|").map { it.toDouble() }
        val el = lats.map { 100.0 + (it - 45.0) * 111320.0 / 5.0 }
        """{"elevations":[${el.joinToString(",")}]}"""
    }

    @Test fun climbsBeforeTheSlope() {
        // Au départ, à 15 m de haut, en allant vers le nord : 30 m devant le sol est 6 m plus haut -> monter
        val vz = hill().verticalSpeed(45.0, 2.0, 0.0, 15.0, 100.0, 15.0)
        assertNotNull(vz)
        assertTrue("monte : $vz", vz!! > 1.0)
    }

    @Test fun holdsHeightOnFlatGroundAndWhenStopped() {
        assertEquals(0.0, hill().verticalSpeed(45.0, 2.0, null, 15.0, 100.0, 15.0)!!, 1e-9)
        // Plus haut sur la colline (20 m au nord, sol +4 m) à l'arrêt, à 15 m : il faut 19 m
        val lat = 45.0 + 20 / 111320.0
        val vz = hill().verticalSpeed(lat, 2.0, null, 15.0, 100.0, 15.0)!!
        assertTrue("monte de 4 m : $vz", vz > 1.0)
    }

    @Test fun descendsWhenGoingDownhill() {
        val lat = 45.0 + 60 / 111320.0 // sol +12 m
        val vz = hill().verticalSpeed(lat, 2.0, 180.0, 30.0, 100.0, 15.0)!! // vers le sud : le sol baisse
        assertTrue("descend : $vz", vz < 0)
    }

    @Test fun sensorPreventsGoingTooLow() {
        // Le modèle dit OK mais le capteur voit 5 m sous le drone (un toit, des arbres) : on monte
        val vz = hill().verticalSpeed(45.0, 2.0, null, 15.0, 100.0, 15.0, agl = 5.0)!!
        assertTrue(vz > 1.0)
    }

    @Test fun noNetworkNoCorrection() {
        assertNull(Terrain { throw java.io.IOException() }.verticalSpeed(45.0, 2.0, 0.0, 15.0, 100.0, 15.0))
    }
}
