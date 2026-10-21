package com.bebopia.app.core.roomba

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import com.bebopia.app.core.norm180
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Liaison série avec le Roomba (carte USB-série branchée au téléphone, ou fausse liaison pour les tests). */
interface SerialLink {
    fun write(b: ByteArray)
    /** Lit exactement [n] octets, ou null si rien de complet n'arrive en [timeoutMs]. */
    fun read(n: Int, timeoutMs: Int): ByteArray?
    fun clearInput()
    /** Ligne de réveil du Roomba (broche 5, BRC) : true = tirée vers le bas. */
    fun setWakeLine(low: Boolean)
    fun setBaud(baud: Int)
    fun close()
}

/**
 * Robot roulant Roomba (série 600) piloté par sa prise de commande, la caméra étant celle du téléphone posé dessus.
 * Pour l'application, c'est un « drone » qui ne vole pas : décoller = démarrer (mode sécurité : il s'arrête tout seul
 * au bord d'une marche ou s'il est soulevé), atterrir = s'arrêter. Il ne se déplace pas sur le côté : il tourne, avance,
 * puis reprend son cap.
 */
class RoombaDrone(
    limits: Limits,
    private val link: SerialLink,
    private val log: (String) -> Unit = {},
    /** Pour les tests : pas d'attente réelle. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : DroneBase(limits) {
    override val name = "Roomba"
    override val groundRobot: Boolean get() = true

    private val io = Any()
    private val odo = Odometry()
    @Volatile var sensors: RoombaOI.Sensors? = null
        private set
    @Volatile private var sensorsAt = 0L
    @Volatile private var running = false
    @Volatile private var cancel = false
    @Volatile private var failures = 0
    @Volatile private var lastWake = 0L
    @Volatile var baud = 0
        private set
    /** Dernier événement de sécurité (vide, roue soulevée), pour l'expliquer. */
    @Volatile var safetyNote: String? = null
        private set

    private var pollThread: Thread? = null
    private var driveThread: Thread? = null
    @Volatile private var vel = DoubleArray(2) // m/s, °/s
    @Volatile private var velUntil = 0L

    // ---- liaison ------------------------------------------------------------------------------------
    private fun send(b: ByteArray) = synchronized(io) { link.write(b) }

    /** Une lecture des capteurs (bloquant, ~20 ms). */
    fun readSensors(): RoombaOI.Sensors? = synchronized(io) {
        link.clearInput()
        link.write(RoombaOI.querySensors())
        val raw = link.read(RoombaOI.SENSOR_BYTES, 250) ?: return null
        RoombaOI.parseSensors(raw)
    }

    /** Impulsion sur la ligne de réveil (sort le Roomba de sa veille). */
    fun wakePulse() {
        lastWake = System.currentTimeMillis()
        runCatching { link.setWakeLine(true); sleeper(500); link.setWakeLine(false) }
    }

    override fun connect() {
        runCatching { link.setWakeLine(false) }
        wakePulse()
        sleeper(300)
        var s: RoombaOI.Sensors? = null
        for (b in listOf(115_200, 57_600)) {
            link.setBaud(b)
            sleeper(100)
            send(RoombaOI.start())
            sleeper(150)
            repeat(3) { if (s == null) s = readSensors() }
            if (s != null) { baud = b; break }
        }
        val ok = s ?: throw IllegalStateException(
            "la carte USB répond mais pas le Roomba. Vérifie : interrupteur de la carte sur 5V, TX -> trou 3, RX -> trou 4, " +
                "GND -> trou 6, et que le Roomba est allumé (appuie une fois sur CLEAN pour le réveiller, puis réessaie)")
        sensors = ok; sensorsAt = System.currentTimeMillis()
        odo.update(ok.leftCounts, ok.rightCounts)
        log("Roomba : réponse à $baud bauds, batterie ${ok.batteryPct ?: "?"} %, mode ${ok.modeName}")
        hello()
        running = true
        pollThread = Thread(::pollLoop, "roomba-capteurs").apply { isDaemon = true; start() }
        driveThread = Thread(::driveLoop, "roomba-roues").apply { isDaemon = true; start() }
    }

    /** Petit air joué à la connexion : s'il chante, toute la chaîne marche. */
    fun hello() {
        send(RoombaOI.song(0, listOf(72 to 10, 76 to 10, 79 to 10, 84 to 20)))
        send(RoombaOI.play(0))
    }

    private fun beep(up: Boolean) {
        send(RoombaOI.song(1, if (up) listOf(67 to 8, 74 to 12) else listOf(74 to 8, 67 to 12)))
        send(RoombaOI.play(1))
    }

    override fun disconnect() {
        running = false
        runCatching { send(RoombaOI.driveDirect(0, 0)); send(RoombaOI.start()) }
        runCatching { link.close() }
    }

    // ---- capteurs, odométrie, sécurité -------------------------------------------------------------------
    private fun pollLoop() {
        while (running) {
            sleeper(60)
            val s = runCatching { readSensors() }.getOrNull()
            val now = System.currentTimeMillis()
            if (s == null) {
                failures++
                // Plus de réponse : il s'est peut-être endormi
                if (failures % 30 == 10) { log("Roomba : pas de réponse, je le réveille"); wakePulse(); runCatching { send(RoombaOI.start()) } }
                continue
            }
            failures = 0
            val prev = sensors
            sensors = s; sensorsAt = now
            odo.update(s.leftCounts, s.rightCounts)
            // En route, le Roomba repasse en mode passif de lui-même (vide, roue soulevée, base) : on arrête tout
            if (flying && prev?.oiMode == 2 && s.oiMode == 1) {
                safetyNote = when {
                    s.cliff -> "vide détecté sous le robot (bord de marche ?)"
                    s.wheelDrop -> "roue soulevée (robot soulevé ou coincé)"
                    s.onHomeBase -> "posé sur sa base"
                    else -> "le Roomba a repris la main (mode passif)"
                }
                log("Roomba : arrêt de sécurité, $safetyNote")
                velUntil = 0L
                cancelMove()
            }
            // Au repos (mode passif), il s'endort au bout de 5 min : petite impulsion chaque minute pour rester joignable
            if (!flying && now - lastWake > 55_000) wakePulse()
        }
    }

    private fun wheels(forwardMS: Double, yawDegS: Double) {
        val (r, l) = RoombaOI.wheelSpeeds(forwardMS, yawDegS)
        send(RoombaOI.driveDirect(r, l))
    }

    /** S'assure d'être en mode sécurité avant de rouler (il en sort après un vide ou une roue soulevée). */
    private fun ensureSafe(): String? {
        val s = sensors ?: readSensors() ?: return "le Roomba ne répond plus"
        if (s.cliff || s.wheelDrop) return "refusé : ${if (s.cliff) "vide détecté sous le robot" else "roue soulevée"}, je ne roule pas"
        if (s.oiMode != 2) { send(RoombaOI.start()); send(RoombaOI.safe()); sleeper(80) }
        return null
    }

    // ---- « décoller », « atterrir » ------------------------------------------------------------------
    override fun doTakeoff() {
        safetyNote = null
        val s = sensors
        send(RoombaOI.start())
        if (s?.onHomeBase == true) {
            // Sur sa base, le mode sécurité le rendrait passif aussitôt : on recule d'abord en mode complet
            log("Roomba : je quitte ma base")
            send(RoombaOI.full())
            sleeper(100)
            val end = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < end) { wheels(-0.12, 0.0); sleeper(100) }
            wheels(0.0, 0.0)
        }
        send(RoombaOI.safe())
        sleeper(100)
        beep(true)
        odo.reset()
    }

    override fun doLand() {
        velUntil = 0L
        cancel = true
        wheels(0.0, 0.0)
        beep(false)
        send(RoombaOI.start()) // mode passif : ses boutons remarchent
    }

    override fun doEmergency() {
        velUntil = 0L
        cancel = true
        runCatching { wheels(0.0, 0.0); send(RoombaOI.start()) }
    }

    override fun doTilt(degrees: Int) {} // la caméra est celle du téléphone, fixe

    override fun doCancelMove() {
        cancel = true
        velUntil = 1L
    }

    // ---- position ---------------------------------------------------------------------------------------
    override fun altitude(): Double = 0.0
    override fun compass(): Double = odo.headingDeg
    override fun positionNE(): Pair<Double, Double> = odo.x to odo.y
    override fun battery(): Int? = sensors?.batteryPct
    override fun takeoffHeading(): Double = 0.0
    public override fun syncHeading() {
        setHeadingFromCompass(odo.headingDeg)
        x = odo.x; y = odo.y
    }

    // ---- déplacements -----------------------------------------------------------------------------------
    private val maxSpeed get() = if (indoor) 0.25 else 0.35

    /** Tourne de [deg] degrés (+ = à droite) d'après les roues. false si interrompu. */
    private fun turn(deg: Double): Boolean {
        if (abs(deg) < 1.5) return true
        val target = norm180(odo.headingDeg + deg)
        val end = System.currentTimeMillis() + ((abs(deg) / 20.0 + 3) * 1000).toLong()
        while (!cancel && System.currentTimeMillis() < end) {
            val err = norm180(target - odo.headingDeg)
            if (abs(err) < 2.0) break
            val rate = (err * 3).coerceIn(-90.0, 90.0).let { if (abs(it) < 18) Math.signum(it) * 18 else it }
            wheels(0.0, rate)
            sleeper(50)
        }
        wheels(0.0, 0.0)
        return !cancel
    }

    /** Avance (ou recule si négatif) de [dist] mètres en gardant son cap. false si interrompu. */
    private fun drive(dist: Double): Boolean {
        if (abs(dist) < 0.01) return true
        val start = odo.travelledM
        val h0 = odo.headingDeg
        val sign = Math.signum(dist)
        val end = System.currentTimeMillis() + ((abs(dist) / 0.05 + 3) * 1000).toLong()
        setForward(sign > 0)
        try {
            while (!cancel && System.currentTimeMillis() < end) {
                val done = odo.travelledM - start
                val left = abs(dist) - done
                if (left < 0.01) break
                val s = sensors
                if (sign > 0 && s != null && s.bump) {
                    // Pare-chocs : il a touché quelque chose devant. Il recule un peu pour se dégager.
                    wheels(0.0, 0.0)
                    log("Roomba : pare-chocs ${if (s.bumpLeft && s.bumpRight) "avant" else if (s.bumpLeft) "gauche" else "droit"} touché")
                    stopForObstacle()
                    val back = System.currentTimeMillis() + 500
                    while (System.currentTimeMillis() < back) { wheels(-0.1, 0.0); sleeper(50) }
                    break
                }
                val v = (left * 1.5).coerceIn(0.06, maxSpeed) * sign
                val yaw = (norm180(h0 - odo.headingDeg) * 2).coerceIn(-20.0, 20.0)
                wheels(v, yaw)
                sleeper(50)
            }
        } finally {
            wheels(0.0, 0.0)
            setForward(false)
        }
        return !cancel
    }

    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {
        val err = ensureSafe()
        if (err != null) { log("Roomba : $err"); cancelMove(); return }
        cancel = false
        val dist = hypot(forward, right)
        val yawDeg = Math.toDegrees(yawRad)
        if (dist < 0.02) { turn(yawDeg); return }
        // Direction du déplacement par rapport à son nez ; en arrière : il recule plutôt que de faire demi-tour
        var dir = Math.toDegrees(atan2(right, forward))
        var d = dist
        if (abs(dir) > 100) { dir = norm180(dir - 180); d = -dist }
        if (!turn(dir)) return
        if (!drive(d)) return
        turn(yawDeg - dir) // reprend son cap (plus la rotation demandée)
    }

    // ---- vitesses continues (manettes du casque, suivi) -----------------------------------------------
    override val supportsVelocity: Boolean get() = true

    override fun velocity(forward: Double, right: Double, up: Double, yawRate: Double, holdMs: Long) {
        // Pas de pas de côté : un « à droite » devient « tourne à droite »
        val yaw = yawRate + right * 60.0
        vel = doubleArrayOf(forward.coerceIn(-maxSpeed, maxSpeed), yaw.coerceIn(-90.0, 90.0))
        velUntil = System.currentTimeMillis() + holdMs
    }

    override fun stopVelocity() { if (velUntil != 0L) velUntil = 1L }

    private fun driveLoop() {
        var active = false
        while (running) {
            sleeper(80)
            val until = velUntil
            if (until == 0L || !flying || System.currentTimeMillis() > until) {
                if (active) { runCatching { wheels(0.0, 0.0) }; active = false; setForward(false) }
                if (until != 0L && System.currentTimeMillis() > until) velUntil = 0L
                continue
            }
            if (!active && ensureSafe() != null) { velUntil = 0L; continue }
            var (f, yaw) = vel
            val s = sensors
            // Pare-chocs ou obstacle vu : plus d'avance, il peut encore reculer et tourner
            if (f > 0 && (forwardBlocked() || s?.bump == true)) f = 0.0
            setForward(f > 0.05)
            runCatching { wheels(f, yaw) }
            active = true
        }
    }

    // ---- base de charge --------------------------------------------------------------------------------
    override fun nativeReturnHome(): String? {
        velUntil = 0L
        cancel = true
        runCatching { wheels(0.0, 0.0); send(RoombaOI.start()); send(RoombaOI.seekDock()) }
            .onFailure { return null }
        flying = false
        return "je rentre à ma base de charge (le Roomba la cherche tout seul, ça peut prendre une ou deux minutes)"
    }

    override fun positionHoldWeak(): Boolean = false

    override fun telemetry(): Map<String, Any?> = super.telemetry() + mapOf(
        "roomba_mode" to sensors?.modeName,
        "pare_chocs" to sensors?.bump,
        "sur_base" to sensors?.onHomeBase,
        "en_charge" to sensors?.charging,
    )
}
