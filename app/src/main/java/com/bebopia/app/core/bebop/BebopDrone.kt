package com.bebopia.app.core.bebop

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Le vrai Parrot Bebop 2 / Bebop 2 Power, piloté par le protocole ARSDK3. */
class BebopDrone(
    limits: Limits,
    binder: SocketBinder,
    private val log: (String) -> Unit,
    host: String = ArNetwork.DEFAULT_HOST,
) : DroneBase(limits) {

    override val name = "Bebop 2"

    @Volatile private var flyingState = -1
    @Volatile private var batteryPct: Int? = null
    @Volatile private var altitudeM: Double? = null
    private val moveEnds = LinkedBlockingQueue<Int>()

    /** Appelé si la liaison Wi-Fi avec le drone est perdue. */
    var onLinkLost: (() -> Unit)? = null
    var streamControlPort = 5005
        private set

    private val net = ArNetwork(binder, log, ::onCommand, { onLinkLost?.invoke() }, host)

    override var flying: Boolean
        get() = flyingState in setOf(1, 2, 3, 4)
        set(_) {} // l'état vient du drone lui-même

    val flyingStateLabel: String get() = BebopCmd.FLYING_STATES[flyingState] ?: "inconnu"
    val linkUp: Boolean get() = net.linkUp

    override fun connect() {
        val hs = net.connect()
        streamControlPort = hs.streamControlPort
        log("connecté au Bebop")
        val now = Date()
        net.sendWithAck(BebopCmd.currentDate(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)))
        net.sendWithAck(BebopCmd.currentTime(SimpleDateFormat("'T'HHmmssZ", Locale.US).format(now)))
        net.sendWithAck(BebopCmd.allSettings())
        net.sendWithAck(BebopCmd.allStates())

        val settings = listOf(
            "plafond" to BebopCmd.maxAltitude(limits.maxAltitudeM.toFloat()),
            "distance max" to BebopCmd.maxDistance(maxOf(10.0, limits.maxDistanceM).toFloat()),
            "géobarrière" to BebopCmd.noFlyOverMaxDistance(true),
            "inclinaison max" to BebopCmd.maxTilt(10f),
            "vitesse verticale" to BebopCmd.maxVerticalSpeed(1f),
            "vitesse de rotation" to BebopCmd.maxRotationSpeed(60f),
        )
        for ((label, c) in settings) {
            if (!net.sendWithAck(c)) log("réglage « $label » non confirmé par le drone")
        }
        if (!net.sendWithAck(BebopCmd.videoEnable(true))) log("activation de la vidéo non confirmée")
    }

    override fun disconnect() = net.close()

    override fun battery(): Int? = batteryPct
    override fun altitude(): Double = altitudeM ?: z

    private fun waitForState(states: Set<Int>, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (flyingState in states) return true
            Thread.sleep(100)
        }
        return flyingState in states
    }

    override fun doTakeoff() {
        if (!net.sendWithAck(BebopCmd.takeOff())) throw IllegalStateException("le drone n'a pas reçu l'ordre de décollage")
        if (!waitForState(setOf(2, 3), 10_000)) throw IllegalStateException("décollage non confirmé (état : $flyingStateLabel)")
    }

    override fun doLand() {
        if (!net.sendWithAck(BebopCmd.landing(), retries = 8)) log("ordre d'atterrissage non confirmé")
        if (!waitForState(setOf(0), 20_000)) log("atterrissage non confirmé (état : $flyingStateLabel)")
    }

    override fun doEmergency() {
        net.sendWithAck(BebopCmd.emergency(), buffer = Ar.BUF_EMERGENCY, retries = 8)
    }

    override fun doCancelMove() {
        net.sendWithAck(BebopCmd.cancelMoveBy())
    }

    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {
        moveEnds.clear()
        val ok = net.sendWithAck(BebopCmd.moveBy(forward.toFloat(), right.toFloat(), down.toFloat(), yawRad.toFloat()))
        if (!ok) throw IllegalStateException("le drone n'a pas reçu l'ordre de déplacement")
        val error = moveEnds.poll(((waitS * 2 + 3) * 1000).toLong(), TimeUnit.MILLISECONDS)
        when (error) {
            null -> log("fin de déplacement non signalée par le drone")
            0 -> Unit
            else -> log("déplacement terminé avec le code $error")
        }
    }

    override fun doTilt(degrees: Int) {
        repeat(3) {
            net.send(BebopCmd.cameraOrientation(degrees.toFloat(), 0f))
            Thread.sleep(50)
        }
    }

    private fun onCommand(c: ArCommand) {
        when {
            BebopCmd.isBattery(c) -> batteryPct = c.reader().u8()
            BebopCmd.isAltitude(c) -> altitudeM = c.reader().f64()
            BebopCmd.isFlyingState(c) -> {
                val s = c.reader().i32()
                if (s != flyingState) {
                    flyingState = s
                    log("état du drone : ${BebopCmd.FLYING_STATES[s] ?: s}")
                }
            }
            BebopCmd.isAlertState(c) -> {
                val a = c.reader().i32()
                if (a != 0) log("ALERTE drone : ${BebopCmd.ALERTS[a] ?: a}")
            }
            BebopCmd.isMoveByEnd(c) -> {
                val r = c.reader()
                repeat(4) { r.f32() }
                moveEnds.offer(r.i32())
            }
        }
    }
}
