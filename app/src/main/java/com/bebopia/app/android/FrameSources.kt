package com.bebopia.app.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

/** Luminosité moyenne (0-255) d'une image, mesurée sur une version réduite. */
fun meanLuma(b: Bitmap): Double {
    val s = Bitmap.createScaledBitmap(b, 32, 18, true)
    val px = IntArray(32 * 18)
    s.getPixels(px, 0, 32, 0, 0, 32, 18)
    if (s !== b) s.recycle()
    return px.sumOf { c -> 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF) } / px.size
}

/** Gain pour ramener une image sombre vers une luminosité normale (1 = inchangé, 3,5 au plus). */
fun brightnessGain(mean: Double): Float = if (mean <= 0.5) 3.5f else (110.0 / mean).coerceIn(1.0, 3.5).toFloat()

/** Filtre de couleur qui multiplie la luminosité (null si inutile). */
fun gainFilter(gain: Float): ColorMatrixColorFilter? {
    if (gain < 1.12f) return null
    val m = ColorMatrix(floatArrayOf(
        gain, 0f, 0f, 0f, 4f,
        0f, gain, 0f, 0f, 4f,
        0f, 0f, gain, 0f, 4f,
        0f, 0f, 0f, 1f, 0f,
    ))
    return ColorMatrixColorFilter(m)
}

/** Éclaircit une image trop sombre (pour l'IA et le casque). Renvoie src si rien à faire. */
fun brighten(src: Bitmap): Bitmap {
    val filter = gainFilter(brightnessGain(meanLuma(src))) ?: return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = filter })
    return out
}

private fun toJpegBase64(src: Bitmap): String {
    val sized = if (src.width > AI_WIDTH) {
        Bitmap.createScaledBitmap(src, AI_WIDTH, (AI_WIDTH.toFloat() * src.height / src.width).toInt(), true)
    } else {
        src
    }
    val scaled = brighten(sized)
    if (scaled !== sized && sized !== src) sized.recycle()
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

private fun toGray(src: Bitmap, width: Int = GUARD_W): Gray {
    val h = maxOf(2, width * src.height / src.width)
    val b = if (src.width != width || src.height != h) Bitmap.createScaledBitmap(src, width, h, true) else src
    val px = IntArray(width * h)
    b.getPixels(px, 0, width, 0, 0, width, h)
    if (b !== src) b.recycle()
    val out = FloatArray(px.size)
    for (i in px.indices) {
        val c = px[i]
        out[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }
    return Gray(width, h, out)
}

/** Capture sur le thread de l'interface, en attendant au plus 300 ms (le détecteur doit rester rapide). */
private fun grayOnMain(width: Int = GUARD_W, grab: () -> Bitmap?): Gray? {
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
        toGray(b, width)
    } finally {
        b.recycle()
    }
}

/** Vidéo du Bebop, en petit, pour le détecteur d'obstacles. */
class TextureGray(
    private val view: () -> TextureView?,
    private val hasVideo: () -> Boolean,
    /** Largeur de l'image (160 pour le détecteur d'obstacles, plus pour le suivi d'une cible). */
    private val width: Int = GUARD_W,
) : GrayFrames {
    override fun grab(): Gray? {
        if (!hasVideo()) return null
        return grayOnMain(width) {
            val v = view()
            if (v != null && v.isAvailable) v.getBitmap(width, width * 9 / 16) else null
        }
    }
}

/** Caméra du téléphone (simulation), pour le détecteur d'obstacles. */
class PreviewGray(private val view: () -> PreviewView?, private val width: Int = GUARD_W) : GrayFrames {
    override fun grab(): Gray? = grayOnMain(width) { view()?.bitmap }
}
