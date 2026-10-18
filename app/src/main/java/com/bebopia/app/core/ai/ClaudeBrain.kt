package com.bebopia.app.core.ai

import com.bebopia.app.core.MiniJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Appel à l'API Claude (Messages) avec l'image et le contexte.
 * [open] permet de faire passer la requête par la 4G sur Android.
 */
class ClaudeBrain(
    private val apiKey: String,
    private val model: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : Brain {

    override fun decide(jpegBase64: String?, userText: String): Decision {
        if (apiKey.isBlank()) throw IllegalStateException("clé API Claude manquante : ajoute-la dans les réglages")

        val content = ArrayList<Any>()
        if (jpegBase64 != null) {
            content.add(mapOf(
                "type" to "image",
                "source" to mapOf("type" to "base64", "media_type" to "image/jpeg", "data" to jpegBase64),
            ))
        }
        content.add(mapOf("type" to "text", "text" to userText))
        val body = MiniJson.stringify(mapOf(
            "model" to model,
            "max_tokens" to 600,
            "system" to Prompt.SYSTEM,
            "messages" to listOf(mapOf("role" to "user", "content" to content)),
        ))

        val conn = open(URL("https://api.anthropic.com/v1/messages"))
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = ((MiniJson.parse(resp) as? Map<*, *>)?.get("error") as? Map<*, *>)?.get("message")
                throw IOException("API Claude, erreur $code : ${msg ?: resp.take(200)}")
            }
            val json = MiniJson.parse(resp) as? Map<*, *>
            val text = (json?.get("content") as? List<*>)
                ?.mapNotNull { block -> (block as? Map<*, *>)?.takeIf { it["type"] == "text" }?.get("text") as? String }
                ?.joinToString("") ?: ""
            return Prompt.parseDecision(text)
        } finally {
            conn.disconnect()
        }
    }
}
