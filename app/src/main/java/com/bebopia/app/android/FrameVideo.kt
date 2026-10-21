package com.bebopia.app.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Vidéo faite d'images prises une à une (hyperlapse) : chaque image ajoutée devient une image de la vidéo, à [fps] images
 * par seconde. Une image prise chaque seconde -> vidéo accélérée 30 fois. Encodage H.264 matériel du téléphone.
 */
class FrameVideo(private val file: File, private val w: Int = 1280, private val h: Int = 720, private val fps: Int = 30) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: android.view.Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var frames = 0L
    private var written = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (android.os.Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    val count: Long get() = frames

    @Synchronized
    fun add(bmp: Bitmap) {
        val c = surface.lockHardwareCanvas()
        try { c.drawBitmap(bmp, null, Rect(0, 0, w, h), null) } finally { surface.unlockCanvasAndPost(c) }
        frames++
        drain(false)
    }

    private fun drain(end: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        // Horodatage régulier (l'image prise toutes les secondes devient 1/30 s de vidéo)
                        info.presentationTimeUs = written * 1_000_000L / fps
                        written++
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Termine la vidéo. Renvoie le fichier, ou null s'il n'y a pas assez d'images. */
    @Synchronized
    fun finish(): File? {
        runCatching { codec.signalEndOfInputStream(); drain(true) }
        runCatching { codec.stop(); codec.release() }
        runCatching { if (track >= 0) muxer.stop(); muxer.release() }
        surface.release()
        if (written < 3) { file.delete(); return null }
        return file
    }
}
