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

    /** Images toutes les 250 ms (comme dans l'application), de [from] à [to] inclus. */
    private fun feed(g: GestureInterpreter, from: Long, to: Long, hand: String?, pose: List<FloatArray>?): Gesture? {
        var t = from
        var r: Gesture? = null
        while (t <= to) { r = g.update(t, hand, pose) ?: r; t += 250 }
        return r
    }

    @Test fun mustBeHeldThenCooldown() {
        val g = GestureInterpreter()
        assertNull(feed(g, 0, 1000, "Open_Palm", null))
        assertEquals(Gesture.STOP, g.update(1250, "Open_Palm", null))
        // Pas de nouveau déclenchement pendant 4 s
        assertNull(feed(g, 1500, 5000, "Victory", null))
        assertEquals(Gesture.PHOTO, feed(g, 5250, 7000, "Victory", null))
    }

    @Test fun flickeringDetectionIsIgnored() {
        // Fausse détection dans un décor : elle apparaît une image sur deux -> jamais tenue
        val g = GestureInterpreter()
        var t = 0L
        repeat(40) { k -> assertNull(g.update(t, if (k % 2 == 0) "Open_Palm" else null, null)); t += 250 }
    }

    @Test fun briefGestureIsIgnored() {
        val g = GestureInterpreter()
        assertNull(feed(g, 0, 1500, null, crossed))
        assertNull(feed(g, 1750, 2250, null, down)) // relâché trop tôt
        assertNull(feed(g, 2500, 4250, null, crossed))
        assertEquals(Gesture.LAND, feed(g, 4500, 4750, null, crossed))
    }

    @Test fun noPersonNoPoseGesture() {
        // Squelette « halluciné » tout petit (pas une vraie personne) : ignoré
        val tiny = pose(0.505f to 0.1f, 0.495f to 0.1f).map { it.copyOf() }
        tiny[11][0] = 0.502f; tiny[12][0] = 0.498f
        assertNull(GestureInterpreter().fromPose(tiny))
    }
}
