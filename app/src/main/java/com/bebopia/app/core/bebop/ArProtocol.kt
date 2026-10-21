package com.bebopia.app.core.bebop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constantes du protocole Parrot ARSDK3 (couche réseau ARNetworkAL). */
object Ar {
    const val TYPE_ACK = 1
    const val TYPE_DATA = 2
    const val TYPE_DATA_LOW_LATENCY = 3
    const val TYPE_DATA_WITH_ACK = 4

    const val BUF_PING = 0
    const val BUF_PONG = 1
    const val BUF_NON_ACK = 10   // contrôleur -> drone, sans accusé
    const val BUF_ACK = 11       // contrôleur -> drone, avec accusé
    const val BUF_EMERGENCY = 12 // contrôleur -> drone, urgence

    const val HEADER_SIZE = 7
}

class ArFrame(val type: Int, val bufferId: Int, val seq: Int, val payload: ByteArray)

object ArFrameCodec {
    /** Trame : type(1) buffer(1) séquence(1) taille totale(4, little-endian) + données. */
    fun encode(type: Int, bufferId: Int, seq: Int, payload: ByteArray): ByteArray {
        val size = Ar.HEADER_SIZE + payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .put(type.toByte())
            .put(bufferId.toByte())
            .put(seq.toByte())
            .putInt(size)
            .put(payload)
            .array()
    }

    /** Un datagramme UDP peut contenir plusieurs trames à la suite. */
    fun decodeAll(data: ByteArray, length: Int): List<ArFrame> {
        val out = ArrayList<ArFrame>()
        var off = 0
        while (off + Ar.HEADER_SIZE <= length) {
            val type = data[off].toInt() and 0xff
            val buffer = data[off + 1].toInt() and 0xff
            val seq = data[off + 2].toInt() and 0xff
            val size = ByteBuffer.wrap(data, off + 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < Ar.HEADER_SIZE || off + size > length) break
            out.add(ArFrame(type, buffer, seq, data.copyOfRange(off + Ar.HEADER_SIZE, off + size)))
            off += size
        }
        return out
    }
}

class ArCommand(val project: Int, val cls: Int, val id: Int, val args: ByteArray) {
    fun matches(project: Int, cls: Int, id: Int) = this.project == project && this.cls == cls && this.id == id
    fun reader() = ArReader(args)
    override fun toString() = "cmd($project,$cls,$id,${args.size}o)"
}

