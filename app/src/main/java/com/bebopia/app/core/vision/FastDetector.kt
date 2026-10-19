package com.bebopia.app.core.vision

import java.text.Normalizer

/** Objet repéré par le détecteur du téléphone (catégorie en anglais, comme dans le jeu d'images COCO). */
class Seen(val label: String, val score: Double, val box: Box)

/**
 * Détecteur d'objets qui tourne sur le téléphone, sans Internet, sur l'image actuelle de la caméra.
 * Ne connaît que les ~80 catégories d'objets courants (personnes, animaux, véhicules, objets du quotidien).
 * tiled : l'image est découpée en 3 morceaux pour mieux voir les petits objets lointains (plus lent).
 */
interface FastDetector {
    fun detect(tiled: Boolean = false): List<Seen>
    /** Temps moyen d'analyse d'une image (ms), pour information. */
    val averageMs: Double
}

/** Correspondance entre ce que demande le pilote (« le chien noir ») et les catégories du détecteur. */
object CocoClasses {
    /** Catégories du détecteur, et les catégories avec lesquelles il les confond souvent (vues de loin). */
    private val GROUPS: List<Pair<List<String>, Set<String>>> = listOf(
        listOf("chien", "chiot", "toutou") to setOf("dog", "cat", "sheep", "bear"),
        listOf("chat", "chaton", "minou") to setOf("cat", "dog"),
        listOf("cheval", "chevaux", "poney", "jument", "ane") to setOf("horse", "cow", "sheep"),
        listOf("vache", "vaches", "veau", "taureau", "boeuf", "betail") to setOf("cow", "horse", "sheep"),
        listOf("mouton", "moutons", "brebis", "chevre", "agneau") to setOf("sheep", "cow", "dog"),
        listOf("oiseau", "pigeon", "corbeau", "poule", "canard", "mouette") to setOf("bird"),
        listOf("personne", "homme", "femme", "enfant", "gens", "moi", "quelqu'un", "garcon", "fille", "pilote", "randonneur", "cycliste", "coureur") to setOf("person"),
        listOf("voiture", "auto", "4x4", "vehicule") to setOf("car", "truck", "bus"),
        listOf("camion", "camionnette", "fourgon", "tracteur") to setOf("truck", "car", "bus"),
        listOf("bus", "car scolaire") to setOf("bus", "truck"),
        listOf("moto", "scooter") to setOf("motorcycle", "bicycle"),
        listOf("velo", "bicyclette", "vtt") to setOf("bicycle", "motorcycle"),
        listOf("bateau", "barque", "kayak", "voilier") to setOf("boat"),
        listOf("avion") to setOf("airplane"),
        listOf("ballon", "balle") to setOf("sports ball"),
        listOf("frisbee") to setOf("frisbee"),
        listOf("cerf-volant", "cerf volant") to setOf("kite"),
        listOf("parapluie", "parasol") to setOf("umbrella"),
        listOf("sac a dos") to setOf("backpack", "handbag", "suitcase"),
        listOf("sac") to setOf("handbag", "backpack", "suitcase"),
        listOf("valise") to setOf("suitcase", "handbag", "backpack"),
        listOf("chaise", "fauteuil") to setOf("chair", "couch"),
        listOf("canape") to setOf("couch", "chair", "bed"),
        listOf("banc") to setOf("bench"),
        listOf("table") to setOf("dining table"),
        listOf("bouteille") to setOf("bottle", "vase"),
        listOf("tasse", "mug", "verre") to setOf("cup", "wine glass", "bowl"),
        listOf("plante", "pot de fleur", "fleur", "tulipe", "bouquet") to setOf("potted plant", "vase"),
        listOf("vase") to setOf("vase", "potted plant", "bottle"),
        listOf("peluche", "nounours", "doudou") to setOf("teddy bear"),
        listOf("telephone", "portable", "smartphone") to setOf("cell phone", "remote"),
        listOf("telecommande") to setOf("remote", "cell phone"),
        listOf("ordinateur", "pc portable") to setOf("laptop", "tv", "keyboard"),
        listOf("television", "tele", "ecran") to setOf("tv", "laptop"),
        listOf("livre") to setOf("book"),
        listOf("horloge", "pendule") to setOf("clock"),
        listOf("skate") to setOf("skateboard"),
        listOf("planche de surf", "surf") to setOf("surfboard"),
        listOf("raquette") to setOf("tennis racket"),
        listOf("ciseaux") to setOf("scissors"),
        listOf("frigo", "refrigerateur") to setOf("refrigerator"),
        listOf("lit") to setOf("bed", "couch"),
        listOf("ours") to setOf("bear", "dog"),
        listOf("train") to setOf("train"),
    )

    val ALL: Set<String> = GROUPS.flatMap { it.second }.toSet()

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Catégories à chercher pour [target] (null si le détecteur ne connaît pas ce genre d'objet). [hint] : catégorie donnée par l'IA. */
    fun forTarget(target: String, hint: String? = null): Set<String>? {
        hint?.trim()?.lowercase()?.takeIf { it in ALL }?.let { h ->
            return GROUPS.firstOrNull { h in it.second && it.second.first() == h }?.second ?: setOf(h)
        }
        val t = " " + norm(target).replace(Regex("[^a-z0-9' -]"), " ") + " "
        // Les noms les plus longs d'abord (« sac a dos » avant « sac »)
        return GROUPS.flatMap { (words, set) -> words.map { it to set } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (w, _) -> t.contains(" $w ") || t.contains(" ${w}s ") || t.contains(" ${w}x ") }?.second
    }

    /** La demande n'est qu'un nom de catégorie (« un chien », « une voiture ») : le détecteur suffit, sans l'IA pour confirmer. */
    fun isGeneric(target: String): Boolean {
        val words = norm(target).replace(Regex("[^a-z0-9' -]"), " ").split(' ', '\'').filter { it.isNotBlank() }
            .filter { it !in setOf("le", "la", "les", "un", "une", "des", "du", "de", "mon", "ma", "mes", "l", "d", "ce", "cette") }
        return words.size == 1 && forTarget(words[0]) != null
    }
}
