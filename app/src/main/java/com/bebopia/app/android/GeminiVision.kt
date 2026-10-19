package com.bebopia.app.android

import android.util.Log
import com.bebopia.app.core.ai.Detection
import com.bebopia.app.core.ai.ObjectDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Repère un objet sur une image avec Gemini (comme « Où est cette pièce ? » dans Vr2) :
 * Gemini Robotics-ER d'abord (spécialisé dans le repérage d'objets), puis des modèles Flash si besoin.
 */
class GeminiVision(
    private val apiKey: () -> String,
    private val open: (URL) -> HttpURLConnection,
) : ObjectDetector {
    companion object {
        private const val TAG = "GeminiVision"
        val MODELS = listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")

        private val SCHEMA = JSONObject("""
        {"type":"object","properties":{
          "found":{"type":"boolean","description":"true seulement si l'objet cherché est vraiment visible sur l'image"},
          "confidence":{"type":"number","description":"0 à 1"},
          "box_2d":{"type":"array","items":{"type":"integer"},"description":"[ymin, xmin, ymax, xmax] de l'objet, entre 0 et 1000"},
          "description":{"type":"string","description":"une phrase en français : ce que tu vois (l'objet et où il est, ou ce qu'il y a à la place)"}
        },"required":["found","confidence","description"]}
        """.trimIndent())
    }

    @Volatile private var modelIndex = 0

    override fun find(jpegBase64: String, target: String): Detection {
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var lastError: Exception? = null
        for (attempt in MODELS.indices) {
            val model = MODELS[(modelIndex + attempt) % MODELS.size]
            try {
                val d = call(model, key, jpegBase64, target)
                modelIndex = (modelIndex + attempt) % MODELS.size // on garde celui qui marche
                return d
            } catch (e: ModelUnavailable) {
                Log.w(TAG, "$model indisponible : ${e.message}")
                lastError = e
            }
        }
        throw IOException("aucun modèle de vision Gemini disponible (${lastError?.message})")
    }

    private class ModelUnavailable(msg: String) : IOException(msg)

    private val PATH_SCHEMA = JSONObject("""
        {"type":"object","properties":{
          "clear":{"type":"boolean","description":"true si rien ne gêne la trajectoire droit devant sur la distance demandée plus 1 m"},
          "nearest_m":{"type":"number","description":"distance estimée (m) du premier obstacle dans l'axe du drone ; 99 s'il n'y en a pas"},
          "description":{"type":"string","description":"une phrase : ce qu'il y a devant, dans l'axe"}
        },"required":["clear","nearest_m","description"]}
        """.trimIndent())

    /** Y a-t-il un obstacle dans l'axe du drone sur les [forwardM] prochains mètres ? */
    fun checkPath(jpegBase64: String, forwardM: Double): com.bebopia.app.core.ai.PathResult {
        val key = apiKey()
        if (key.isBlank()) return com.bebopia.app.core.ai.PathResult(true, null, "")
        val prompt = "Image de la caméra frontale d'un drone de 30 cm de large, qui va avancer tout droit de " +
            "%.1f m (vers le centre de l'image). Regarde la zone centrale de l'image, dans l'axe de déplacement : ".format(forwardM) +
            "mur, meuble, porte, personne, plante, câble, vitre, objet suspendu… Estime la distance du premier obstacle " +
            "dans l'axe, en t'aidant de la taille des objets connus. En cas de doute sur une distance, sous-estime-la. " +
            "Le sol sous le drone n'est pas un obstacle."
        var last: Exception? = null
        for (attempt in MODELS.indices) {
            val model = MODELS[(modelIndex + attempt) % MODELS.size]
            try {
                val j = generate(model, key, jpegBase64, prompt, PATH_SCHEMA)
                return com.bebopia.app.core.ai.PathResult(
                    clear = j.optBoolean("clear", false),
                    nearestM = j.optDouble("nearest_m", Double.NaN).takeIf { !it.isNaN() && it < 90 },
                    description = j.optString("description"),
                )
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException("vérification de trajectoire impossible (${last?.message})")
    }

    /** Appel générique : image + consigne, réponse JSON selon [schema]. */
    private fun generate(model: String, key: String, jpeg: String, prompt: String, schema: JSONObject): JSONObject {
        val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema).put("temperature", 0.1)
        if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", gen)
        val conn = open(URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) throw ModelUnavailable("$code $msg")
                throw IOException("Gemini, erreur $code : $msg")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }
            val a = answer.indexOf('{')
            val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) throw IOException("réponse illisible")
            return JSONObject(answer.substring(a, b + 1))
        } finally {
            conn.disconnect()
        }
    }

    private fun call(model: String, key: String, jpeg: String, target: String): Detection {
        val prompt = "Image de la caméra frontale d'un drone, en intérieur ou en extérieur. Objet cherché : « $target ».\n" +
            "Regarde attentivement toute l'image, y compris les bords et les petits objets. " +
            "Si tu vois cet objet (ou quelque chose qui y correspond clairement), found = true et encadre-le dans box_2d. " +
            "Sinon found = false et décris brièvement ce que tu vois. N'invente rien : en cas de doute, confidence basse."
        val gen = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseJsonSchema", SCHEMA)
            .put("temperature", 0.2)
        if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", gen)

        val conn = open(URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
                // Modèle absent, surchargé ou quota atteint : on essaie le suivant.
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) {
                    throw ModelUnavailable("$code $msg")
                }
                if (code == 400 || code == 401 || code == 403) throw IOException("clé Gemini refusée ou requête invalide ($code) : $msg")
                throw IOException("Gemini, erreur $code : $msg")
            }
            val out = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString {
                if (out != null) for (i in 0 until out.length()) {
                    val p = out.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return Detection(false, 0.0, 0.5, 0.5, 0.0, 0.0, "réponse illisible")
            val d = JSONObject(answer.substring(start, end + 1))
            val box = d.optJSONArray("box_2d")
            val found = d.optBoolean("found") && box != null && box.length() == 4
            if (!found) return Detection(false, d.optDouble("confidence", 0.0), 0.5, 0.5, 0.0, 0.0, d.optString("description"))
            val y0 = box.optDouble(0) / 1000; val x0 = box.optDouble(1) / 1000
            val y1 = box.optDouble(2) / 1000; val x1 = box.optDouble(3) / 1000
            return Detection(
                found = true,
                confidence = d.optDouble("confidence", 0.6),
                cx = ((x0 + x1) / 2).coerceIn(0.0, 1.0),
                cy = ((y0 + y1) / 2).coerceIn(0.0, 1.0),
                w = kotlin.math.abs(x1 - x0).coerceIn(0.0, 1.0),
                h = kotlin.math.abs(y1 - y0).coerceIn(0.0, 1.0),
                description = d.optString("description"),
            )
        } finally {
            conn.disconnect()
        }
    }
}