/** Encodage des arguments (little-endian, chaînes terminées par un octet nul). */
class ArArgs {
    private val out = ByteArrayOutputStream()
    private fun le(size: Int, fill: (ByteBuffer) -> Unit) = apply {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fill(b)
        out.write(b.array())
    }

    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun i32(v: Int) = le(4) { it.putInt(v) }
    fun f32(v: Float) = le(4) { it.putFloat(v) }
    fun f64(v: Double) = le(8) { it.putDouble(v) }
    fun str(s: String) = apply {
        out.write(s.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

class ArReader(args: ByteArray) {
    private val bb = ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(): Int = bb.get().toInt() and 0xff
    fun i32(): Int = bb.int
    fun f32(): Float = bb.float
    fun f64(): Double = bb.double
}

object ArCommands {
    /** Commande : projet(1) classe(1) id(2, little-endian) + arguments. */
    fun encode(project: Int, cls: Int, id: Int, args: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(4 + args.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(project.toByte())
            .put(cls.toByte())
            .putShort(id.toShort())
            .put(args)
            .array()

    fun decode(payload: ByteArray): ArCommand? {
        if (payload.size < 4) return null
        val id = (payload[2].toInt() and 0xff) or ((payload[3].toInt() and 0xff) shl 8)
        return ArCommand(payload[0].toInt() and 0xff, payload[1].toInt() and 0xff, id,
            payload.copyOfRange(4, payload.size))
    }
}

/** Commandes et événements du Bebop 2 utilisés par l'app (projets common=0 et ardrone3=1). */
object BebopCmd {
    private fun cmd(p: Int, c: Int, i: Int, a: ArArgs? = null) = ArCommands.encode(p, c, i, a?.bytes() ?: ByteArray(0))

    // common
    fun allSettings() = cmd(0, 2, 0)
    fun allStates() = cmd(0, 4, 0)
    fun currentDate(date: String) = cmd(0, 4, 1, ArArgs().str(date))
    fun currentTime(time: String) = cmd(0, 4, 2, ArArgs().str(time))

    // ardrone3.Piloting
    fun flatTrim() = cmd(1, 0, 0)
    fun takeOff() = cmd(1, 0, 1)
    fun landing() = cmd(1, 0, 3)
    fun emergency() = cmd(1, 0, 4)
    fun moveBy(dx: Float, dy: Float, dz: Float, dPsi: Float) =
        cmd(1, 0, 7, ArArgs().f32(dx).f32(dy).f32(dz).f32(dPsi))
    fun cancelMoveBy() = cmd(1, 0, 14)
    /** Pilotage aux manches : flag (1 = roulis/tangage actifs), roulis, tangage, lacet, gaz en % (-100 à 100). */
    fun pcmd(flag: Int, roll: Int, pitch: Int, yaw: Int, gaz: Int, seq: Int) =
        cmd(1, 0, 2, ArArgs().u8(flag).u8(roll).u8(pitch).u8(yaw).u8(gaz).i32(seq))

    // ardrone3.Camera
    fun cameraOrientation(tilt: Float, pan: Float) = cmd(1, 1, 1, ArArgs().f32(tilt).f32(pan))

    // ardrone3.PilotingSettings
    fun maxAltitude(m: Float) = cmd(1, 2, 0, ArArgs().f32(m))
    fun maxTilt(deg: Float) = cmd(1, 2, 1, ArArgs().f32(deg))
    fun maxDistance(m: Float) = cmd(1, 2, 3, ArArgs().f32(m))
    fun noFlyOverMaxDistance(on: Boolean) = cmd(1, 2, 4, ArArgs().u8(if (on) 1 else 0))

    // ardrone3.SpeedSettings
    fun maxVerticalSpeed(mps: Float) = cmd(1, 11, 0, ArArgs().f32(mps))
    fun maxRotationSpeed(degps: Float) = cmd(1, 11, 1, ArArgs().f32(degps))

    // ardrone3.MediaStreaming
    // MediaRecord : vidéo (0 = démarrer, 1 = arrêter) et photo, sur la mémoire du Bebop
    fun videoRecord(start: Boolean) = cmd(1, 7, 3, ArArgs().i32(if (start) 0 else 1))
    fun videoEnable(on: Boolean) = cmd(1, 21, 0, ArArgs().u8(if (on) 1 else 0))

    // Événements reçus
    fun isBattery(c: ArCommand) = c.matches(0, 5, 1)          // u8 pourcentage
    fun isFlyingState(c: ArCommand) = c.matches(1, 4, 1)      // enum (i32)
    fun isAlertState(c: ArCommand) = c.matches(1, 4, 2)       // enum (i32)
    fun isAltitude(c: ArCommand) = c.matches(1, 4, 8)         // double
    fun isMoveByEnd(c: ArCommand) = c.matches(1, 34, 0)       // 4 floats + enum erreur

    val FLYING_STATES = mapOf(
        0 to "au sol", 1 to "décollage", 2 to "stationnaire", 3 to "en vol",
        4 to "atterrissage", 5 to "urgence", 6 to "décollage manuel", 7 to "moteurs", 8 to "atterrissage d'urgence",
    )
    val ALERTS = mapOf(
        1 to "urgence utilisateur", 2 to "moteur coupé", 3 to "batterie critique",
        4 to "batterie faible", 5 to "angle trop important",
    )
}
