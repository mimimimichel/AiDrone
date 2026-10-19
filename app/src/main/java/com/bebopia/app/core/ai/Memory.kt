package com.bebopia.app.core.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Mémoire de l'IA d'un vol à l'autre : ce qu'elle a choisi de retenir (« la tulipe est près de la fenêtre »)
 * et le résumé de chaque vol. Relue au début de chaque conversation.
 */
class Memory(private val file: File) {
    class Note(val text: String, val time: Long, val lat: Double?, val lon: Double?)

    private val notes = ArrayList<Note>()

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    notes.add(Note(o.getString("text"), o.getLong("time"),
                        if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lon")) o.getDouble("lon") else null))
                }
            }
        }
    }

    val size: Int get() = synchronized(notes) { notes.size }

    fun add(text: String, where: Pair<Double, Double>?) {
        val t = text.trim().take(600)
        if (t.isEmpty()) return
        synchronized(notes) {
            notes.add(Note(t, System.currentTimeMillis(), where?.first, where?.second))
            while (notes.size > 200) notes.removeAt(0)
        }
        save()
    }

    fun clear() {
        synchronized(notes) { notes.clear() }
        save()
    }

    private fun save() {
        val a = JSONArray()
        synchronized(notes) {
            notes.forEach { n ->
                val o = JSONObject().put("text", n.text).put("time", n.time)
                if (n.lat != null && n.lon != null) o.put("lat", n.lat).put("lon", n.lon)
                a.put(o)
            }
        }
        runCatching { file.writeText(a.toString()) }
    }

    /** Souvenirs pour l'IA : les plus récents, avec la distance d'ici quand on la connaît. */
    fun promptText(here: Pair<Double, Double>?): String {
        val list = synchronized(notes) { notes.toList() }.takeLast(40)
        if (list.isEmpty()) return ""
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        val lines = list.map { n ->
            val far = if (here != null && n.lat != null && n.lon != null) {
                val d = hypot((n.lat - here.first) * 111320.0, (n.lon - here.second) * 111320.0 * cos(Math.toRadians(here.first)))
                if (d < 300) ", ici" else ", à %.1f km d'ici".format(d / 1000)
            } else ""
            "- [${fmt.format(Date(n.time))}$far] ${n.text}"
        }
        var text = lines.joinToString("\n")
        if (text.length > 4000) text = text.takeLast(4000)
        return "\n\nTa mémoire (souvenirs des vols précédents, du plus ancien au plus récent) :\n$text\n" +
            "Sers-t'en quand c'est utile (« la dernière fois, la tulipe était près de la fenêtre »), sans les réciter."
    }
}
