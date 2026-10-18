package com.bebopia.app.core.bebop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Permet d'attacher les sockets au Wi-Fi du drone (côté Android). */
interface SocketBinder {
    fun bind(socket: Socket) {}
    fun bind(socket: DatagramSocket) {}
}

object NoBinder : SocketBinder

/**
 * Liaison avec le Bebop : handshake TCP (port 44444) puis échanges UDP.
 * On écoute sur 43210, on envoie vers le port donné par le drone (54321 en général).
 */
class ArNetwork(
    private val binder: SocketBinder,
    private val log: (String) -> Unit,
    private val onCommand: (ArCommand) -> Unit,
    private val onLinkLost: () -> Unit,
    val host: String = DEFAULT_HOST,
) {
    companion object {
        const val DEFAULT_HOST = "192.168.42.1"
        const val DISCOVERY_PORT = 44444
        const val D2C_PORT = 43210
        const val CLIENT_STREAM_PORT = 55004
        const val CLIENT_CONTROL_PORT = 55005
        const val LINK_TIMEOUT_MS = 5000L

        fun intField(json: String, name: String): Int? =
            Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    class Handshake(val c2dPort: Int, val streamControlPort: Int, val raw: String)

    @Volatile private var socket: DatagramSocket? = null
    private var droneAddress: InetAddress? = null
    @Volatile private var c2dPort = 54321
    private val seqLock = Any()
    private val seq = IntArray(256)
    private val pendingAcks = ConcurrentHashMap<Int, CountDownLatch>()
    @Volatile private var running = false
    @Volatile private var lastRx = 0L
    @Volatile var linkUp = false
        private set

    fun connect(): Handshake {
        val udp = DatagramSocket(null)
        udp.reuseAddress = true
        udp.bind(InetSocketAddress(D2C_PORT))
        try {
            binder.bind(udp)
            udp.soTimeout = 500
            droneAddress = InetAddress.getByName(host)
            socket = udp
            val hs = handshake()
            c2dPort = hs.c2dPort
            running = true
            lastRx = System.currentTimeMillis()
            linkUp = true
            thread(name = "bebop-rx", isDaemon = true) { rxLoop() }
            return hs
        } catch (e: Exception) {
            udp.close()
            socket = null
            throw e
        }
    }

    private fun handshake(): Handshake {
        Socket().use { s ->
            binder.bind(s)
            s.connect(InetSocketAddress(host, DISCOVERY_PORT), 5000)
            s.soTimeout = 5000
            val req = "{\"controller_type\":\"computer\",\"controller_name\":\"BebopIA\"," +
                "\"d2c_port\":$D2C_PORT,\"arstream2_client_stream_port\":$CLIENT_STREAM_PORT," +
                "\"arstream2_client_control_port\":$CLIENT_CONTROL_PORT}"
            val output = s.getOutputStream()
            output.write(req.toByteArray(Charsets.UTF_8))
            output.flush()

            val input = s.getInputStream()
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (buf.size() < 16384) {
                val n = try { input.read(chunk) } catch (e: SocketTimeoutException) { -1 }
                if (n <= 0) break
                buf.write(chunk, 0, n)
                if (String(buf.toByteArray(), Charsets.UTF_8).contains('}')) break
            }
            val raw = String(buf.toByteArray(), Charsets.UTF_8).trim('\u0000', ' ', '\n', '\r')
            if (!raw.contains('{')) throw IOException("le drone n'a pas répondu à la demande de connexion")
            val status = intField(raw, "status") ?: 0
            if (status != 0) {
                throw IOException("le drone refuse la connexion (code $status) : une autre app ou la Skycontroller est peut-être connectée")
            }
            return Handshake(
                intField(raw, "c2d_port") ?: 54321,
                intField(raw, "arstream2_server_control_port") ?: 5005,
                raw,
            )
        }
    }

    private fun rxLoop() {
        val buf = ByteArray(65536)
        while (running) {
            val udp = socket ?: break
            try {
                val p = DatagramPacket(buf, buf.size)
                udp.receive(p)
                lastRx = System.currentTimeMillis()
                if (!linkUp) {
                    linkUp = true
                    log("liaison avec le drone rétablie")
                }
                for (f in ArFrameCodec.decodeAll(buf, p.length)) handleFrame(f)
            } catch (e: SocketTimeoutException) {
                // pas de paquet pendant 500 ms : on vérifie juste la liaison
            } catch (e: Exception) {
                if (running) log("réception : ${e.message}")
            }
            if (running && linkUp && System.currentTimeMillis() - lastRx > LINK_TIMEOUT_MS) {
                linkUp = false
                log("plus de nouvelles du drone depuis 5 s")
                onLinkLost()
            }
        }
    }

    private fun ackKey(ackBuffer: Int, seqNo: Int) = ackBuffer * 256 + seqNo

    private fun handleFrame(f: ArFrame) {
        when {
            f.type == Ar.TYPE_ACK -> {
                if (f.payload.isNotEmpty()) {
                    pendingAcks[ackKey(f.bufferId, f.payload[0].toInt() and 0xff)]?.countDown()
                }
            }
            f.bufferId == Ar.BUF_PING -> sendFrame(Ar.TYPE_DATA, Ar.BUF_PONG, f.payload)
            f.bufferId == Ar.BUF_PONG -> Unit
            else -> {
                if (f.type == Ar.TYPE_DATA_WITH_ACK) {
                    sendFrame(Ar.TYPE_ACK, (f.bufferId + 128) % 256, byteArrayOf(f.seq.toByte()))
                }
                val cmd = ArCommands.decode(f.payload) ?: return
                try {
                    onCommand(cmd)
                } catch (e: Exception) {
                    log("événement $cmd illisible : ${e.message}")
                }
            }
        }
    }

    private fun nextSeq(buffer: Int): Int = synchronized(seqLock) {
        seq[buffer] = (seq[buffer] + 1) and 0xff
        seq[buffer]
    }

    private fun sendRaw(bytes: ByteArray) {
        val udp = socket ?: throw IOException("drone non connecté")
        udp.send(DatagramPacket(bytes, bytes.size, droneAddress, c2dPort))
    }

    private fun sendFrame(type: Int, buffer: Int, payload: ByteArray): Int {
        val s = nextSeq(buffer)
        try {
            sendRaw(ArFrameCodec.encode(type, buffer, s, payload))
        } catch (e: IOException) {
            log("envoi : ${e.message}")
        }
        return s
    }

    /** Commande sans accusé de réception (buffer 10). */
    fun send(command: ByteArray) {
        sendFrame(Ar.TYPE_DATA, Ar.BUF_NON_ACK, command)
    }

    /** Commande avec accusé de réception : renvoyée jusqu'à ce que le drone confirme. Bloquant. */
    fun sendWithAck(command: ByteArray, buffer: Int = Ar.BUF_ACK, retries: Int = 4, timeoutMs: Long = 400): Boolean {
        val s = nextSeq(buffer)
        val key = ackKey((buffer + 128) % 256, s)
        val latch = CountDownLatch(1)
        pendingAcks[key] = latch
        val frame = ArFrameCodec.encode(Ar.TYPE_DATA_WITH_ACK, buffer, s, command)
        try {
            repeat(retries) {
                sendRaw(frame)
                if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return true
            }
            return false
        } catch (e: IOException) {
            log("envoi : ${e.message}")
            return false
        } finally {
            pendingAcks.remove(key)
        }
    }

    fun close() {
        running = false
        linkUp = false
        socket?.close()
        socket = null
    }
}
