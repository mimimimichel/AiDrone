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
        const val INDOOR_SPEED = 0.3 // m/s en intérieur
        const val INDOOR_YAW = 30.0  // °/s en intérieur
    }

    override val name: String get() = link.model()

    override var flying: Boolean
        get() = link.flying()
        set(_) {} // l'état vient du drone

    @Volatile private var cancel = false
    @Volatile private var moving = false
    @Volatile private var manual = false

    override val manualControl: Boolean get() = manual

    /** Pilote aux commandes : on coupe le pilotage par l'application (les manches reprennent effet). */
    override fun setManual(on: Boolean) {
        if (on == manual) return
        manual = on
        if (on) {
            cancel = true
            headFollow = false
            call(4) { link.disableVirtualStick(it) }?.let { log("reprise en main : $it") }
        } else {
            call(4) { link.enableVirtualStick(it) }?.let { log("pilotage par l'application : $it") }
            droneYawCenter = link.heading()
            headYawCenter = headYaw
        }
    }

    // ---- suivi de la tête (casque) ----------------------------------------------------------
    @Volatile private var headSeenAt = 0L
    @Volatile private var headFollow = false
    @Volatile private var headYaw = 0.0
    @Volatile private var headYawCenter = 0.0
    @Volatile private var droneYawCenter: Double? = null
    private var pitchSent = 999f
    private var pitchSentAt = 0L
    private var yawLoop: Thread? = null

    override fun headLook(pitch: Double, yaw: Double, follow: Boolean, recenter: Boolean) {
        val now = System.currentTimeMillis()
        if (headPaused) {
            // Manœuvre automatique : la tête ne commande plus rien ; au retour, on recentre sur le cap actuel.
            headFollow = false
            droneYawCenter = null
            return
        }
        headSeenAt = now
        // Nacelle du Mini : de 0° (devant) à -90° (vers le sol)
        val p = pitch.coerceIn(-90.0, 0.0).toFloat()
        if (abs(p - pitchSent) >= 1f && now - pitchSentAt >= 60) {
            link.gimbalPitchFast(p)
            pitchSent = p
            pitchSentAt = now
        }
        if (recenter || droneYawCenter == null || (follow && !headFollow)) {
            droneYawCenter = link.heading()
            headYawCenter = yaw
        }
        headYaw = yaw
        headFollow = follow && !manual
        if (follow && yawLoop == null) {
            yawLoop = Thread({ followLoop() }, "suivi-tete").apply { isDaemon = true; start() }
        }
    }

    private fun angleDiff(a: Double): Double = ((a + 540.0) % 360.0) - 180.0

    /**
     * Le drone prend le même cap que la tête, en 1 pour 1 d'après sa boussole :
     * 90° de tête = 90° de drone. Correction 20 fois par seconde.
     */
    private fun followLoop() {
        var sending = false
        while (true) {
            Thread.sleep(50)
            val active = headFollow && !manual && System.currentTimeMillis() - headSeenAt < 1000 && !moving && link.flying()
            val center = droneYawCenter
            if (!active || center == null) {
                if (sending) {
                    link.sendSticks(0f, 0f, 0f, 0f)
                    sending = false
                }
                continue
            }
            link.sendSticks(0f, 0f, followRate(center).toFloat(), 0f)
            sending = true
        }
    }

    /** Vitesse de rotation pour prendre le cap de la tête : proportionnelle à l'écart, douce à l'arrivée. */
    private fun followRate(center: Double): Double {
        val err = angleDiff(center + (headYaw - headYawCenter) - link.heading())
        return if (abs(err) < 1.5) 0.0 else (err * 2.5).coerceIn(-100.0, 100.0)
    }

    /** Le drone suit la tête du pilote (casque) : il continue pendant les déplacements. */
    private fun following(): Boolean =
        headFollow && !manual && System.currentTimeMillis() - headSeenAt < 1000 && droneYawCenter != null

    override fun bodyCompass(): Double = moveRef ?: link.heading()
    override val handlesViewFrame: Boolean get() = true
    override fun moveFrameHeading(): Double = com.bebopia.app.core.norm180(bodyCompass() - takeoffCompass)

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

    override fun telemetry(): Map<String, Any?> {
        val base = super.telemetry().toMutableMap()
        val t = link.telemetry()
        // Sans GPS, le cap du départ vient de l'estimation interne : on le recale sur la boussole.
        if (t["homeBearing"] == null) {
            val rel = (base["homeBearing"] as Double) - heading
            base["homeBearing"] = link.heading() + rel
        }
        base.putAll(t)
        return base
    }


    // ---- position, regard, vitesses continues -------------------------------------------------
    override fun compass(): Double = link.heading()
    override fun syncHeading() = setHeadingFromCompass(link.heading())
    override fun takeoffHeading(): Double = link.heading()
    override val hasGps: Boolean get() = link.position() != null && link.home() != null
    override fun gps(): Pair<Double, Double>? = link.position()?.let { it[0] to it[1] }
    override fun homeGps(): Pair<Double, Double>? = link.home()?.let { it[0] to it[1] }

    override fun positionNE(): Pair<Double, Double> {
        val p = link.position()
        val h = link.home()
        if (p == null || h == null) return super.positionNE()
        val n = (p[0] - h[0]) * 111320.0
        val e = (p[1] - h[1]) * 111320.0 * kotlin.math.cos(Math.toRadians(h[0]))
        return n to e
    }

    override fun gimbalPitch(): Double = link.gimbalPitchNow()

    override fun lookHeading(): Double {
        val c = droneYawCenter
        return if (headFollow && headRecent && c != null) com.bebopia.app.core.norm180(c + (headYaw - headYawCenter)) else link.heading()
    }

    override fun signalQuality(): Int? = link.signalQuality().takeIf { it >= 0 }
    override val recording: Boolean get() = link.recording()
    override fun startRecording(): String? = call(8) { link.record(true, it) }
    override fun stopRecording(): String? = call(5) { link.record(false, it) }

    override val supportsVelocity: Boolean get() = true
    @Volatile private var vel = DoubleArray(4)
    @Volatile private var velUntil = 0L
    private var velLoop: Thread? = null

    override fun velocity(forward: Double, right: Double, up: Double, yawRate: Double, holdMs: Long) {
        if (manual) throw IllegalStateException("le pilote a repris la main avec la radiocommande")
        val f = if (forwardBlocked()) minOf(forward, 0.0) else forward
        cancel = false
        vel = doubleArrayOf(f.coerceIn(-8.0, 8.0), right.coerceIn(-8.0, 8.0), up.coerceIn(-2.0, 2.0), yawRate.coerceIn(-100.0, 100.0))
        velUntil = System.currentTimeMillis() + holdMs
        moving = true
        setForward(f > 0.2)
        synchronized(this) {
            if (velLoop == null) velLoop = Thread({ velocityLoop() }, "vitesse").apply { isDaemon = true; start() }
        }
    }

    @Volatile private var stickFollow = false

    /**
     * Manettes avec le casque : le manche gauche tourne le cap de vol ; la tête regarde librement autour.
     * Avant / côtés sont par rapport au cap de vol, pas à la tête : on peut avancer tout droit en regardant à droite.
     */
    override fun sticks(forward: Double, right: Double, up: Double, yawRate: Double) {
        var yr = yawRate
        var f = forward
        var r = right
        if (following()) {
            if (abs(yawRate) > 1) droneYawCenter = com.bebopia.app.core.norm180(droneYawCenter!! + yawRate * 0.05)
            val c = droneYawCenter!!
            yr = followRate(c)
            stickFollow = true
            // Le drone regarde ailleurs (tête tournée) : on tourne le vecteur vitesse du cap de vol vers son repère actuel.
            val d = Math.toRadians(angleDiff(c - link.heading()))
            f = forward * kotlin.math.cos(d) - right * kotlin.math.sin(d)
            r = forward * kotlin.math.sin(d) + right * kotlin.math.cos(d)
        }
        velocity(f, r, up, yr, holdMs = 300)
    }

    /** Cap de vol aux manettes (différent de la caméra quand le pilote tourne la tête), null hors casque. */
    override fun travelHeading(): Double? = if (following()) droneYawCenter else null

    override fun stopVelocity() {
        if (velUntil != 0L) velUntil = 1L
    }

    private fun velocityLoop() {
        var last = System.currentTimeMillis()
        while (true) {
            Thread.sleep(100)
            val now = System.currentTimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val until = velUntil
            if (until == 0L) continue
            if (cancel || now > until || manual || !link.flying()) {
                velUntil = 0L
                setForward(false)
                if (!manual) repeat(8) { link.sendSticks(0f, 0f, 0f, 0f); Thread.sleep(100) }
                moving = false
                if (!stickFollow) {
                    droneYawCenter = link.heading()
                    headYawCenter = headYaw
                }
                stickFollow = false
                continue
            }
            val v = vel
            link.sendSticks(v[1].toFloat(), v[0].toFloat(), v[3].toFloat(), v[2].toFloat())
            syncHeading()
            integrate(v[0], v[1], v[2], v[3], dt)
        }
    }

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
        moving = true
        try {
            moveNow(forward, right, down, yawRad, following())
        } finally {
            moving = false
        }
    }

    private fun moveNow(forward: Double, right: Double, down: Double, yawRad: Double, follow: Boolean) {
        if (manual) throw IllegalStateException("le pilote a repris la main avec la radiocommande")
        cancel = false
        val yawDeg = Math.toDegrees(yawRad)
        // Repère du déplacement : la vue au moment de l'ordre (fixe pendant tout le déplacement)
        val ref = bodyCompass()
        // Le drone suit la tête : une rotation demandée décale la correspondance tête -> drone d'autant.
        if (follow && yawDeg != 0.0) droneYawCenter = com.bebopia.app.core.norm180(droneYawCenter!! + yawDeg)
        val dist = hypot(forward, right)
        // En intérieur : lent et doux
        val speed = if (indoor) INDOOR_SPEED else if (dist > 3) MISSION_SPEED else SPEED
        val t = max(max(dist / speed, abs(down) / VSPEED), max(abs(yawDeg) / (if (indoor) INDOOR_YAW else YAW_RATE), 0.5))
        val vf = (forward / t).toFloat()
        val vr = (right / t).toFloat()
        val vu = (-down / t).toFloat()
        val vy = (yawDeg / t).toFloat()
        val end = System.currentTimeMillis() + (t * 1000).toLong()
        while (System.currentTimeMillis() < end && !cancel && link.flying()) {
            // Le drone tourne (tête ou rotation demandée) : la direction du déplacement reste celle de la vue au
            // moment de l'ordre, on tourne donc le vecteur vitesse dans le repère actuel du drone.
            val d = Math.toRadians(angleDiff(ref - link.heading()))
            val bf = vf * kotlin.math.cos(d) - vr * kotlin.math.sin(d)
            val br = vf * kotlin.math.sin(d) + vr * kotlin.math.cos(d)
            val yr = if (follow) followRate(droneYawCenter!!).toFloat() else vy
            link.sendSticks(br.toFloat(), bf.toFloat(), yr, vu)
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
