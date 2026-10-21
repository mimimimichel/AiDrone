package com.bebopia.app.core.ai

import com.bebopia.app.core.DroneBase
import com.bebopia.app.core.norm180
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Recherche dans la maison, pièce par pièce : repérage de la pièce (tour complet) ; si l'objet n'y est pas,
 * passage de la porte ouverte la plus proche qui n'a pas encore été franchie, et on recommence.
 * Toujours en mode intérieur : petits pas lents, jamais au-dessus du plafond, place libre vérifiée.
 */
class HouseSearch(
    private val drone: DroneBase,
    private val surveyor: Surveyor,
    private val passer: DoorPasser,
    private val finder: () -> ObjectFinder?,
    private val memory: SceneMemory,
    private val shouldStop: () -> Boolean,
    private val report: (String) -> Unit,
    private val onPlan: (Plan) -> Unit,
) {
    /** Portes franchies pendant la recherche, dans l'ordre (pour revenir). */
    private val path = ArrayList<SceneItem>()

    fun run(target: String, maxRooms: Int = 6): String {
        if (!drone.flying) return "refusé : le drone est au sol, décolle d'abord"
        val t = target.trim().ifEmpty { return "refusé : objet à chercher non précisé" }
        drone.indoor = true
        path.clear()
        var plan = Plan("trouver « $t » dans la maison", listOf(
            PlanStep("Repérer la pièce et y chercher « $t »"),
            PlanStep("Sinon, passer une porte vers une autre pièce"),
            PlanStep("Recommencer dans chaque pièce (jusqu'à $maxRooms)"),
            PlanStep("M'approcher de « $t »"),
        ), approved = true)
        fun step(i: Int, st: StepState, note: String = "") {
            plan = plan.copy(steps = plan.steps.mapIndexed { k, s -> if (k == i) PlanStep(s.text, st, note) else s })
            onPlan(plan)
        }
        fun finish(ok: Boolean, summary: String): String {
            plan = plan.copy(finished = true, success = ok, summary = summary)
            onPlan(plan)
            return summary
        }
        onPlan(plan)
        var rooms = 1
        while (true) {
            if (shouldStop()) return finish(false, "recherche arrêtée par le pilote")
            step(0, StepState.DOING, "pièce $rooms")
            val res = surveyor.survey(t)
            if (res.text.startsWith("repérage arrêté") || res.text.startsWith("refusé")) return finish(false, res.text)
            if (res.targetBearing != null) {
                step(0, StepState.DONE, "vu dans la pièce $rooms")
                step(3, StepState.DOING)
                var left = norm180(res.targetBearing - drone.compass())
                while (abs(left) > 5) { val p = left.coerceIn(-90.0, 90.0); drone.move(0.0, 0.0, 0.0, p); left -= p }
                val f = finder()
                val r = f?.search(t, approach = true) ?: "trouvé : ${res.targetDesc}"
                step(3, if (r.startsWith("arrivé") || r.startsWith("trouvé")) StepState.DONE else StepState.FAILED, r.take(80))
                return finish(true, "« $t » trouvé dans la pièce $rooms : $r")
            }
            step(0, StepState.DONE, "pas dans la pièce $rooms")
            if (rooms >= maxRooms) return finish(false, "pas trouvé « $t » dans les $rooms pièces visitées")
            // Porte suivante : la plus proche pas encore franchie
            step(1, StepState.DOING)
            val here = drone.positionNE()
            var passed = false
            for (door in memory.doors().sortedBy { hypot(it.n - here.first, it.e - here.second) }) {
                if (shouldStop()) return finish(false, "recherche arrêtée par le pilote")
                report("🏠 Pièce $rooms : pas de « $t ». Je passe « ${door.label} »")
                val r = passer.pass(door.label, door)
                if (r.startsWith("porte franchie")) {
                    path.add(door)
                    passed = true
                    break
                }
                report("🚪 ${r.take(90)} — j'essaie une autre porte")
                door.failed = true
            }
            if (!passed) {
                step(1, StepState.FAILED, "plus de porte ouverte")
                return finish(false, "pas trouvé « $t » dans les $rooms pièce(s) visitée(s) : je ne vois plus de porte ouverte à franchir")
            }
            step(1, StepState.DONE, "pièce ${rooms + 1}")
            step(2, StepState.DOING, "pièce ${rooms + 1}")
            rooms++
        }
    }

    /** Retour vers la pièce de départ : repasse les portes franchies, dans l'ordre inverse. */
    fun comeBack(): String {
        if (path.isEmpty()) return "aucune porte franchie pendant la recherche : je suis déjà dans la pièce de départ"
        for (door in path.asReversed()) {
            if (shouldStop()) return "retour arrêté par le pilote"
            door.traversed = false
            door.failed = false
            report("↩ Je repasse « ${door.label} »")
            val r = passer.pass(door.label, door)
            if (!r.startsWith("porte franchie")) return "retour arrêté : $r"
        }
        path.clear()
        return "revenu dans la pièce de départ"
    }
}
