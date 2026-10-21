package com.bebopia.app.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/** Gestes de la main (MediaPipe Gesture Recognizer) et posture du corps (MediaPipe Pose, version légère), sur le téléphone. */
class GestureWatcher(private val context: Context) {
    private var hands: GestureRecognizer? = null
    private var pose: PoseLandmarker? = null
    private var failed = false

    private fun <T> make(build: (Delegate) -> T): T? =
        runCatching { build(Delegate.GPU) }.recoverCatching { build(Delegate.CPU) }.onFailure { Log.e("Gestes", "modèle indisponible", it) }.getOrNull()

    private fun ready(): Boolean {
        if (failed) return false
        if (hands == null) hands = make { d ->
            GestureRecognizer.createFromOptions(context, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").setDelegate(d).build())
                .setRunningMode(RunningMode.IMAGE).setNumHands(2)
                .setMinHandDetectionConfidence(0.7f).setMinHandPresenceConfidence(0.7f).build())
        }
        if (pose == null) pose = make { d ->
            PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(d).build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(1)
                .setMinPoseDetectionConfidence(0.7f).setMinPosePresenceConfidence(0.7f).build())
        }
        if (hands == null && pose == null) failed = true
        return !failed
    }

    /** Geste de la main le plus sûr (catégorie MediaPipe) et repères du corps (x, y, visibilité), sur une image. */
    @Synchronized
    fun analyze(bmp: Bitmap): Pair<String?, List<FloatArray>?> {
        if (!ready()) return null to null
        val img = BitmapImageBuilder(bmp).build()
        val body = runCatching {
            pose?.detect(img)?.landmarks()?.firstOrNull()?.map { l -> floatArrayOf(l.x(), l.y(), l.visibility().orElse(1f)) }
        }.getOrNull()
        // Une main seule, dans un décor encombré, est souvent une fausse détection : on ne la croit que si
        // une personne est visible, ou si la main est grande dans l'image (tendue près de la caméra).
        val hand = runCatching {
            val r = hands?.recognize(img) ?: return@runCatching null
            val personSeen = body != null && com.bebopia.app.core.vision.GestureInterpreter.personVisible(body)
            r.gestures().indices.mapNotNull { i ->
                val g = r.gestures()[i].maxByOrNull { it.score() } ?: return@mapNotNull null
                if (g.score() < 0.8f || g.categoryName() == "None") return@mapNotNull null
                val lm = r.landmarks().getOrNull(i) ?: return@mapNotNull null
                val span = maxOf(lm.maxOf { it.x() } - lm.minOf { it.x() }, lm.maxOf { it.y() } - lm.minOf { it.y() })
                if (!personSeen && span < 0.18f) return@mapNotNull null
                g
            }.maxByOrNull { it.score() }?.categoryName()
        }.getOrNull()
        return hand to body
    }

    fun close() {
        runCatching { hands?.close() }; runCatching { pose?.close() }
        hands = null; pose = null
    }
}
