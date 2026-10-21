package com.bebopia.app

import com.bebopia.app.core.Limits
import com.bebopia.app.core.roomba.Odometry
import com.bebopia.app.core.roomba.RoombaDrone
import com.bebopia.app.core.roomba.RoombaOI
import com.bebopia.app.core.roomba.SerialLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Faux Roomba : comprend le protocole, fait tourner ses roues (impulsions) et répond aux lectures de capteurs. */
class FakeRoomba(private val baudOk: Int = 115_200) : SerialLink {
    var mode = 1 // passif
    var right = 0; var left = 0 // mm/s
    var countsL = 1000.0; var countsR = 64000.0 // proche du passage à 0, pour tester le rebouclage
    var bump = false; var cliff = false; var onBase = false
    var x = 0.0; var y = 0.0; var h = 0.0 // vraie position (m, °)
    val songs = ArrayList<Int>()
    var curBaud = 0
    private var last = System.nanoTime()
    private val out = ArrayList<Byte>()
    private val inBuf = ArrayList<Int>()

    @Synchronized private fun physics() {
        val now = System.nanoTime()
        val dt = (now - last) / 1e9
        last = now
        if (mode < 2) { right = 0; left = 0 }
        val dl = left / 1000.0 * dt; val dr = right / 1000.0 * dt
        countsL += dl * 1000 / RoombaOI.MM_PER_COUNT; countsR += dr * 1000 / RoombaOI.MM_PER_COUNT
        val d = (dl + dr) / 2
        val dth = Math.toDegrees((dl - dr) / 0.235)
        x += d * cos(Math.toRadians(h)); y += d * sin(Math.toRadians(h)); h += dth
        if (cliff && mode >= 2) mode = 1 // le mode sécurité le rend passif
        if (onBase && x < -0.1) onBase = false
    }

    @Synchronized override fun write(b: ByteArray) {
        physics()
        if (curBaud != baudOk) return // mauvaise vitesse : il n'entend rien
        for (v in b) inBuf.add(v.toInt() and 0xFF)
        while (inBuf.isNotEmpty()) {
            val op = inBuf[0]
            val need = when (op) { 128, 131, 132, 143 -> 1; 145 -> 5; 141 -> 2; 140 -> if (inBuf.size >= 3) 3 + 2 * inBuf[2] else 99
                149 -> if (inBuf.size >= 2) 2 + inBuf[1] else 99; else -> 1 }
            if (inBuf.size < need) return
            val c = inBuf.subList(0, need).toList()
            repeat(need) { inBuf.removeAt(0) }
            when (op) {
                128 -> mode = 1
                131 -> mode = if (onBase) 1 else 2
                132 -> mode = 3
                143 -> mode = 1
                145 -> if (mode >= 2) { right = ((c[1] shl 8) or c[2]).toShort().toInt(); left = ((c[3] shl 8) or c[4]).toShort().toInt() }
                141 -> songs.add(c[1])
                149 -> out.addAll(sensors().toList())
            }
        }
    }

    private fun sensors(): ByteArray {
        val l = countsL.toInt() and 0xFFFF; val r = countsR.toInt() and 0xFFFF
        val v = intArrayOf(
            if (bump) 3 else 0, if (cliff) 1 else 0, 0, 0, 0,
            if (onBase) 2 else 0, 0x3A, 0x98, 0xFF, 0x38, 0x07, 0xD0, 0x0B, 0xB8,
            if (onBase) 2 else 0, mode, l shr 8, l and 0xFF, r shr 8, r and 0xFF, 0,
        )
        return ByteArray(v.size) { v[it].toByte() }
    }

    @Synchronized override fun read(n: Int, timeoutMs: Int): ByteArray? {
        if (out.size < n) return null
        val r = ByteArray(n) { out[it] }
        repeat(n) { out.removeAt(0) }
        return r
    }
    @Synchronized override fun clearInput() { out.clear() }
    override fun setWakeLine(low: Boolean) {}
    override fun setBaud(baud: Int) { curBaud = baud }
    override fun close() {}
}

class RoombaTest {
    @Test
    fun driveDirectEncoding() {
        val b = RoombaOI.driveDirect(-200, 500)
        assertEquals(listOf(145, 0xFF, 0x38, 0x01, 0xF4), b.map { it.toInt() and 0xFF })
        // Tourner à droite : roue gauche plus rapide
        val (r, l) = RoombaOI.wheelSpeeds(0.0, 90.0)
        assertTrue(l > 0 && r < 0)
    }

