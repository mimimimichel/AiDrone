package com.bebopia.app.core.vision

import kotlin.math.abs

/** Ce que le pilote demande d'un geste. */
enum class Gesture(val label: String) {
    STOP("✋ stop"), PHOTO("📸 photo"), YES("👍 oui"), NO("👎 non"), LAND("🛬 atterris")
}

/**
 * Interprète les gestes vus par la caméra du drone, une image après l'autre :
 * - main (de près) : paume ouverte = stop, V = photo, pouce levé = oui, pouce baissé = non ;
 * - bras (de plus loin) : un bras levé = stop, deux bras levés = photo, bras croisés en X = atterris.
 * Un geste doit être tenu un moment (pas de déclenchement au hasard), puis 4 s de pause avant le suivant.
 */
class GestureInterpreter {
    companion object {
        // Repères du corps (MediaPipe Pose) : nez, épaules, poignets, hanches
        const val NOSE = 0; const val L_SHOULDER = 11; const val R_SHOULDER = 12
        const val L_WRIST = 15; const val R_WRIST = 16; const val L_HIP = 23; const val R_HIP = 24
        // Un geste doit être tenu, sans interruption (fausses détections : elles clignotent)
        private val HOLD = mapOf(Gesture.STOP to 1200L, Gesture.PHOTO to 1500L, Gesture.YES to 1000L, Gesture.NO to 1000L, Gesture.LAND to 2000L)
        const val COOLDOWN = 4000L
        private const val GAP_MS = 300L

        /** Une vraie personne : tête et épaules bien visibles, assez grande dans l'image, tête au-dessus des épaules. */
        fun personVisible(p: List<FloatArray>): Boolean {
            if (p.size < 25) return false
            fun ok(i: Int) = p[i].size < 3 || p[i][2] > 0.8f
            if (!ok(NOSE) || !ok(L_SHOULDER) || !ok(R_SHOULDER)) return false
            val shW = abs(p[L_SHOULDER][0] - p[R_SHOULDER][0])
            val shY = (p[L_SHOULDER][1] + p[R_SHOULDER][1]) / 2
            return shW > 0.05f && p[NOSE][1] < shY
        }
    }

    private var candidate: Gesture? = null
    private var since = 0L
    private var lastSeen = 0L
    private var lastFired = Long.MIN_VALUE / 2
    private var misses = 0

    /** Geste en cours de reconnaissance et sa progression (0 à 1), pour l'affichage. */
    var progress: Pair<Gesture, Double>? = null
        private set

    /** Main : catégorie MediaPipe (Open_Palm, Victory, Thumb_Up, Thumb_Down…). */
    fun fromHand(category: String?): Gesture? = when (category) {
        "Open_Palm" -> Gesture.STOP
        "Victory" -> Gesture.PHOTO
        "Thumb_Up" -> Gesture.YES
        "Thumb_Down" -> Gesture.NO
        else -> null
    }

    /** Corps : 33 repères (x, y de 0 à 1, y vers le bas ; visibilité de 0 à 1). */
    fun fromPose(p: List<FloatArray>?): Gesture? {
        if (p == null || p.size < 25 || !personVisible(p)) return null
        fun ok(i: Int) = p[i].size < 3 || p[i][2] > 0.8f
        if (!ok(L_WRIST) || !ok(R_WRIST)) return null
        val nose = p[NOSE][1]
        val shY = (p[L_SHOULDER][1] + p[R_SHOULDER][1]) / 2
        val shW = abs(p[L_SHOULDER][0] - p[R_SHOULDER][0]).coerceAtLeast(0.02f)
        val lUp = p[L_WRIST][1] < nose
        val rUp = p[R_WRIST][1] < nose
        if (lUp && rUp) return Gesture.PHOTO
        if (lUp != rUp) {
            val other = if (lUp) p[R_WRIST] else p[L_WRIST]
            if (other[1] > shY) return Gesture.STOP // l'autre bras le long du corps
        }
        // Bras croisés devant la poitrine : les poignets passent de l'autre côté
        val hipY = if (ok(L_HIP) && ok(R_HIP)) (p[L_HIP][1] + p[R_HIP][1]) / 2 else shY + shW * 2
        val chest = { w: FloatArray -> w[1] > shY - shW * 0.3f && w[1] < hipY }
        val midX = (p[L_SHOULDER][0] + p[R_SHOULDER][0]) / 2
        val crossed = (p[R_WRIST][0] - midX) * (p[R_SHOULDER][0] - midX) < 0 && (p[L_WRIST][0] - midX) * (p[L_SHOULDER][0] - midX) < 0
        if (crossed && chest(p[L_WRIST]) && chest(p[R_WRIST])) return Gesture.LAND
        return null
    }

    /** Une image analysée : renvoie un geste quand il a été tenu assez longtemps. */
    fun update(now: Long, hand: String?, pose: List<FloatArray>?): Gesture? {
        val g = fromHand(hand) ?: fromPose(pose)
        if (now - lastFired < COOLDOWN) { candidate = null; progress = null; return null }
        if (g == null) {
            // Une image ratée est tolérée une fois ; une détection qui clignote est fausse
            misses++
            if (now - lastSeen > GAP_MS || misses >= 2) { candidate = null; progress = null }
            return null
        }
        lastSeen = now
        if (g != candidate) { candidate = g; since = now; misses = 0 }
        val hold = HOLD[g] ?: 1000L
        progress = g to ((now - since).toDouble() / hold).coerceAtMost(1.0)
        if (now - since >= hold) {
            lastFired = now
            candidate = null
            progress = null
            return g
        }
        return null
    }
}
