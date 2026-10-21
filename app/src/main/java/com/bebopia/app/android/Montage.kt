package com.bebopia.app.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Montage : les meilleurs moments de chaque plan (le milieu, quelques secondes), mis bout à bout. */
object Montage {
    /** [clips] : (début, fin) en secondes dans [input]. Bloquant (ne pas appeler sur le fil de l'interface). */
    fun make(context: Context, input: File, clips: List<Pair<Double, Double>>, output: File): String? {
        if (clips.isEmpty()) return "aucun plan à monter"
        val items = clips.map { (a, b) ->
            EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs((a * 1000).toLong()).setEndPositionMs((b * 1000).toLong()).build(),
                ).build(),
            ).build()
        }
        val latch = CountDownLatch(1)
        var error: String? = "montage trop long"
        Handler(Looper.getMainLooper()).post {
            try {
                val t = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { error = null; latch.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException.message ?: "erreur de montage"; latch.countDown()
                    }
                }).build()
                t.start(Composition.Builder(EditedMediaItemSequence(items)).build(), output.path)
            } catch (e: Exception) {
                error = e.message ?: "montage impossible"
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.MINUTES)
        return error
    }
}
