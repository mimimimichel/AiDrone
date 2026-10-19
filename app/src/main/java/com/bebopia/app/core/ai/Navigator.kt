package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.norm180
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Point marqué par le pilote. Avec le GPS il est gardé d'un vol à l'autre ; sinon seulement pour ce vol. */
class Waypoint(val label: String, val n: Double, val e: Double, val alt: Double, val lat: Double?, val lon: Double?)

/** Zone regardée pendant une recherche (centre et rayon vu, en mètres nord/est du départ). */
class Coverage(val n: Double, val e: Double, val r: Double)

/** Repère posé sur la carte : cible trouvée, piste, photo… */
class Pin(val n: Double, val e: Double, val label: String, val found: Boolean)

/**
 * Navigation sur la carte : trace du vol, points marqués, « va là où je regarde », retour par le même chemin.
 *
 * Tout est en mètres (nord, est) par rapport au point de décollage : GPS quand le drone en a,
 * sinon l'estimation interne. Chaque trajet est refait à chaque segment d'après la position mesurée.
 */
class Navigator(
    private val drone: DroneBase,
    private val simulation: Boolean,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    /** Points à garder d'un vol à l'autre (JSON). */
    private val save: (String) -> Unit,
) {
    private class Abort(msg: String) : Exception(msg)

    /** Vérification de la trajectoire par l'image avant d'avancer (distance permise, ou refus). */
    var pathCheck: ((Double) -> Pair<Double, String?>)? = null

    private val track = ArrayList<Pair<Double, Double>>()
    private val waypoints = ArrayList<Waypoint>()
    private val coverage = ArrayList<Coverage>()
    private val pins = ArrayList<Pin>()
    private var wasFlying = false

    fun load(json: String) {
        runCatching {
            val a = JSONArray(json)
            synchronized(this) {
                waypoints.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    waypoints.add(Waypoint(o.getString("label"), 0.0, 0.0, o.optDouble("alt", 5.0), o.getDouble("lat"), o.getDouble("lon")))
                }
            }
        }
    }

    private fun persist() {
        val a = JSONArray()
        synchronized(this) {
            waypoints.filter { it.lat != null }.forEach {
                a.put(JSONObject().put("label", it.label).put("alt", it.alt).put("lat", it.lat).put("lon", it.lon))
            }
        }
        save(a.toString())
    }

    /** Appelé environ une fois par seconde : enregistre la trace du vol. */
    fun tick() {
        val flying = drone.flying
        if (flying && !wasFlying) newFlight()
        wasFlying = flying
        if (!flying) return
        val p = drone.positionNE()
        synchronized(this) {
            val last = track.lastOrNull()
            val minGap = if (drone.hasGps) 2.0 else 0.4
            if (last == null || hypot(p.first - last.first, p.second - last.second) >= minGap) {
                track.add(p)
                if (track.size > 3000) track.removeAt(1)
            }
        }
    }

    private fun newFlight() {
        synchronized(this) {
            track.clear()
            track.add(0.0 to 0.0)
            coverage.clear()
            pins.clear()
            // Les points sans GPS n'ont de sens que pour un vol.
            waypoints.removeAll { it.lat == null }
        }
    }

    // ---- positions ------------------------------------------------------------------------------
    /** Position (nord, est) d'un point marqué, recalculée sur le point de départ actuel s'il a des coordonnées GPS. */
    private fun ne(w: Waypoint): Pair<Double, Double>? {
        if (w.lat == null || w.lon == null) return w.n to w.e
        val h = drone.homeGps() ?: return null
        return (w.lat - h.first) * 111320.0 to (w.lon - h.second) * 111320.0 * cos(Math.toRadians(h.first))
    }

    fun addCoverage(radius: Double) {
        val p = drone.positionNE()
        synchronized(this) { coverage.add(Coverage(p.first, p.second, radius)); if (coverage.size > 300) coverage.removeAt(0) }
    }

    /** Repère sur la carte, à [distance] m devant la caméra (null = sous le drone). */
    fun addPin(label: String, found: Boolean, distance: Double?) {
        val p = drone.positionNE()
        val h = Math.toRadians(drone.lookHeading())
        val d = distance ?: 0.0
        synchronized(this) {
            pins.add(Pin(p.first + d * cos(h), p.second + d * sin(h), label.take(30), found))
            if (pins.size > 50) pins.removeAt(0)
        }
    }

    /** Pour la mini-carte du casque. */
    fun mapJson(): JSONObject {
        val pos = drone.positionNE()
        return synchronized(this) {
            val t = JSONArray()
            val step = maxOf(1, track.size / 200)
            for (i in track.indices step step) t.put(JSONArray().put(r1(track[i].first)).put(r1(track[i].second)))
            val w = JSONArray()
            waypoints.forEachIndexed { i, wp -> ne(wp)?.let { w.put(JSONArray().put(r1(it.first)).put(r1(it.second)).put(i + 1).put(wp.label)) } }
            val c = JSONArray()
            coverage.forEach { c.put(JSONArray().put(r1(it.n)).put(r1(it.e)).put(r1(it.r))) }
            val p = JSONArray()
            pins.forEach { p.put(JSONArray().put(r1(it.n)).put(r1(it.e)).put(it.label).put(it.found)) }
            JSONObject().put("pos", JSONArray().put(r1(pos.first)).put(r1(pos.second)))
                .put("gps", drone.hasGps).put("track", t).put("wps", w).put("cov", c).put("pins", p)
        }
    }

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0

    fun waypointCount(): Int = synchronized(this) { waypoints.size }

    fun clearWaypoints() {
        synchronized(this) { waypoints.clear() }
        persist()
    }

    fun describeWaypoints(): String = synchronized(this) {
        if (waypoints.isEmpty()) "aucun point marqué"
        else waypoints.mapIndexed { i, w ->
            val d = ne(w)?.let { p -> val q = drone.positionNE(); " à %.0f m".format(hypot(p.first - q.first, p.second - q.second)) } ?: ""
            "${i + 1}. ${w.label}$d"
        }.joinToString(" ; ")
    }

    // ---- actions --------------------------------------------------------------------------------
    fun markPoint(label: String): String {
        val p = drone.positionNE()
        val g = drone.gps()
        val n = synchronized(this) {
            val name = label.trim().ifEmpty { "point ${waypoints.size + 1}" }
            waypoints.add(Waypoint(name, p.first, p.second, drone.altitude(), g?.first, g?.second))
            if (waypoints.size > 30) waypoints.removeAt(0)
            waypoints.size
        }
        persist()
        val w = synchronized(this) { waypoints.last() }
        report("📍 Point $n marqué : ${w.label}")
        return "point $n « ${w.label} » enregistré" + if (g == null) " (sans GPS : valable pour ce vol seulement)" else " (GPS : gardé pour les prochains vols)"
    }

    private fun find(ref: String): Pair<Int, Waypoint>? = synchronized(this) {
        val r = ref.trim().lowercase()
        r.filter { it.isDigit() }.toIntOrNull()?.let { i -> if (i in 1..waypoints.size) return@synchronized i to waypoints[i - 1] }
        waypoints.withIndex().firstOrNull { it.value.label.lowercase().contains(r) || r.contains(it.value.label.lowercase()) }
            ?.let { it.index + 1 to it.value }
    }

    fun goToPoint(ref: String): String = guarded {
        val (i, w) = find(ref) ?: return@guarded "refusé : point « $ref » inconnu. Points : ${describeWaypoints()}"
        val p = ne(w) ?: return@guarded "refusé : ce point a des coordonnées GPS mais le drone n'a pas encore de point de départ GPS"
        report("📍 Je vais au point $i (${w.label})")
        goTo(p.first, p.second, w.alt)
        "arrivé au point $i « ${w.label} »"
    }

    fun tour(): String = guarded {
        val list = synchronized(this) { waypoints.toList() }
        if (list.isEmpty()) return@guarded "refusé : aucun point marqué. Dis « marque ce point » aux endroits voulus."
        for ((i, w) in list.withIndex()) {
            val p = ne(w) ?: continue
            report("📍 Tour : point ${i + 1}/${list.size} (${w.label})")
            goTo(p.first, p.second, w.alt)
            Thread.sleep(1500)
        }
        "tour des ${list.size} points terminé ; je suis au dernier point"
    }

    /**
     * « Va là » : le point au sol au centre de la caméra (donc là où regarde le pilote dans le casque).
     * Distance = altitude / tan(inclinaison de la caméra).
     */
    fun goToLook(): String = guarded {
        if (!drone.flying) return@guarded "refusé : le drone est au sol"
        val below = -drone.gimbalPitch()
        val alt = drone.altitude()
        if (below < 6) return@guarded "refusé : la caméra regarde à l'horizontale. Regarde (baisse la tête vers) le point au sol où aller."
        if (alt < 0.5) return@guarded "refusé : trop bas pour viser un point au sol"
        val d = alt / tan(Math.toRadians(below))
        val go = d - if (drone.hasGps) 1.5 else 0.3
        if (go < 0.3) return@guarded "déjà au-dessus du point visé"
        val h = Math.toRadians(drone.lookHeading())
        val p = drone.positionNE()
        val tn = p.first + go * cos(h)
        val te = p.second + go * sin(h)
        if (hypot(tn, te) > drone.limits.maxDistanceM) {
            return@guarded "refusé : le point visé (à %.0f m) sort du rayon de %.0f m".format(d, drone.limits.maxDistanceM)
        }
        report("🎯 Je vais au point visé, à %.0f m".format(d))
        goTo(tn, te, null)
        "arrivé au point visé (%.0f m)".format(go)
    }

    /** Retour au départ en refaisant le chemin du vol à l'envers (évite les obstacles déjà contournés). */
    fun retrace(land: Boolean): String = guarded {
        if (!drone.flying) return@guarded "refusé : le drone est au sol"
        val pts = synchronized(this) { track.toList() }
        val gap = if (drone.hasGps) 8.0 else 1.0
        val path = ArrayList<Pair<Double, Double>>()
        var last = drone.positionNE()
        for (p in pts.asReversed()) {
            if (hypot(p.first - last.first, p.second - last.second) >= gap) {
                path.add(p)
                last = p
            }
        }
        path.add(0.0 to 0.0)
        report("↩ Retour par le même chemin (${path.size} étapes)")
        for ((i, p) in path.withIndex()) {
            if (i % 3 == 0 || i == path.size - 1) report("↩ Étape ${i + 1}/${path.size}")
            goTo(p.first, p.second, null)
        }
        if (land) {
            report("🛬 Arrivé au départ : j'atterris")
            drone.land()
            "revenu au départ par le même chemin, et posé"
        } else "revenu au-dessus du point de départ par le même chemin"
    }

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (e: Abort) {
        e.message ?: "interrompu"
    }

    private fun checkStop() {
        if (shouldStop()) throw Abort("arrêté par le pilote (le drone reste sur place)")
        if (!drone.flying) throw Abort("arrêté : le drone n'est plus en vol")
    }

    private fun turnTo(bearing: Double) {
        var left = norm180(bearing - drone.compass())
        if (abs(left) < 8) return
        if (simulation) report("(Simulation) Tourne le téléphone de ${abs(left).roundToInt()}° vers la ${if (left > 0) "droite" else "gauche"}")
        var guard = 0
        while (abs(left) >= 8 && guard++ < 6) {
            checkStop()
            val part = left.coerceIn(-drone.limits.maxYawDeg, drone.limits.maxYawDeg)
            val r = drone.move(0.0, 0.0, 0.0, part, maxStep = drone.limits.missionStepM)
            if (r.startsWith("refusé") || r.startsWith("interrompu")) throw Abort("rotation impossible : $r")
            left = norm180(bearing - drone.compass())
        }
    }

    /** Va au point (nord, est), en se recalant après chaque segment. [alt] : altitude à prendre en route. */
    private fun goTo(n: Double, e: Double, alt: Double?) {
        if (hypot(n, e) > drone.limits.maxDistanceM + 1) {
            throw Abort("refusé : ce point est à %.0f m du départ, au-delà du rayon de %.0f m".format(hypot(n, e), drone.limits.maxDistanceM))
        }
        val tol = if (drone.hasGps) 2.0 else 0.25
        alt?.let { a ->
            val du = a.coerceIn(drone.limits.minAltitudeM, drone.limits.maxAltitudeM) - drone.altitude()
            if (abs(du) > 0.5) {
                checkStop()
                drone.move(0.0, 0.0, du, 0.0, maxStep = drone.limits.missionStepM)
            }
        }
        var blocked = 0
        repeat(60) {
            checkStop()
            val p = drone.positionNE()
            val dn = n - p.first
            val de = e - p.second
            val dist = hypot(dn, de)
            if (dist < tol) return
            turnTo(Math.toDegrees(atan2(de, dn)))
            if (drone.gimbalPitch() < -25) drone.tiltCamera(-10.0) // le détecteur d'obstacles a besoin de voir devant
            var step = minOf(dist, if (simulation) 1.0 else drone.limits.missionStepM)
            pathCheck?.let { check ->
                val (ok, refusal) = check(step)
                if (refusal != null) {
                    if (++blocked >= 3) throw Abort("chemin bloqué par un obstacle : $refusal")
                    report("⚠ Obstacle devant : je monte un peu pour passer au-dessus")
                    drone.move(0.0, 0.0, 1.0, 0.0)
                    return@repeat
                }
                step = ok
            }
            if (simulation) report("(Simulation) Avance le téléphone d'environ ${(step * 100).roundToInt()} cm")
            val r = drone.move(step, 0.0, 0.0, 0.0, maxStep = drone.limits.missionStepM)
            if (r.startsWith("interrompu")) throw Abort("arrêté : $r")
            if (r.startsWith("refusé : obstacle")) {
                if (++blocked >= 3) throw Abort("chemin bloqué : $r")
                Thread.sleep(5500)
            } else if (r.startsWith("refusé")) throw Abort(r)
        }
    }
}
