package com.bebopia.app.dji

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.Limits
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Mavic Mini (et autres drones DJI du kit 4.18) piloté par l'application.
 *
 * Le kit DJI se pilote en vitesses (« virtual stick ») : pour un déplacement de quelques mètres,
 * on envoie une vitesse constante 10 fois par seconde pendant la durée voulue, puis on s'arrête.
 * Sans GPS (en intérieur), le Mini tient sa position grâce à ses capteurs du dessous :
 * il faut un sol texturé et éclairé.
 */
class DjiDrone(
    limits: Limits,
    private val link: DjiLink,
    private val log: (String) -> Unit,
) : DroneBase(limits) {
    companion object {
        const val SPEED = 0.5        // m/s à l'horizontale pour les petits pas (prudent en intérieur, dans une cage)
        const val MISSION_SPEED = 3.0 // m/s pour les longs segments d'une mission en extérieur
        const val VSPEED = 0.4       // m/s à la verticale
        const val YAW_RATE = 40.0    // °/s
    }

    override val name: String get() = link.model()

    override var flying: Boolean
        get() = link.flying()
        set(_) {} // l'état vient du drone

    @Volatile private var cancel = false

    /** Attend le résultat d'une commande DJI (null = réussite). */
    private fun call(timeoutS: Long = 10, cmd: (DjiLink.Done) -> Unit): String? {
        val latch = CountDownLatch(1)
        var err: String? = "pas de réponse du drone"
        cmd(DjiLink.Done { e ->
            err = e
            latch.countDown()
        })
        latch.await(timeoutS, TimeUnit.SECONDS)
        return err
    }

    override fun connect() {
        if (!link.isConnected()) throw IllegalStateException("drone DJI non connecté")
        applyLimits()
        call { link.enableVirtualStick(it) }?.let { log("mode pilotage par l'application : $it") }
        call(6) { link.autoExposure(false, it) }?.let { log("exposition auto de la caméra : $it") }
    }

    /** Caméra plus claire (+1 IL), pour une pièce peu éclairée. */
    fun brighter(on: Boolean): String? = call(6) { link.autoExposure(on, it) }

    override fun disconnect() {
        runCatching { call(3) { link.disableVirtualStick(it) } }
    }

    override fun applyLimits() = link.setLimits(limits.maxAltitudeM.toInt(), limits.maxDistanceM.toInt())

    override fun battery(): Int? = link.battery()
    override fun altitude(): Double = link.altitude().toDouble()

    private fun waitFlying(want: Boolean, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (link.flying() == want) return true
            if (!want && link.landingConfirmationNeeded()) call(5) { link.confirmLanding(it) }
            Thread.sleep(200)
        }
        return link.flying() == want
    }

    override fun doTakeoff() {
        var err = call { link.takeoff(it) }
        if (err != null) {
            // Certains drones refusent de décoller tant que le pilotage par l'application est actif :
            // on le coupe, on décolle, et on le réactive ensuite.
            log("décollage refusé ($err), nouvel essai sans le pilotage par l'application")
            call(5) { link.disableVirtualStick(it) }
            Thread.sleep(1000)
            err = call { link.takeoff(it) }
        }
        if (err != null) throw IllegalStateException("décollage refusé : $err. État : ${link.diagnostics()}")
        if (!waitFlying(true, 15_000)) throw IllegalStateException("décollage non confirmé. État : ${link.diagnostics()}")
        Thread.sleep(3000) // le Mini monte à ~1,2 m puis se stabilise
        // Le pilotage par l'application se réactive après le décollage.
        call { link.enableVirtualStick(it) }?.let { log("mode pilotage par l'application : $it") }
    }

    override fun doLand() {
        cancel = true
        call { link.land(it) }?.let { log("atterrissage : $it") }
        if (!waitFlying(false, 40_000)) log("atterrissage non confirmé")
    }

    /** Arrêt d'urgence : coupe les moteurs (le kit DJI ne le permet en général qu'au sol), sinon atterrit. */
    override fun doEmergency() {
        cancel = true
        val err = call(3) { link.motorsOff(it) }
        if (err != null) {
            log("coupure des moteurs refusée ($err) : atterrissage immédiat")
            call(3) { link.land(it) }
        }
    }

    override fun doCancelMove() {
        cancel = true
    }

    override fun doMove(forward: Double, right: Double, down: Double, yawRad: Double, waitS: Double) {
        cancel = false
        val yawDeg = Math.toDegrees(yawRad)
        val dist = hypot(forward, right)
        val speed = if (dist > 3) MISSION_SPEED else SPEED
        val t = max(max(dist / speed, abs(down) / VSPEED), max(abs(yawDeg) / YAW_RATE, 0.5))
        val vf = (forward / t).toFloat()
        val vr = (right / t).toFloat()
        val vu = (-down / t).toFloat()
        val vy = (yawDeg / t).toFloat()
        val end = System.currentTimeMillis() + (t * 1000).toLong()
        while (System.currentTimeMillis() < end && !cancel && link.flying()) {
            link.sendSticks(vr, vf, vy, vu)
            Thread.sleep(100)
        }
        // Freinage : vitesses nulles pendant une seconde
        repeat(10) {
            link.sendSticks(0f, 0f, 0f, 0f)
            Thread.sleep(100)
        }
        if (cancel) log("déplacement interrompu")
    }

    override fun doTilt(degrees: Int) {
        // La nacelle du Mini va de -90° (vers le bas) à 0° (droit devant).
        call(4) { link.gimbalPitch(degrees.coerceIn(-90, 0).toFloat(), it) }?.let { log("nacelle : $it") }
    }
}
