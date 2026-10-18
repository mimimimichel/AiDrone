package com.bebopia.app.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.TextureView
import androidx.camera.view.PreviewView
import com.bebopia.app.core.ai.Eyes
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
