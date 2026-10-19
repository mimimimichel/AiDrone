package com.bebopia.app.android

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tuiles de carte satellite (Esri World Imagery) pour la mini-carte du casque.
 * Téléchargées par le téléphone (4G), gardées en mémoire et sur le disque :
 * une zone déjà vue reste affichable même sans réseau.
 */
class MapTiles(context: Context, private val open: (URL) -> HttpURLConnection) {
    private val dir = File(context.cacheDir, "tuiles").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 300
    }

    fun get(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 1..20) return null
        val key = "$z-$x-$y"
        synchronized(memory) { memory[key] }?.let { return it }
        val file = File(dir, "$key.jpg")
        if (file.exists()) {
            return file.readBytes().also { synchronized(memory) { memory[key] = it } }
        }
        val url = URL("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x")
        val conn = open(url)
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "BebopIA (application de drone personnelle)")
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            synchronized(memory) { memory[key] = bytes }
            runCatching { file.writeBytes(bytes) }
            bytes
        } catch (e: Exception) {
            Log.w("MapTiles", "tuile $key : ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
