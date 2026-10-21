package com.bebopia.app

import com.bebopia.app.core.vision.Gesture
import com.bebopia.app.core.vision.GestureInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturesTest {
    /** Personne face au drone : épaule droite à gauche de l'image (x 0,45), épaule gauche à droite (x 0,55). */
    private fun pose(lw: Pair<Float, Float>, rw: Pair<Float, Float>): List<FloatArray> {
        val p = MutableList(33) { floatArrayOf(0.5f, 0.5f, 0.9f) }
        p[0] = floatArrayOf(0.5f, 0.30f, 0.9f)               // nez
        p[11] = floatArrayOf(0.55f, 0.40f, 0.9f); p[12] = floatArrayOf(0.45f, 0.40f, 0.9f) // épaules
        p[23] = floatArrayOf(0.54f, 0.65f, 0.9f); p[24] = floatArrayOf(0.46f, 0.65f, 0.9f) // hanches
        p[15] = floatArrayOf(lw.first, lw.second, 0.9f); p[16] = floatArrayOf(rw.first, rw.second, 0.9f)
        return p
    }
    private val down = pose(0.57f to 0.62f, 0.43f to 0.62f)
    private val bothUp = pose(0.6f to 0.15f, 0.4f to 0.15f)
    private val oneUp = pose(0.6f to 0.15f, 0.43f to 0.62f)
    private val crossed = pose(0.46f to 0.5f, 0.54f to 0.5f)

    @Test fun poseRules() {
        val g = GestureInterpreter()
        assertNull(g.fromPose(down))
        assertEquals(Gesture.PHOTO, g.fromPose(bothUp))
        assertEquals(Gesture.STOP, g.fromPose(oneUp))
        assertEquals(Gesture.LAND, g.fromPose(crossed))
    }

    @Test fun mustBeHeldThenCooldown() {
        val g = GestureInterpreter()
        assertNull(g.update(0, "Open_Palm", null))
        assertNull(g.update(500, "Open_Palm", null))
        assertEquals(Gesture.STOP, g.update(900, "Open_Palm", null))
        // Pas de nouveau déclenchement pendant 4 s
        assertNull(g.update(1500, "Victory", null))
        assertNull(g.update(3000, "Victory", null))
        assertNull(g.update(5000, "Victory", null))
        assertEquals(Gesture.PHOTO, g.update(6100, "Victory", null))
    }

    @Test fun briefGestureIsIgnored() {
        val g = GestureInterpreter()
        assertNull(g.update(0, null, crossed))
        assertNull(g.update(600, null, crossed))
        assertNull(g.update(1200, null, down)) // relâché trop tôt
        assertNull(g.update(1700, null, down))
        assertNull(g.update(1800, null, crossed))
        assertNull(g.update(2800, null, crossed))
        assertEquals(Gesture.LAND, g.update(3400, null, crossed))
    }
}
