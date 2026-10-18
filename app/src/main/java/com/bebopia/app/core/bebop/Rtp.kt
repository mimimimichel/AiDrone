package com.bebopia.app.core.bebop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Paquet RTP (le flux vidéo du Bebop 2 arrive en H.264 sur RTP, port 55004). */
class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val seq: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
) {
    companion object {
        fun parse(buf: ByteArray, len: Int): RtpPacket? {
            if (len < 12) return null
            val b0 = buf[0].toInt() and 0xff
            if (b0 ushr 6 != 2) return null
            val padding = b0 and 0x20 != 0
            val extension = b0 and 0x10 != 0
            val csrcCount = b0 and 0x0f
            val b1 = buf[1].toInt() and 0xff
            val seq = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
            val ts = u32(buf, 4)
            val ssrc = u32(buf, 8)
            var off = 12 + csrcCount * 4
            if (extension) {
                if (off + 4 > len) return null
                val extWords = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
                off += 4 + extWords * 4
            }
            var end = len
            if (padding) end -= buf[len - 1].toInt() and 0xff
            if (off >= end) return null
            return RtpPacket(b1 and 0x80 != 0, b1 and 0x7f, seq, ts, ssrc, buf.copyOfRange(off, end))
        }

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
                ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    }
}

/**
 * Reconstitue les images H.264 (format Annex B, avec codes de début) à partir des paquets RTP.
 * Gère les NAL simples, STAP-A (24) et FU-A (28).
 */
class H264Depacketizer(private val onFrame: (frame: ByteArray, rtpTimestamp: Long) -> Unit) {
    companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        fun containsNalType(frame: ByteArray, type: Int): Boolean {
            var i = 0
            while (i + 4 < frame.size) {
                if (frame[i].toInt() == 0 && frame[i + 1].toInt() == 0 && frame[i + 2].toInt() == 0 && frame[i + 3].toInt() == 1) {
                    if (frame[i + 4].toInt() and 0x1f == type) return true
                    i += 4
                } else {
                    i++
                }
            }
            return false
        }
    }

    @Volatile var sps: ByteArray? = null
        private set
    @Volatile var pps: ByteArray? = null
        private set

    private val frame = ByteArrayOutputStream()
    private var fu: ByteArrayOutputStream? = null
    private var frameTs = -1L
    private var lastSeq = -1

    fun push(p: RtpPacket) {
        if (lastSeq >= 0 && ((lastSeq + 1) and 0xffff) != p.seq) fu = null // paquet perdu : fragment abandonné
        lastSeq = p.seq
        if (frameTs != -1L && p.timestamp != frameTs) flush()
        frameTs = p.timestamp

        val pl = p.payload
        if (pl.isEmpty()) return
        when (val type = pl[0].toInt() and 0x1f) {
            in 1..23 -> addNal(pl, 0, pl.size)
            24 -> { // STAP-A
                var off = 1
                while (off + 2 <= pl.size) {
                    val n = ((pl[off].toInt() and 0xff) shl 8) or (pl[off + 1].toInt() and 0xff)
                    off += 2
                    if (n == 0 || off + n > pl.size) break
                    addNal(pl, off, n)
                    off += n
                }
            }
            28 -> { // FU-A
                if (pl.size < 2) return
                val indicator = pl[0].toInt() and 0xff
                val header = pl[1].toInt() and 0xff
                if (header and 0x80 != 0) {
                    fu = ByteArrayOutputStream().apply { write((indicator and 0xe0) or (header and 0x1f)) }
                }
                val cur = fu ?: return
                cur.write(pl, 2, pl.size - 2)
                if (header and 0x40 != 0) {
                    val nal = cur.toByteArray()
                    fu = null
                    addNal(nal, 0, nal.size)
                }
            }
            else -> if (type != 0) Unit
        }
        if (p.marker) flush()
    }

    private fun addNal(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        when (b[off].toInt() and 0x1f) {
            7 -> sps = b.copyOfRange(off, off + len)
            8 -> pps = b.copyOfRange(off, off + len)
        }
        frame.write(START_CODE)
        frame.write(b, off, len)
    }

    private fun flush() {
        if (frame.size() > 0) {
            onFrame(frame.toByteArray(), frameTs)
            frame.reset()
        }
        frameTs = -1L
    }
}

/** Suivi du numéro de séquence RTP étendu (pour les rapports RTCP). */
class RtpSeqTracker {
    private var cycles = 0L
    private var maxSeq = -1
    var packets = 0L
        private set

    fun update(seq: Int) {
        packets++
        if (maxSeq < 0) {
            maxSeq = seq
            return
        }
        val delta = (seq - maxSeq) and 0xffff
        if (delta in 1..0x7fff) {
            if (seq < maxSeq) cycles += 65536
            maxSeq = seq
        }
    }

    val extendedHighest: Long get() = cycles + maxOf(maxSeq, 0)
}

object Rtcp {
    /** Rapport de réception RTCP minimal (RR, type 201, un bloc). */
    fun receiverReport(senderSsrc: Long, sourceSsrc: Long, extendedHighestSeq: Long): ByteArray =
        ByteBuffer.allocate(32)
            .put(0x81.toByte())         // V=2, P=0, RC=1
            .put(201.toByte())          // RR
            .putShort(7)                // longueur en mots de 32 bits - 1
            .putInt(senderSsrc.toInt())
            .putInt(sourceSsrc.toInt())
            .putInt(0)                  // fraction perdue + cumul perdu
            .putInt(extendedHighestSeq.toInt())
            .putInt(0)                  // gigue
            .putInt(0)                  // LSR
            .putInt(0)                  // DLSR
            .array()
}
