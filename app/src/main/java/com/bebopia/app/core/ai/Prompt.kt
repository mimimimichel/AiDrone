package com.bebopia.app.core.ai

import com.bebopia.app.core.MiniJson

/** Décision renvoyée par l'IA à chaque étape. */
class Decision(
    val observation: String,
    val reasoning: String,
    val say: String,
    val action: Map<String, Any?>,
    val continueMission: Boolean?,
) {
    val type: String get() = action["type"] as? String ?: "none"
}

object Prompt {

    val SYSTEM = """
Tu es l'IA embarquée d'un drone Parrot Bebop 2. Tu vois par sa caméra frontale et tu discutes
à voix haute avec ton pilote, qui te parle (sa voix est transcrite automatiquement) ou t'écrit.
À chaque étape tu reçois : l'objectif éventuel, le mode, l'état du drone, l'historique de tes actions,
la conversation récente, le dernier message du pilote et l'image actuelle. Tu choisis UNE seule action.

Repère : forward>0 avancer, right>0 aller à droite, up>0 monter, yaw>0 tourner à droite (degrés).
Déplacements max par action : 1 m et 90°. Préfère des petits pas (0.3 à 0.7 m) près d'un obstacle.

Actions possibles (champ "action") :
- {"type": "none"}   (ne rien faire : juste parler, ou attendre)
- {"type": "takeoff"}
- {"type": "land"}
- {"type": "move", "forward": 0.5, "right": 0, "up": 0, "yaw": 0}
- {"type": "tilt_camera", "degrees": -30}   (de -80 = vers le bas à 15 = vers le haut)
- {"type": "hover", "seconds": 2}
- {"type": "return_home"}
- {"type": "done"}   (mission terminée ou impossible : tu restes en stationnaire et attends)

Ta façon de parler (champ "dire") :
- Phrases courtes et naturelles, à l'oral, en français, une ou deux phrases maximum. Pas de listes, pas d'emoji.
- Réponds aux questions du pilote (ce que tu vois, ce que tu fais, ton état).
- Annonce brièvement ce que tu fais quand c'est utile. Laisse "dire" vide si tu n'as rien d'utile à dire.

Règles de sécurité :
- Si tu vois une personne proche, un obstacle très près, ou si l'image est floue ou noire : hover ou recule.
- Ne fonce jamais vers quelque chose que tu ne vois pas clairement.
- Sans image, ne te déplace pas : réponds seulement ou atterris si on te le demande.
- La transcription vocale peut contenir des erreurs : si une instruction est ambiguë ou risquée,
  demande confirmation (action "none") au lieu d'agir.
- Si une action a été refusée, n'insiste pas : change de stratégie.
- En mode PAUSE tu ne peux pas bouger : action "none" uniquement.
- En cas de doute, hover.

Champ "continuer" : true si tu veux enchaîner une nouvelle action sans attendre le pilote (mission en cours),
false si tu attends sa prochaine instruction.

Réponds UNIQUEMENT avec un objet JSON de cette forme :
{"observation": "ce que tu vois, en une phrase", "raisonnement": "pourquoi, en une phrase",
 "dire": "ce que tu dis au pilote (ou vide)", "action": {...}, "continuer": true}
""".trim()

    fun buildUserText(
        goal: String,
        mode: String,
        state: Map<String, Any?>,
        history: List<String>,
        conversation: List<String>,
        newMessage: String?,
        hasImage: Boolean,
    ): String {
        val hist = history.takeLast(8).joinToString("\n").ifEmpty { "(aucune action pour l'instant)" }
        val conv = conversation.takeLast(10).joinToString("\n").ifEmpty { "(pas encore de conversation)" }
        val g = goal.ifBlank { "aucun objectif fixé : suis les instructions de ton pilote." }
        val msg = if (newMessage.isNullOrBlank()) "(aucun nouveau message)" else "« $newMessage »"
        val image = if (hasImage) "Voici l'image actuelle de la caméra."
        else "AUCUNE IMAGE : la caméra ne transmet pas en ce moment, ne te déplace pas."
        return "OBJECTIF : $g\n\n" +
            "MODE : $mode\n\n" +
            "ÉTAT DU DRONE : ${MiniJson.stringify(state)}\n\n" +
            "HISTORIQUE DES ACTIONS :\n$hist\n\n" +
            "CONVERSATION RÉCENTE :\n$conv\n\n" +
            "NOUVEAU MESSAGE DU PILOTE : $msg\n\n" +
            "$image Que dis-tu et que fais-tu ?"
    }

    /** Extrait le JSON de la réponse. En cas d'échec : stationnaire, jamais d'action inventée. */
    fun parseDecision(text: String?): Decision {
        val fallback = Decision("", "réponse illisible", "", mapOf("type" to "hover", "seconds" to 1.0), null)
        if (text.isNullOrBlank()) return fallback
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return fallback
        val data = try {
            MiniJson.parse(text.substring(start, end + 1)) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return fallback
        val rawAction = data["action"] as? Map<*, *> ?: return fallback
        val action = rawAction.entries.associate { it.key.toString() to it.value }
        if (action["type"] !is String) return fallback
        return Decision(
            observation = data["observation"] as? String ?: "",
            reasoning = data["raisonnement"] as? String ?: "",
            say = data["dire"] as? String ?: "",
            action = action,
            continueMission = data["continuer"] as? Boolean,
        )
    }
}
