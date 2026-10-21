package com.bebopia.app.core.roomba

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Protocole de pilotage des Roomba série 500/600 (« Open Interface » d'iRobot, prise mini-DIN 7 broches).
 * Octets à envoyer sur la liaison série (115 200 bauds pour la série 600, 57 600 pour certains 500).
 */
object RoombaOI {
    const val START = 128
    const val SAFE = 131
    const val FULL = 132
    const val POWER_DOWN = 133
    const val SEEK_DOCK = 143
    const val DRIVE_DIRECT = 145
    const val LEDS = 139
    const val SONG = 140
    const val PLAY = 141
    const val QUERY_LIST = 149

    /** Roues : 508,8 impulsions par tour, roue de 72 mm, 235 mm entre les roues. */
    const val MM_PER_COUNT = PI * 72.0 / 508.8
    const val WHEEL_BASE_MM = 235.0
    const val MAX_MM_S = 500

    fun start() = bytes(START)
    fun safe() = bytes(SAFE)
    fun full() = bytes(FULL)
    fun seekDock() = bytes(SEEK_DOCK)
    fun powerDown() = bytes(POWER_DOWN)

    /** Vitesse de chaque roue (mm/s, + = en avant), bornée à ±500. */
    fun driveDirect(rightMmS: Int, leftMmS: Int): ByteArray {
        val r = rightMmS.coerceIn(-MAX_MM_S, MAX_MM_S)
        val l = leftMmS.coerceIn(-MAX_MM_S, MAX_MM_S)
        return bytes(DRIVE_DIRECT, (r shr 8) and 0xFF, r and 0xFF, (l shr 8) and 0xFF, l and 0xFF)
    }

    /**
     * Vitesses des roues (mm/s) pour avancer à [forwardMS] m/s en tournant à [yawRateDegS] °/s
     * (+ = vers la droite, sens des aiguilles d'une montre vu de dessus) : (droite, gauche).
     */
    fun wheelSpeeds(forwardMS: Double, yawRateDegS: Double): Pair<Int, Int> {
        val v = forwardMS * 1000.0
        val w = Math.toRadians(yawRateDegS) * WHEEL_BASE_MM / 2
        // Tourner à droite : la roue gauche va plus vite que la droite
        return (v - w).roundToInt() to (v + w).roundToInt()
    }

    /** Petite mélodie : notes MIDI (31-127) et durées en 1/64 s. */
    fun song(number: Int, notes: List<Pair<Int, Int>>): ByteArray {
        val n = notes.take(16)
        val out = ArrayList<Int>()
        out.add(SONG); out.add(number); out.add(n.size)
        for ((note, dur) in n) { out.add(note.coerceIn(31, 127)); out.add(dur.coerceIn(1, 255)) }
        return bytes(*out.toIntArray())
    }

    fun play(number: Int) = bytes(PLAY, number)

    /** Voyants : [debris] et [spot] (bleus), bouton CLEAN de la couleur [color] (0 vert -> 255 rouge), [intensity] 0-255. */
    fun leds(debris: Boolean, spot: Boolean, color: Int, intensity: Int) =
        bytes(LEDS, (if (debris) 1 else 0) or (if (spot) 2 else 0), color.coerceIn(0, 255), intensity.coerceIn(0, 255))

    // ---- capteurs ----------------------------------------------------------------------------------
    /** Paquets lus à chaque tour : (numéro, taille en octets). */
    val SENSOR_PACKETS = listOf(
        7 to 1,   // pare-chocs et roues soulevées
        9 to 1, 10 to 1, 11 to 1, 12 to 1, // vide : gauche, avant gauche, avant droit, droite
        21 to 1,  // état de charge
        22 to 2,  // tension (mV)
        23 to 2,  // courant (mA, signé)
        25 to 2,  // charge (mAh)
        26 to 2,  // capacité (mAh)
        34 to 1,  // source de charge (bit 1 = base)
        35 to 1,  // mode : 0 éteint, 1 passif, 2 sécurité, 3 complet
        43 to 2,  // impulsions roue gauche
        44 to 2,  // impulsions roue droite
        45 to 1,  // détecteurs de proximité de l'avant (si le modèle en a)
    )
    val SENSOR_BYTES = SENSOR_PACKETS.sumOf { it.second }

    fun querySensors(): ByteArray = bytes(QUERY_LIST, SENSOR_PACKETS.size, *SENSOR_PACKETS.map { it.first }.toIntArray())

    class Sensors(
        val bumpRight: Boolean,
        val bumpLeft: Boolean,
        val wheelDropRight: Boolean,
        val wheelDropLeft: Boolean,
        /** Vide sous l'un des 4 capteurs (bord de marche, robot soulevé). */
        val cliff: Boolean,
        val chargingState: Int,
        val voltageMv: Int,
        val currentMa: Int,
        val chargeMah: Int,
        val capacityMah: Int,
        val onHomeBase: Boolean,
        val oiMode: Int,
        val leftCounts: Int,
        val rightCounts: Int,
        val lightBumper: Int,
    ) {
        val bump get() = bumpLeft || bumpRight
        val wheelDrop get() = wheelDropLeft || wheelDropRight
        val batteryPct: Int? get() = if (capacityMah in 500..10_000) (chargeMah * 100 / capacityMah).coerceIn(0, 100) else null
        val charging get() = chargingState in 1..3
        val modeName get() = when (oiMode) { 0 -> "éteint"; 1 -> "passif"; 2 -> "sécurité"; 3 -> "complet"; else -> "?" }
    }

    /** Lit la réponse à [querySensors] ; null si la taille ou les valeurs sont incohérentes (mauvaise vitesse de liaison). */
    fun parseSensors(b: ByteArray): Sensors? {
        if (b.size != SENSOR_BYTES) return null
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun s16(i: Int) = u16(i).toShort().toInt()
        val bumps = u8(0)
        val mode = u8(15)
        val chargingState = u8(5)
        // Contrôles de cohérence : bits inutilisés à zéro, mode et état de charge dans leurs plages
        if (bumps and 0xF0 != 0 || mode > 3 || chargingState > 5) return null
        for (i in 1..4) if (u8(i) > 1) return null
        return Sensors(
            bumpRight = bumps and 1 != 0,
            bumpLeft = bumps and 2 != 0,
            wheelDropRight = bumps and 4 != 0,
            wheelDropLeft = bumps and 8 != 0,
            cliff = (1..4).any { u8(it) == 1 },
            chargingState = chargingState,
            voltageMv = u16(6),
            currentMa = s16(8),
            chargeMah = u16(10),
            capacityMah = u16(12),
            onHomeBase = u8(14) and 2 != 0,
            oiMode = mode,
            leftCounts = u16(16),
            rightCounts = u16(18),
            lightBumper = u8(20),
        )
    }

    /** Écart d'impulsions entre deux lectures (les compteurs repassent par 0 après 65 535). */
    fun countDelta(prev: Int, now: Int): Int {
        var d = now - prev
        if (d > 32767) d -= 65536
        if (d < -32768) d += 65536
        return d
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

/**
 * Position estimée par les roues : x devant au départ, y à droite (m), cap en degrés (+ = à droite).
 */
class Odometry {
    var x = 0.0; private set
    var y = 0.0; private set
    var headingDeg = 0.0; private set
    var travelledM = 0.0; private set
    private var lastL: Int? = null
    private var lastR: Int? = null

    fun reset() { x = 0.0; y = 0.0; headingDeg = 0.0; travelledM = 0.0 }

    /** Nouveaux compteurs ; renvoie (distance m, rotation °) depuis la lecture précédente. */
    fun update(leftCounts: Int, rightCounts: Int): Pair<Double, Double> {
        val pl = lastL; val pr = lastR
        lastL = leftCounts; lastR = rightCounts
        if (pl == null || pr == null) return 0.0 to 0.0
        val dl = RoombaOI.countDelta(pl, leftCounts) * RoombaOI.MM_PER_COUNT / 1000.0
        val dr = RoombaOI.countDelta(pr, rightCounts) * RoombaOI.MM_PER_COUNT / 1000.0
        // Saut aberrant (lecture ratée) : ignoré
        if (kotlin.math.abs(dl) > 0.3 || kotlin.math.abs(dr) > 0.3) return 0.0 to 0.0
        val d = (dl + dr) / 2
        // Roue gauche plus loin que la droite : le robot tourne à droite
        val dth = Math.toDegrees((dl - dr) / (RoombaOI.WHEEL_BASE_MM / 1000.0))
        val mid = Math.toRadians(headingDeg + dth / 2)
        x += d * kotlin.math.cos(mid)
        y += d * kotlin.math.sin(mid)
        headingDeg = com.bebopia.app.core.norm180(headingDeg + dth)
        travelledM += kotlin.math.abs(d)
        return d to dth
    }
}
