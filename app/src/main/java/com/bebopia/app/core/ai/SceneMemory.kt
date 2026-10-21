package com.bebopia.app.core.ai

import com.bebopia.app.core.SpaceGuard
import com.bebopia.app.core.norm180
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ce qu'une vue a montré : une chose repérée, placée en mètres (nord, est) depuis le décollage. */
class SceneItem(
    val label: String,
    /** porte, passage, escalier, fenetre, mur, meuble, obstacle, personne, animal, cible, autre */
    val kind: String,
    val n: Double,
    val e: Double,
    val widthM: Double?,
    val open: Boolean?,
    val seenAt: Long,
) {
    @Volatile var traversed = false
    @Volatile var failed = false
}

/**
 * Mémoire de la scène : ce que le drone a vu lors de ses repérages (portes, murs, meubles, cible…)
 * et la place libre dans chaque direction, mesurée depuis le point du repérage.
 * Sert à planifier (« la porte est à 40° à droite, à 3 m ») et à ne pas aller dans les murs.
 */
class SceneMemory(private val indoorMargin: () -> Boolean) : SpaceGuard {
    private val items = ArrayList<SceneItem>()
    /** Place libre : (cap absolu, distance libre en m) mesurée depuis [origin]. */
    private var free = ArrayList<Pair<Double, Double>>()
    private var origin = 0.0 to 0.0
    private var freeAt = 0L
    @Volatile private var ceiling: Double? = null
    @Volatile var belowM: Double? = null
        private set
    @Volatile var belowNote: String = ""
        private set

    fun clear() = synchronized(this) {
        items.clear(); free.clear(); ceiling = null; belowM = null; belowNote = ""
    }

    /** Nouveau repérage : remplace la place libre (les objets vus restent, fusionnés). */
    fun setFreeSpace(at: Pair<Double, Double>, sectors: List<Pair<Double, Double>>) = synchronized(this) {
        origin = at
        free = ArrayList(sectors)
        freeAt = System.currentTimeMillis()
    }

    fun setCeiling(alt: Double?) { if (alt != null && alt > 0.8) ceiling = alt }

    fun setBelow(m: Double?, note: String) { belowM = m; belowNote = note }

    fun add(item: SceneItem) = synchronized(this) {
        // Même chose vue à moins d'un mètre : on garde la plus récente
        items.removeAll { it.kind == item.kind && hypot(it.n - item.n, it.e - item.e) < 1.0 && !it.traversed }
        items.add(item)
        if (items.size > 120) items.removeAt(0)
    }

    fun doors(): List<SceneItem> = synchronized(this) {
        items.filter { (it.kind == "porte" || it.kind == "passage") && it.open != false && !it.traversed && !it.failed }
    }

    fun all(): List<SceneItem> = synchronized(this) { items.toList() }

    // ---- garde-fou d'espace libre ---------------------------------------------------------------
    override fun allowed(bearingDeg: Double, pos: Pair<Double, Double>): Double? = synchronized(this) {
        if (free.isEmpty() || System.currentTimeMillis() - freeAt > 180_000) return null
        val moved = hypot(pos.first - origin.first, pos.second - origin.second)
        if (moved > 2.5) return null // trop loin du point de repérage : on ne sait plus
        val near = free.filter { abs(norm180(it.first - bearingDeg)) <= 30 }
        if (near.isEmpty()) return null
        val margin = if (indoorMargin()) 0.5 else 1.5
        return (near.minOf { it.second } - moved - margin).coerceAtLeast(0.0)
    }

    override fun ceilingAlt(): Double? = ceiling

    // ---- pour l'IA et la carte ---------------------------------------------------------------------
    /** Description lisible par l'IA, directions relatives au cap actuel du drone. */
    fun describe(pos: Pair<Double, Double>, heading: Double): String = synchronized(this) {
        val sb = StringBuilder()
        fun rel(n: Double, e: Double): String {
            val b = Math.toDegrees(kotlin.math.atan2(e - pos.second, n - pos.first))
            val d = hypot(n - pos.first, e - pos.second)
            val r = norm180(b - heading).roundToInt()
            val side = when {
                abs(r) <= 10 -> "devant"
                abs(r) >= 170 -> "derrière"
                r > 0 -> "$r° à droite"
                else -> "${-r}° à gauche"
            }
            return "%s, à %.1f m".format(side, d)
        }
        if (free.isNotEmpty()) {
            sb.append("Place libre (depuis le point du repérage) : ")
            sb.append(free.sortedBy { norm180(it.first - heading) }.joinToString(" ; ") { (b, d) ->
                val r = norm180(b - heading).roundToInt()
                (if (abs(r) <= 10) "devant" else if (r > 0) "$r° droite" else "${-r}° gauche") + " %.1f m".format(d)
            })
            sb.append(". ")
        }
        ceiling?.let { sb.append("Plafond estimé à %.1f m d'altitude. ".format(it)) }
        belowM?.let { sb.append("Sous le drone : %.1f m libres%s. ".format(it, if (belowNote.isNotBlank()) " ($belowNote)" else "")) }
        val list = items.sortedBy { hypot(it.n - pos.first, it.e - pos.second) }.take(25)
        if (list.isNotEmpty()) {
            sb.append("Repéré : ")
            sb.append(list.joinToString(" ; ") { i ->
                i.label + " [" + i.kind + (if (i.open == false) ", fermée" else "") +
                    (i.widthM?.let { w -> ", largeur ~%.1f m".format(w) } ?: "") +
                    (if (i.traversed) ", déjà franchie" else "") + "] " + rel(i.n, i.e)
            })
        }
        sb.toString().ifBlank { "rien en mémoire : fais un repérage" }
    }

    fun mapJson(): JSONObject = synchronized(this) {
        val it2 = JSONArray()
        items.forEach { i -> it2.put(JSONArray().put(r1(i.n)).put(r1(i.e)).put(i.label).put(i.kind).put(i.traversed)) }
        val fr = JSONArray()
        if (System.currentTimeMillis() - freeAt < 180_000) free.forEach { (b, d) -> fr.put(JSONArray().put(r1(b)).put(r1(d))) }
        JSONObject().put("items", it2).put("free", fr).put("o", JSONArray().put(r1(origin.first)).put(r1(origin.second)))
    }

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

    companion object {
        /** Position (nord, est) d'un point vu à [dist] m dans la direction [bearing] depuis [pos]. */
        fun project(pos: Pair<Double, Double>, bearing: Double, dist: Double): Pair<Double, Double> {
            val b = Math.toRadians(bearing)
            return (pos.first + dist * cos(b)) to (pos.second + dist * sin(b))
        }
    }
}
