package com.bebopia.app.core.ai

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Suivi du relief : garder la même hauteur au-dessus du sol en montant ou en descendant une pente.
 * L'altitude du terrain vient du modèle de terrain de l'IGN (précis au mètre en France, à défaut le monde entier) ;
 * on regarde aussi 10, 20 et 30 m devant le drone pour monter avant la pente plutôt que dedans.
 * [fetch] lit une adresse web et renvoie le texte.
 */
class Terrain(private val fetch: (String) -> String) {
    private val cache = HashMap<Long, Double>()

    /** Clé de cache : carré d'environ 5 m. */
    private fun key(lat: Double, lon: Double) = (lat * 20000).roundToLong() * 100_000_000L + (lon * 20000).roundToLong()

    /** Altitudes du terrain (m) aux points donnés. Bloquant ; null si le service ne répond pas. */
    fun elevations(points: List<Pair<Double, Double>>): List<Double>? {
        val missing = points.filter { cache[key(it.first, it.second)] == null }.distinctBy { key(it.first, it.second) }
        if (missing.isNotEmpty()) {
            val url = "https://data.geopf.fr/altimetrie/1.0/calcul/alti/rest/elevation.json?resource=ign_rge_alti_wld&zonly=true" +
                "&lon=" + missing.joinToString("|") { "%.6f".format(java.util.Locale.ROOT, it.second) } +
                "&lat=" + missing.joinToString("|") { "%.6f".format(java.util.Locale.ROOT, it.first) }
            val arr = runCatching { JSONObject(fetch(url)).getJSONArray("elevations") }.getOrNull() ?: return null
            for (i in missing.indices) {
                val v = arr.optDouble(i, Double.NaN)
                if (!v.isNaN() && v > -500) synchronized(cache) { cache[key(missing[i].first, missing[i].second)] = v }
            }
        }
        return points.map { synchronized(cache) { cache[key(it.first, it.second)] } ?: return null }
    }

    /**
     * Vitesse verticale (m/s, + = monter) pour garder [targetAgl] mètres au-dessus du sol.
     * [alt] : altitude du drone par rapport au décollage ; [groundAtHome] : altitude du terrain au décollage ;
     * [course] : direction du déplacement (°) ou null à l'arrêt ; [agl] : hauteur mesurée directement (capteur), si disponible.
     */
    fun verticalSpeed(
        lat: Double, lon: Double, course: Double?, alt: Double, groundAtHome: Double, targetAgl: Double, agl: Double? = null,
    ): Double? {
        val pts = ArrayList<Pair<Double, Double>>()
        pts.add(lat to lon)
        if (course != null) for (d in listOf(10.0, 20.0, 30.0)) {
            val b = Math.toRadians(course)
            pts.add((lat + d * cos(b) / 111320.0) to (lon + d * sin(b) / (111320.0 * cos(Math.toRadians(lat)))))
        }
        val elev = elevations(pts) ?: return null
        // Altitude voulue (par rapport au décollage) : au-dessus du point le plus haut entre ici et 30 m devant
        val ground = elev.max()
        var wanted = ground - groundAtHome + targetAgl
        // Capteur de hauteur (précis près du sol) : corrige l'écart du modèle de terrain sous le drone
        if (agl != null && agl < 8) wanted = maxOf(wanted, alt + (targetAgl - agl))
        val err = wanted - alt
        if (abs(err) < 0.3) return 0.0
        return (err * 0.6).coerceIn(-1.5, 2.0)
    }
}
