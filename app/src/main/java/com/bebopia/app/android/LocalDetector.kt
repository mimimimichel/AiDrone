package com.bebopia.app.android

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bebopia.app.core.vision.Box
import com.bebopia.app.core.vision.FastDetector
import com.bebopia.app.core.vision.Seen
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Détecteur d'objets sur le téléphone (MediaPipe, modèle EfficientDet-Lite2 entraîné sur COCO, ~80 catégories).
 * Processeur graphique si possible, sinon processeur. Chargé au premier usage.
 */
class LocalDetector(
    private val context: Context,
    /** Image actuelle de la caméra (appelée sur le thread de l'interface). */
    private val frame: () -> Bitmap?,
    private val log: (String) -> Unit = {},
) : FastDetector {
    companion object {
        const val MODEL = "efficientdet_lite2.tflite"
    }

    private var detector: ObjectDetector? = null
    private var failed = false
    @Volatile private var avg = 0.0
    override val averageMs: Double get() = avg

    private fun create(delegate: Delegate): ObjectDetector {
        val base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build()
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(12)
            .setScoreThreshold(0.3f)
            .build()
        return ObjectDetector.createFromOptions(context, opts)
    }

    private fun detector(): ObjectDetector? {
        detector?.let { return it }
        if (failed) return null
        detector = runCatching { create(Delegate.GPU) }.onFailure { Log.w("LocalDetector", "GPU indisponible", it) }.getOrNull()
            ?: runCatching { create(Delegate.CPU) }.onFailure {
                Log.e("LocalDetector", "détecteur indisponible", it)
                log("Détecteur d'objets du téléphone indisponible : ${it.message}")
                failed = true
            }.getOrNull()
        return detector
    }

    /** Image de la caméra, prise sur le thread de l'interface (300 ms au plus). */
    private fun grab(): Bitmap? {
        val latch = CountDownLatch(1)
        var b: Bitmap? = null
        Handler(Looper.getMainLooper()).post {
            try { b = frame() } catch (_: Exception) { } finally { latch.countDown() }
        }
        if (!latch.await(300, TimeUnit.MILLISECONDS)) return null
        return b?.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false).also { _ -> it.recycle() } else it }
    }

    @Synchronized
    override fun detect(tiled: Boolean): List<Seen> {
        val d = detector() ?: return emptyList()
        val bmp = grab() ?: return emptyList()
        val t0 = System.currentTimeMillis()
        try {
            val W = bmp.width
            val H = bmp.height
            val out = ArrayList<Seen>()
            // Morceaux carrés qui se chevauchent (gauche, centre, droite) : les petits objets y sont plus gros.
            val crops = if (tiled && W > H * 1.2) listOf(0, (W - H) / 2, W - H) else listOf(-1)
            for (x0 in crops) {
                val img = if (x0 < 0) bmp else Bitmap.createBitmap(bmp, x0, 0, H, H)
                val off = if (x0 < 0) 0 else x0
                try {
                    val r = d.detect(BitmapImageBuilder(img).build())
                    for (det in r.detections()) {
                        val c = det.categories().firstOrNull() ?: continue
                        val bb = det.boundingBox()
                        out.add(Seen(c.categoryName(), c.score().toDouble(), Box(
                            ((off + bb.centerX()) / W).toDouble(), (bb.centerY() / H).toDouble(),
                            (bb.width() / W).toDouble(), (bb.height() / H).toDouble(),
                        )))
                    }
                } finally {
                    if (img !== bmp) img.recycle()
                }
            }
            val dt = (System.currentTimeMillis() - t0).toDouble() / crops.size
            avg = if (avg == 0.0) dt else avg * 0.9 + dt * 0.1
            return merge(out)
        } catch (e: Exception) {
            Log.w("LocalDetector", "analyse impossible", e)
            return emptyList()
        } finally {
            bmp.recycle()
        }
    }

    /** Les morceaux se chevauchent : un même objet vu deux fois ne compte qu'une fois (on garde le plus sûr). */
    private fun merge(list: List<Seen>): List<Seen> {
        val kept = ArrayList<Seen>()
        for (s in list.sortedByDescending { it.score }) {
            if (kept.none { it.label == s.label && it.box.iou(s.box) > 0.4 }) kept.add(s)
        }
        return kept
    }

    fun close() {
        runCatching { detector?.close() }
        detector = null
    }
}