    @Test
    fun parseRejectsGarbage() {
        assertEquals(null, RoombaOI.parseSensors(ByteArray(RoombaOI.SENSOR_BYTES) { 0x7F }))
        assertEquals(null, RoombaOI.parseSensors(ByteArray(5)))
    }

    @Test
    fun odometryWrapsAndTurns() {
        val o = Odometry()
        o.update(65500, 100)
        // Roue gauche avance (passe par 0), roue droite recule : rotation sur place vers la droite
        val c = (0.1 * 1000 / RoombaOI.MM_PER_COUNT).toInt()
        o.update((65500 + c) and 0xFFFF, 100 - c)
        assertTrue("rotation à droite : ${o.headingDeg}", o.headingDeg > 40)
        assertEquals(0.0, o.x, 0.01)
    }

    @Test
    fun connectsAtSecondBaudRate() {
        val fake = FakeRoomba(baudOk = 57_600)
        val d = RoombaDrone(Limits(), fake)
        d.connect()
        assertEquals(57_600, d.baud)
        assertTrue("mélodie jouée", fake.songs.contains(0))
        d.disconnect()
    }

    @Test
    fun drivesForwardThenSideways() {
        val fake = FakeRoomba()
        val d = RoombaDrone(Limits(maxStepM = 1.0), fake)
        d.connect()
        d.takeoff()
        assertEquals(2, fake.mode)
        val r1 = d.move(0.5, 0.0, 0.0, 0.0)
        assertTrue(r1, r1.startsWith("déplacé"))
        assertEquals(0.5, fake.x, 0.06)
        // « 30 cm à droite » : il tourne, avance, reprend son cap
        d.move(0.0, 0.3, 0.0, 0.0)
        assertEquals(0.3, fake.y, 0.06)
        assertTrue("cap repris : ${fake.h}", abs(fake.h) < 6)
        // L'odométrie de l'appli suit la vraie position
        assertEquals(fake.x, d.positionNE().first, 0.05)
        assertEquals(fake.y, d.positionNE().second, 0.05)
        // Monter : refusé
        assertTrue(d.move(0.0, 0.0, 0.5, 0.0).startsWith("refusé"))
        d.land()
        assertEquals(1, fake.mode)
        d.disconnect()
    }

    @Test
    fun bumperStopsForward() {
        val fake = FakeRoomba()
        val d = RoombaDrone(Limits(maxStepM = 1.0), fake)
        d.connect(); d.takeoff()
        Thread { Thread.sleep(700); fake.bump = true }.start()
        val r = d.move(1.0, 0.0, 0.0, 0.0)
        assertTrue(r, r.contains("obstacle"))
        assertTrue("arrêté avant 1 m : ${fake.x}", fake.x < 0.5)
        fake.bump = false
        // Juste après : l'avance est bloquée, reculer reste possible
        assertTrue(d.move(0.3, 0.0, 0.0, 0.0).startsWith("refusé"))
        d.disconnect()
    }

    @Test
    fun cliffMakesItStop() {
        val fake = FakeRoomba()
        val d = RoombaDrone(Limits(maxStepM = 1.0), fake)
        d.connect(); d.takeoff()
        Thread { Thread.sleep(600); fake.cliff = true }.start()
        val r = d.move(1.0, 0.0, 0.0, 0.0)
        assertTrue(r, r.contains("interrompu"))
        assertNotNull(d.safetyNote)
        assertTrue(d.safetyNote!!, d.safetyNote!!.contains("vide"))
        // Tant qu'il y a du vide : il refuse de repartir
        val again = d.move(0.2, 0.0, 0.0, 0.0)
        assertTrue(again, again.contains("interrompu") || again.startsWith("refusé"))
        d.disconnect()
    }

    @Test
    fun leavesBaseBeforeDriving() {
        val fake = FakeRoomba()
        fake.onBase = true
        val d = RoombaDrone(Limits(), fake)
        d.connect()
        fake.onBase = true
        d.takeoff()
        assertTrue("a reculé de sa base : ${fake.x}", fake.x < -0.15)
        d.disconnect()
    }
}
