package com.bebopia.app.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Effet « voix de robot » appliqué au son de l'IA avant le haut-parleur :
 * - modulation en anneau (la voix multipliée par une onde à ~60 Hz : le timbre « Dalek ») ;
 * - résonance métallique (un écho très court qui se répète, comme dans une boîte de conserve) ;
 * - légère saturation pour un son plus électronique.
 * Garde son état d'un morceau à l'autre pour éviter les clics.
 */
class RobotVoice(private val sampleRate: Int) {
    private val ringHz = 60.0
    private val ringMix = 0.65          // part de voix modulée (le reste : voix d'origine, pour rester compréhensible)
    private val combDelay = (sampleRate * 0.0045).toInt() // 4,5 ms : résonance vers 220 Hz
    private val combFeedback = 0.55
    private val delay = FloatArray(combDelay)
    private var pos = 0
    private var phase = 0.0

    fun reset() {
        delay.fill(0f)
        pos = 0
        phase = 0.0
    }

    /** Transforme des échantillons PCM 16 bits petit-boutiste, sur place. */
    fun process(pcm: ByteArray, length: Int = pcm.size) {
        val step = 2 * PI * ringHz / sampleRate
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
            // Modulation en anneau
            val ring = s * sin(phase)
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
            var y = (1 - ringMix) * s + ringMix * ring * 1.6
            // Résonance métallique (filtre en peigne)
            val echo = delay[pos]
            y += combFeedback * echo
            delay[pos] = y.toFloat()
            pos = (pos + 1) % delay.size
            // Saturation douce, et volume remis à niveau
            y = tanh(y * 1.4) * 0.8
            val out = (y * 32767).toInt().coerceIn(-32768, 32767)
            pcm[i] = (out and 0xFF).toByte()
            pcm[i + 1] = (out shr 8).toByte()
            i += 2
        }
    }
}
