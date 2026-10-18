package com.bebopia.app.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.bebopia.app.core.bebop.ArNetwork
import com.bebopia.app.core.bebop.H264Depacketizer
import com.bebopia.app.core.bebop.Rtcp
import com.bebopia.app.core.bebop.RtpPacket
import com.bebopia.app.core.bebop.RtpSeqTracker
import com.bebopia.app.core.bebop.SocketBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Reçoit le flux vidéo du Bebop 2 (H.264 sur RTP, port 55004), le décode avec le
 * décodeur matériel du téléphone et l'affiche sur [surface].
 */
class BebopVideo(
    private val binder: SocketBinder,
    private val droneHost: String,
    private val serverControlPort: Int,
    private val log: (String) -> Unit,
) {
    @Volatile var surface: Surface? = null
    @Volatile var lastFrameAt = 0L
        private set
    @Volatile private var running = false

    private var streamSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private val ourSsrc = Random.nextInt().toLong() and 0xffffffffL
    private var depacketizer: H264Depacketizer? = null
    private var announced = false

    val hasRecentFrame: Boolean get() = System.currentTimeMillis() - lastFrameAt < 2000

    fun start() {
        if (running) return
        running = true
        thread(name = "bebop-video", isDaemon = true) { runLoop() }
    }

    fun stop() {
        running = false
        streamSocket?.close()
        controlSocket?.close()
    }

    private fun openSocket(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        binder.bind(s)
        return s
    }

    private fun runLoop() {
        try {
            val sock = openSocket(ArNetwork.CLIENT_STREAM_PORT)
            sock.soTimeout = 1000
            sock.receiveBufferSize = 2 * 1024 * 1024
            streamSocket = sock
            val ctrl = openSocket(ArNetwork.CLIENT_CONTROL_PORT)
            controlSocket = ctrl
            val droneAddress = InetAddress.getByName(droneHost)
            val dep = H264Depacketizer { frame, ts -> decode(frame, ts) }
            depacketizer = dep
            val tracker = RtpSeqTracker()
            val buf = ByteArray(65536)
            var sourceSsrc = -1L
            var lastRtcp = 0L
            log("vidéo : en attente du flux")
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val rtp = RtpPacket.parse(buf, p.length)
                    if (rtp != null) {
                        sourceSsrc = rtp.ssrc
                        tracker.update(rtp.seq)
                        dep.push(rtp)
                    }
                } catch (e: SocketTimeoutException) {
                    // pas de vidéo pendant 1 s
                }
                val now = System.currentTimeMillis()
                if (sourceSsrc >= 0 && now - lastRtcp > 1000) {
                    lastRtcp = now
                    val rr = Rtcp.receiverReport(ourSsrc, sourceSsrc, tracker.extendedHighest)
                    try {
                        ctrl.send(DatagramPacket(rr, rr.size, droneAddress, serverControlPort))
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            if (running) log("vidéo : ${e.message}")
        } finally {
            releaseCodec()
            streamSocket?.close()
            controlSocket?.close()
        }
    }

    private fun decode(frame: ByteArray, rtpTimestamp: Long) {
        val target = surface
        if (codec != null && codecSurface !== target) releaseCodec() // l'écran a été recréé
        if (target == null) return
        if (codec == null) {
            val dep = depacketizer ?: return
            val sps = dep.sps ?: return
            val pps = dep.pps ?: return
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 856, 480)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(H264Depacketizer.START_CODE + sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(H264Depacketizer.START_CODE + pps))
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(format, target, null, 0)
                c.start()
                codec = c
                codecSurface = target
            } catch (e: Exception) {
                log("vidéo : décodeur indisponible (${e.message})")
                releaseCodec()
                return
            }
        }
        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val input = c.getInputBuffer(inIndex)
                if (input == null || frame.size > input.capacity()) {
                    c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                } else {
                    input.clear()
                    input.put(frame)
                    c.queueInputBuffer(inIndex, 0, frame.size, rtpTimestamp * 1_000_000L / 90_000L, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 0)
                when {
                    outIndex >= 0 -> {
                        c.releaseOutputBuffer(outIndex, true)
                        lastFrameAt = System.currentTimeMillis()
                        if (!announced) {
                            announced = true
                            log("vidéo : images reçues")
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    else -> Unit // format ou tampons modifiés : on continue
                }
            }
        } catch (e: Exception) {
            log("vidéo : erreur de décodage (${e.message}), redémarrage du décodeur")
            releaseCodec()
        }
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        codecSurface = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }
}
