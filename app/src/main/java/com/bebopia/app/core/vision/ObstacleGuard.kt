package com.bebopia.app.core.vision

import com.bebopia.app.core.DroneBase
import kotlin.concurrent.thread

/** Fournit une petite image en niveaux de gris de la caméra (null si indisponible). Bloquant. */
fun interface GrayFrames { fun grab(): Gray? }

/**
 * Surveille la vidéo pendant que le drone avance et l'arrête tout de suite
 * si quelque chose devant lui grossit trop vite (collision probable dans moins de 2 s).
 * Fonctionne sans l'IA, une dizaine de fois par seconde.
 *
 * Limites : ne voit que devant (pas sur les côtés, ni au-dessus), a besoin d'une surface texturée
 * (une vitre ou un mur blanc uni passent inaperçus), et se met en veille si la caméra regarde vers le sol.
 */
class ObstacleGuard(
    private val drone: DroneBase,
    private val frames: GrayFrames,
    private val enabled: () -> Boolean,
    private val onObstacle: (ttcS: Double) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val detector = LoomingDetector()
    @Volatile private var running = false
    /** Dernier temps avant contact mesuré (s), pour l'affichage. */
    @Volatile var lastTtcS: Double? = null
        private set
    @Volatile var watching = false
        private set

    fun start() {
        if (running) return
        running = true
        thread(name = "obstacles", isDaemon = true) {
            while (running) {
                try {
                    loop()
                } catch (e: Throwable) {
                    // Une erreur du détecteur ne doit jamais faire planter l'application.
                    log("détecteur d'obstacles : erreur ${e.javaClass.simpleName} ${e.message ?: ""}")
                    detector.reset()
                    Thread.sleep(500)
                }
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun loop() {
        while (running) {
            val t0 = System.currentTimeMillis()
            // Caméra très inclinée vers le bas : le sol qui défile ressemble à un obstacle.
            val active = enabled() && drone.flying && drone.movingForward && drone.cameraTilt >= -25
            watching = active
            if (!active) {
                detector.reset()
                lastTtcS = null
                Thread.sleep(60)
                continue
            }
            val g = runCatching { frames.grab() }.getOrNull()
            if (g == null) {
                detector.reset()
                Thread.sleep(60)
                continue
            }
            val r = detector.update(g, System.currentTimeMillis())
            lastTtcS = r.ttcS
            if (r.danger && drone.movingForward) {
                val ttc = r.ttcS ?: 0.0
                log("obstacle : contact estimé dans %.1f s, arrêt".format(ttc))
                drone.stopForObstacle()
                detector.reset()
                onObstacle(ttc)
            }
            // Environ 12 images par seconde
            val wait = 80 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
    }
}
