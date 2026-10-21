package com.bebopia.app.core.ai

/** Une chose vue sur une image : x = position horizontale de son centre (0 = bord gauche, 1 = bord droit). */
class ViewItem(val label: String, val kind: String, val x: Double, val distanceM: Double?, val widthM: Double?, val open: Boolean?)

/** Ce que montre une vue du repérage. */
class ViewReport(
    val indoor: Boolean?,
    /** Distance libre droit devant (au centre de l'image, à hauteur du drone). */
    val freeAheadM: Double?,
    /** Distance entre le drone et le plafond, si visible. */
    val ceilingAboveM: Double?,
    val items: List<ViewItem>,
    /** L'objet cherché, s'il est visible. */
    val target: ViewItem?,
    /** Vue vers le bas : hauteur libre sous le drone, et s'il peut descendre. */
    val belowM: Double?,
    val belowClear: Boolean?,
    val summary: String,
    /** Type de pièce (salon, cuisine, couloir…), si on le reconnaît. */
    val roomType: String? = null,
)

/** Analyse une vue (JPEG base64) : horizontale (down = false) ou vers le sol. Bloquant. */
fun interface SceneAnalyzer {
    fun analyze(jpegBase64: String, target: String?, down: Boolean, hfovDeg: Double): ViewReport
}
