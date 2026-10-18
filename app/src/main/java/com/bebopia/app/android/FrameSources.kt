package com.bebopia.app.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.TextureView
import androidx.camera.view.PreviewView
import com.bebopia.app.core.ai.Eyes
import com.bebopia.app.core.vision.Gray
import com.bebopia.app.core.vision.GrayFrames
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val AI_WIDTH = 768

private fun toJpegBase64(src: Bitmap): String {
    val scaled = if (src.width > AI_WIDTH) {
        Bitmap.createScaledBitmap(src, AI_WIDTH, (AI_WIDTH.toFloat() * src.height / src.width).toInt(), true)
    } else {
        src
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (scaled !== src) scaled.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Capture une image sur le thread de l'interface (obligatoire pour ces vues), en attendant au plus 2 s. */
private fun captureOnMain(grab: () -> Bitmap?): String? {
    val latch = CountDownLatch(1)
    var bitmap: Bitmap? = null
    Handler(Looper.getMainLooper()).post {
        try {
            bitmap = grab()
        } catch (e: Exception) {
            bitmap = null
        } finally {
            latch.countDown()
        }
    }
    if (!latch.await(2, TimeUnit.SECONDS)) return null
    val b = bitmap ?: return null
    return try {
        toJpegBase64(b)
    } finally {
        b.recycle()
    }
}

/** Les yeux de l'IA en vol : l'image affichée du flux vidéo du Bebop. */
class TextureEyes(
    private val view: () -> TextureView?,
    private val hasVideo: () -> Boolean,
) : Eyes {
    override fun captureJpegBase64(): String? {
        if (!hasVideo()) return null
        return captureOnMain {
            val v = view()
            if (v != null && v.isAvailable) v.getBitmap(AI_WIDTH, AI_WIDTH * 9 / 16) else null
        }
    }
}

/** Les yeux de l'IA en simulation : la caméra du téléphone. */
class PreviewEyes(private val view: () -> PreviewView?) : Eyes {
    override fun captureJpegBase64(): String? = captureOnMain { view()?.bitmap }
}

// ---- petites images en gris pour le détecteur d'obstacles -----------------------------------
private const val GUARD_W = 160

private fun toGray(src: Bitmap): Gray {
    val h = maxOf(2, GUARD_W * src.height / src.width)
    val b = if (src.width != GUARD_W || src.height != h) Bitmap.createScaledBitmap(src, GUARD_W, h, true) else src
    val px = IntArray(GUARD_W * h)
    b.getPixels(px, 0, GUARD_W, 0, 0, GUARD_W, h)
    if (b !== src) b.recycle()
    val out = FloatArray(px.size)
    for (i in px.indices) {
        val c = px[i]
        out[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }
    return Gray(GUARD_W, h, out)
}

/** Capture sur le thread de l'interface, en attendant au plus 300 ms (le détecteur doit rester rapide). */
private fun grayOnMain(grab: () -> Bitmap?): Gray? {
    val latch = CountDownLatch(1)
    var bitmap: Bitmap? = null
    Handler(Looper.getMainLooper()).post {
        try {
            bitmap = grab()
        } catch (e: Exception) {
            bitmap = null
        } finally {
            latch.countDown()
        }
    }
    if (!latch.await(300, TimeUnit.MILLISECONDS)) return null
    val b = bitmap ?: return null
    return try {
        toGray(b)
    } finally {
        b.recycle()
    }
}

/** Vidéo du Bebop, en petit, pour le détecteur d'obstacles. */
class TextureGray(
    private val view: () -> TextureView?,
    private val hasVideo: () -> Boolean,
) : GrayFrames {
    override fun grab(): Gray? {
        if (!hasVideo()) return null
        return grayOnMain {
            val v = view()
            if (v != null && v.isAvailable) v.getBitmap(GUARD_W, GUARD_W * 9 / 16) else null
        }
    }
}

/** Caméra du téléphone (simulation), pour le détecteur d'obstacles. */
class PreviewGray(private val view: () -> PreviewView?) : GrayFrames {
    override fun grab(): Gray? = grayOnMain { view()?.bitmap }
}
