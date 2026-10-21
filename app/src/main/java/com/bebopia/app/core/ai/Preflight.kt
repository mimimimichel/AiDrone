package com.bebopia.app.core.ai

import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.cos

/** Résultat de « est-ce que je peux voler ici ? » : verdict (ok, prudence, non), explication, hauteur max de la zone. */
class PreflightReport(
    val verdict: String,
    val text: String,
    val maxHeightM: Int?,
    val windMs: Double?,
    val gustMs: Double?,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Avant de voler dehors : zones réglementées (carte officielle des restrictions pour les drones de loisir, IGN)
 * et météo du moment (vent, rafales, pluie, froid, Open-Meteo). [fetch] lit une adresse web et renvoie le texte.
 */
class Preflight(private val fetch: (String) -> String) {
    class Zone(val limit: String, val remark: String)
    class Weather(val wind: Double?, val gust: Double?, val rain: Double?, val temp: Double?, val code: Int?)

    fun check(lat: Double, lon: Double, droneName: String, maxWindMs: Double): PreflightReport {
        val zones = runCatching { parseZones(fetch(zonesUrl(lat, lon))) }.getOrNull()
        val weather = runCatching { parseWeather(fetch(weatherUrl(lat, lon))) }.getOrNull()
        return verdict(zones, weather, droneName, maxWindMs)
    }

    fun zonesUrl(lat: Double, lon: Double): String {
        // Petit carré d'environ 60 m autour du point
        val dLat = 0.0003
        val dLon = 0.0003 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val bbox = "%.6f,%.6f,%.6f,%.6f,urn:ogc:def:crs:EPSG::4326".format(java.util.Locale.ROOT, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        return "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature" +
            "&TYPENAMES=TRANSPORTS.DRONES.RESTRICTIONS:carte_restriction_drones_lf&OUTPUTFORMAT=application/json&COUNT=20" +
            "&BBOX=" + URLEncoder.encode(bbox, "UTF-8")
    }

    fun weatherUrl(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.ROOT, lat, lon) +
        "&current=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m&wind_speed_unit=ms"

    fun parseZones(json: String): List<Zone> {
        val f = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until f.length()).mapNotNull { i ->
            val p = f.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
            Zone(p.optString("limite").trim(), p.optString("remarque").trim())
        }.distinctBy { it.limit + it.remark }
    }

    fun parseWeather(json: String): Weather {
        val c = JSONObject(json).optJSONObject("current") ?: return Weather(null, null, null, null, null)
        fun d(k: String) = c.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
        return Weather(d("wind_speed_10m"), d("wind_gusts_10m"), d("precipitation"), d("temperature_2m"), c.optInt("weather_code", -1).takeIf { it >= 0 })
    }

    fun verdict(zones: List<Zone>?, w: Weather?, droneName: String, maxWindMs: Double): PreflightReport {
        val no = ArrayList<String>()
        val careful = ArrayList<String>()
        var maxH: Int? = null
        if (zones == null) careful.add("zones réglementées non vérifiées (pas de réseau)")
        else {
            for (z in zones) {
                if (z.limit.contains("interdit", true)) no.add("zone : ${z.limit.trimEnd('*', ' ')}" + if (z.remark.isNotBlank()) " (${z.remark})" else "")
                Regex("(\\d+)\\s*m").find(z.limit)?.groupValues?.get(1)?.toIntOrNull()?.let { h -> maxH = minOf(maxH ?: h, h) }
            }
            if (zones.isEmpty()) maxH = 120
        }
        maxH?.let { if (it < 120 && no.isEmpty()) careful.add("hauteur limitée à $it m dans cette zone") }
        if (w == null) careful.add("météo non vérifiée (pas de réseau)")
        else {
            val gust = w.gust ?: w.wind
            if (gust != null) {
                if (gust > maxWindMs) no.add("rafales à %.0f km/h, trop fort pour le %s (max ~%.0f km/h)".format(gust * 3.6, droneName, maxWindMs * 3.6))
                else if (gust > maxWindMs * 0.7) careful.add("vent soutenu, rafales à %.0f km/h : vole bas et près de toi".format(gust * 3.6))
            }
            if ((w.rain ?: 0.0) > 0.1 || (w.code ?: 0) in 51..99) no.add("pluie ou orage : le drone n'est pas étanche")
            if ((w.temp ?: 20.0) < 5) careful.add("froid (%.0f °C) : la batterie se vide plus vite, garde-la au chaud avant de voler".format(w.temp))
        }
        val verdict = if (no.isNotEmpty()) "non" else if (careful.isNotEmpty()) "prudence" else "ok"
        val meteo = w?.let { m ->
            listOfNotNull(
                m.wind?.let { "vent %.0f km/h".format(it * 3.6) },
                m.gust?.let { "rafales %.0f km/h".format(it * 3.6) },
                m.temp?.let { "%.0f °C".format(it) },
            ).joinToString(", ")
        }.orEmpty()
        val head = when (verdict) {
            "non" -> "⛔ Pas ici, pas maintenant : " + no.joinToString(" ; ")
            "prudence" -> "⚠️ Possible, avec prudence : " + careful.joinToString(" ; ")
            else -> "✅ Tu peux voler ici" + (maxH?.let { if (it >= 120) " (jusqu'à 120 m, la limite légale)" else " (jusqu'à $it m)" } ?: "")
        }
        val also = if (verdict == "non" && careful.isNotEmpty()) ". Aussi : " + careful.joinToString(" ; ") else ""
        return PreflightReport(verdict, head + also + (if (meteo.isNotBlank()) ". Météo : $meteo." else ".") +
            " Reste à vue, loin des gens, 120 m maximum.", maxH, w?.wind, w?.gust)
    }
}
