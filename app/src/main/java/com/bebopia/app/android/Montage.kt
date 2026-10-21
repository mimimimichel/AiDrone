package com.bebopia.app.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Montage : les meilleurs moments de chaque plan (le milieu, quelques secondes), mis bout à bout.
 * Sans réencodage : on recopie les images de la vidéo d'origine, chaque extrait commençant à l'image clé la plus proche.
 */
object Montage {
    /** [clips] : (début, fin) en secondes dans [input]. Renvoie null si tout va bien, sinon l'erreur. Bloquant. */
    @Suppress("UNUSED_PARAMETER")
    fun make(context: Context, input: File, clips: List<Pair<Double, Double>>, output: File): String? {
        if (clips.isEmpty()) return "aucun plan à monter"
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            ex.setDataSource(input.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return "pas de piste vidéo"
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val m = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val out = m.addTrack(fmt)
            m.start()
            val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(maxOf(size, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var outT = 0L
            var frameUs = 33_333L
            var written = 0
            for ((a, b) in clips) {
                val startUs = (a * 1_000_000).toLong()
                val endUs = (b * 1_000_000).toLong()
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var first = -1L
                var last = -1L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = ex.sampleTime
                    if (t > endUs) break
                    if (first < 0) first = t
                    if (last >= 0 && t > last) frameUs = t - last
                    last = t
                    info.set(0, n, outT + (t - first), if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(out, buf, info)
                    written++
                    ex.advance()
                }
                if (first >= 0) outT += (last - first) + frameUs
            }
            if (written == 0) "aucune image copiée" else null
        } catch (e: Exception) {
            e.message ?: "montage impossible"
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
