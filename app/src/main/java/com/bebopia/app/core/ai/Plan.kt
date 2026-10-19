package com.bebopia.app.core.ai

enum class StepState { TODO, DOING, DONE, FAILED, SKIPPED }

class PlanStep(val text: String, val state: StepState = StepState.TODO, val note: String = "")

/**
 * Plan proposé par l'IA pour atteindre un objectif du pilote.
 * Copie immuable : chaque changement crée un nouveau plan (sûr entre les fils).
 */
class Plan(
    val goal: String,
    val steps: List<PlanStep>,
    /** Validé par le pilote : l'IA peut l'exécuter sans redemander chaque déplacement. */
    val approved: Boolean = false,
    val finished: Boolean = false,
    val success: Boolean = false,
    val summary: String = "",
) {
    val running get() = approved && !finished

    fun copy(
        steps: List<PlanStep> = this.steps, approved: Boolean = this.approved, finished: Boolean = this.finished,
        success: Boolean = this.success, summary: String = this.summary,
    ) = Plan(goal, steps, approved, finished, success, summary)

    /** Résumé pour l'IA. */
    fun describe(): String {
        val lines = steps.mapIndexed { i, s ->
            val mark = when (s.state) {
                StepState.TODO -> "à faire"
                StepState.DOING -> "en cours"
                StepState.DONE -> "fait"
                StepState.FAILED -> "échec"
                StepState.SKIPPED -> "sauté"
            }
            "${i + 1}. ${s.text} [$mark]" + if (s.note.isNotBlank()) " (${s.note})" else ""
        }
        val status = when {
            finished -> if (success) "terminé avec succès" else "terminé sans succès"
            approved -> "en cours d'exécution"
            else -> "en attente de validation du pilote"
        }
        return "Objectif : $goal — plan $status\n" + lines.joinToString("\n")
    }
}
