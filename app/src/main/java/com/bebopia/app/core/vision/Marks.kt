package com.bebopia.app.core.vision

/**
 * Cadres affichés sur la vidéo (casque, cockpit, téléphone) : ce que le drone suit, ce qu'il propose de suivre,
 * les candidats vus par le téléphone, ce qu'il a trouvé. Chaque source remplace ses propres cadres ; ils expirent seuls.
 */
object Marks {
    enum class Kind { FOLLOWING, TRACKING, PROPOSED, CANDIDATE, FOUND }

    class Mark(val box: Box, val label: String, val kind: Kind)

    private class Entry(val marks: List<Mark>, val until: Long)

    private val sources = HashMap<String, Entry>()

    fun show(source: String, marks: List<Mark>, ttlMs: Long) {
        synchronized(sources) { sources[source] = Entry(marks, System.currentTimeMillis() + ttlMs) }
    }

    fun clear(source: String) {
        synchronized(sources) { sources.remove(source) }
    }

    fun current(): List<Mark> = synchronized(sources) {
        val now = System.currentTimeMillis()
        sources.values.removeAll { it.until < now }
        // Le plus important dessiné en dernier (par-dessus)
        sources.values.flatMap { it.marks }.sortedByDescending { it.kind.ordinal }
    }
}
