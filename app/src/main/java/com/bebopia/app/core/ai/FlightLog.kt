package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** Un vol enregistré, pour la liste du journal. */
class FlightEntry(val dir: File, val title: String, val summary: String, val photos: List<File>)

/**
 * Journal de vol : du décollage à l'atterrissage, la conversation et les événements, la trace,
 * quelques photos (points marqués, cibles trouvées, une toutes les 2 minutes) et, à la fin,
 * un résumé écrit par l'IA (« j'ai vu deux voitures et un chat près de la haie »).
 */
class FlightLog(
    private val root: File,
    private val drone: DroneBase,
    private val eyes: Eyes,
    /** Résumé par l'IA (texte du journal -> résumé), null si impossible. Bloquant. */
    private val summarize: (String) -> String?,
    private val onSaved: (summary: String, title: String) -> Unit,
) {
    private var flying = false
    private var dir: File? = null
    private var start = 0L
    private var batteryStart: Int? = null
    private var maxAlt = 0.0
    private var maxDist = 0.0
    private var lastPhoto = 0L
    private var home: Pair<Double, Double>? = null
    private val events = JSONArray()
    private val track = JSONArray()
    private val photos = ArrayList<String>()

    val active: Boolean get() = flying

    /** Dossier et heure de début du vol en cours (ou du dernier). */
    val currentDir: File? get() = dir
    val startedAt: Long get() = start

    /** Une ligne de la conversation ou un événement. */
    fun line(role: Role, text: String) {
        if (!flying || text.isBlank()) return
        synchronized(events) {
            if (events.length() < 3000) events.put(JSONObject().put("t", (System.currentTimeMillis() - start) / 1000)
                .put("r", when (role) { Role.PILOT -> "pilote"; Role.DRONE -> "IA"; else -> "app" }).put("x", text.take(500)))
        }
    }

    /** Appelé environ une fois par seconde. */
    fun tick() {
        val now = drone.flying
        if (now && !flying) begin()
        else if (!now && flying) end()
        if (!flying) return
        maxAlt = maxOf(maxAlt, drone.altitude())
        maxDist = maxOf(maxDist, drone.distanceFromHome())
        if (home == null) home = drone.homeGps()
        val p = drone.positionNE()
        synchronized(track) { if (track.length() < 4000) track.put(JSONArray().put(r1(p.first)).put(r1(p.second)).put(r1(drone.altitude()))) }
        if (System.currentTimeMillis() - lastPhoto > 120_000) snap("vue")
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    private fun begin() {
        flying = true
        start = System.currentTimeMillis()
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(Date(start))
        dir = File(root, name).also { it.mkdirs() }
        batteryStart = drone.battery()
        maxAlt = 0.0; maxDist = 0.0; home = null
        lastPhoto = System.currentTimeMillis() - 100_000 // première photo peu après le décollage
        synchronized(events) { while (events.length() > 0) events.remove(0) }
        synchronized(track) { while (track.length() > 0) track.remove(0) }
        photos.clear()
    }

    /** Photo de la caméra, rangée dans le dossier du vol. */
    fun snap(label: String) {
        val d = dir ?: return
        if (!flying) return
        lastPhoto = System.currentTimeMillis()
        val b64 = eyes.captureJpegBase64() ?: return
        val safe = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "photo" }
        val f = File(d, "%03d-%s.jpg".format(photos.size + 1, safe))
        runCatching {
            f.writeBytes(Base64.getDecoder().decode(b64))
            synchronized(photos) { photos.add(f.name) }
            line(Role.SYSTEM, "📷 photo ${f.name}")
        }
    }

    private fun end() {
        flying = false
        val d = dir ?: return
        val end = System.currentTimeMillis()
        val minutes = (end - start) / 60000.0
        val title = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(start)) +
            " · %.0f min · %.0f m de haut · %.0f m du départ".format(minutes, maxAlt, maxDist)
        val evText = synchronized(events) {
            (0 until events.length()).joinToString("\n") { i ->
                val o = events.getJSONObject(i)
                "[%d:%02d] %s : %s".format(o.getLong("t") / 60, o.getLong("t") % 60, o.getString("r"), o.getString("x"))
            }
        }
        val json = JSONObject()
            .put("title", title).put("start", start).put("end", end)
            .put("maxAlt", maxAlt).put("maxDist", maxDist)
            .put("batteryStart", batteryStart ?: JSONObject.NULL).put("batteryEnd", drone.battery() ?: JSONObject.NULL)
            .put("events", events).put("track", track).put("photos", JSONArray(photos))
        home?.let { json.put("homeLat", it.first).put("homeLon", it.second) }
        val file = File(d, "journal.json")
        runCatching { file.writeText(json.toString()) }
        // Le résumé par l'IA prend quelques secondes : à part.
        Thread({
            val prompt = "Voici le journal d'un vol de drone ($title). " +
                "Écris en français un résumé de 2 à 5 phrases : ce qui a été fait, ce qui a été vu ou trouvé " +
                "(objets, animaux, personnes, lieux), les incidents éventuels. Puis une dernière phrase qui commence par " +
                "« À retenir : » avec ce qui sera utile aux prochains vols (où se trouvent les choses, obstacles du lieu). " +
                "Pas de liste, pas de titre.\n\n" + evText.takeLast(12000)
            val summary = runCatching { summarize(prompt) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Vol de %.0f min, jusqu'à %.0f m de haut et %.0f m du départ.".format(minutes, maxAlt, maxDist)
            runCatching { file.writeText(json.put("summary", summary).toString()) }
            onSaved(summary, title)
        }, "journal").apply { isDaemon = true; start() }
    }

    fun list(): List<FlightEntry> = (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
        .sortedByDescending { it.name }
        .mapNotNull { d ->
            val o = runCatching { JSONObject(File(d, "journal.json").readText()) }.getOrNull() ?: return@mapNotNull null
            FlightEntry(d, o.optString("title", d.name), o.optString("summary", "(résumé en cours…)"),
                (d.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()))
        }
}
