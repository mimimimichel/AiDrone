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

/** Une pièce repérée : forme (zone libre vue depuis le point de repérage), type, et les pièces voisines par les portes. */
class Room(val id: Int, var type: String, val cn: Double, val ce: Double, var outline: List<Pair<Double, Double>>, val seenAt: Long) {
    /** Portes vers d'autres pièces : (label de la porte, id de la pièce). */
    val links = ArrayList<Pair<String, Int>>()
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
    @Volatile var belowAt = 0L
        private set
    private var belowPos = 0.0 to 0.0

    // ---- plan des pièces ----
    private val rooms = ArrayList<Room>()
    @Volatile var currentRoom: Room? = null
        private set
    /** Porte franchie depuis le dernier repérage : le prochain repérage décrit une nouvelle pièce. */
    private var crossed: String? = null
    @Volatile var lastSurveyAt = 0L
        private set
    private var lastSurveyPos = 0.0 to 0.0

    /** Repérage encore valable (moins de [maxAgeMs], et le drone n'a pas bougé de plus de [maxMoveM]). */
    fun surveyFresh(pos: Pair<Double, Double>, maxAgeMs: Long = 120_000, maxMoveM: Double = 2.0): Boolean = synchronized(this) {
        lastSurveyAt > 0 && System.currentTimeMillis() - lastSurveyAt < maxAgeMs && crossed == null &&
            hypot(pos.first - lastSurveyPos.first, pos.second - lastSurveyPos.second) < maxMoveM
    }

    /** Hauteur libre sous le drone, si on l'a regardée récemment (moins d'une minute, sans bouger de plus d'un mètre). */
    fun belowFresh(pos: Pair<Double, Double>): Double? = synchronized(this) {
        if (System.currentTimeMillis() - belowAt > 60_000) return null
        if (hypot(pos.first - belowPos.first, pos.second - belowPos.second) > 1.0) return null
        belowM
    }

    fun setBelowAt(pos: Pair<Double, Double>, m: Double?, note: String) = synchronized(this) {
        belowM = m; belowNote = note; belowAt = System.currentTimeMillis(); belowPos = pos
    }

    /** Le drone vient de passer une porte. */
    fun crossedDoor(label: String) = synchronized(this) { crossed = label }

    /**
     * Repérage terminé à [pos] : même pièce que la courante (pas de porte franchie, à moins de 2,5 m) -> mise à jour,
     * sinon nouvelle pièce, reliée à la précédente par la porte franchie.
     */
    fun recordRoom(pos: Pair<Double, Double>, sectors: List<Pair<Double, Double>>, type: String?) = synchronized(this) {
        val outline = sectors.groupBy { (Math.round(it.first / 45.0) * 45).toInt() }
            .map { (_, v) -> v.minBy { it.second } }.sortedBy { ((it.first % 360) + 360) % 360 }
            .map { (b, d) -> project(pos, b, d.coerceAtMost(12.0)) }
        val cur = currentRoom
        val known = rooms.firstOrNull { hypot(it.cn - pos.first, it.ce - pos.second) < 2.5 }
        val room = if (crossed == null && cur != null && hypot(cur.cn - pos.first, cur.ce - pos.second) < 2.5) cur
            else known ?: Room(rooms.size + 1, type ?: "pièce", pos.first, pos.second, outline, System.currentTimeMillis()).also { rooms.add(it) }
        if (outline.size >= 3) room.outline = outline
        if (type != null && (room.type == "pièce" || room === cur)) room.type = type
        val door = crossed
        if (door != null && cur != null && cur !== room) {
            if (cur.links.none { it.second == room.id }) cur.links.add(door to room.id)
            if (room.links.none { it.second == cur.id }) room.links.add(door to cur.id)
        }
        crossed = null
        currentRoom = room
        lastSurveyAt = System.currentTimeMillis()
        lastSurveyPos = pos
    }

    fun roomCount(): Int = synchronized(this) { rooms.size }

    /** Le plan des pièces en une phrase (pour l'IA et pour sa mémoire). */
    fun roomsSummary(): String = synchronized(this) {
        if (rooms.isEmpty()) return ""
        rooms.joinToString(" ; ") { r ->
            val name = "${r.type} (pièce ${r.id})"
            val links = r.links.joinToString(", ") { (d, id) -> "par « $d » vers ${rooms.firstOrNull { it.id == id }?.type ?: "?"} (pièce $id)" }
            val inside = items.filter { it.kind in setOf("meuble", "cible", "escalier") && hypot(it.n - r.cn, it.e - r.ce) < 4 }.take(5).joinToString(", ") { it.label }
            name + (if (r === currentRoom) " [ici]" else "") + (if (links.isNotEmpty()) " : $links" else "") + (if (inside.isNotEmpty()) " ; contient : $inside" else "")
        }
    }

    fun clear() = synchronized(this) {
        items.clear(); free.clear(); ceiling = null; belowM = null; belowNote = ""
        rooms.clear(); currentRoom = null; crossed = null; lastSurveyAt = 0L; belowAt = 0L
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
        if (rooms.isNotEmpty()) sb.append("Plan des pièces : ").append(roomsSummary()).append(". ")
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
        val rm = JSONArray()
        rooms.forEach { r ->
            val pts = JSONArray()
            r.outline.forEach { (n, e) -> pts.put(JSONArray().put(r1(n)).put(r1(e))) }
            rm.put(JSONArray().put(r.type).put(pts).put(r1(r.cn)).put(r1(r.ce)).put(r === currentRoom))
        }
        JSONObject().put("items", it2).put("free", fr).put("o", JSONArray().put(r1(origin.first)).put(r1(origin.second))).put("rooms", rm)
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
