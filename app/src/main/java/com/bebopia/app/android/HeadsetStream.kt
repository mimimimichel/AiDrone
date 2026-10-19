package com.bebopia.app.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Diffuse la vidéo du drone sur le réseau local, pour un casque (Meta Quest) ou tout autre écran :
 * il suffit d'ouvrir http://<adresse du téléphone>:8080 dans son navigateur.
 *
 * Vidéo en MJPEG (une suite d'images JPEG, lue nativement par tous les navigateurs),
 * environ 12 images par seconde, et une page avec les infos de vol et ce que dit l'IA.
 */
class HeadsetStream(
    /** Image actuelle, capturée sur le thread de l'interface (null si pas d'image). */
    private val grab: () -> Bitmap?,
    /** Infos à afficher sur la page, en JSON. */
    private val state: () -> String,
    private val port: Int = 8080,
) {
    companion object {
        private const val TAG = "HeadsetStream"
        private const val BOUNDARY = "bebopiaframe"

        /** Adresses IPv4 du téléphone sur ses réseaux locaux (Wi-Fi, partage de connexion). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                // Pas les interfaces 4G/5G (rmnet, ccmni…) : seulement Wi-Fi et partage de connexion
                .filter { nif -> listOf("rmnet", "ccmni", "pdp", "v4-", "dummy", "tun").none { nif.name.startsWith(it) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .mapNotNull { it.hostAddress }
                }
                .distinct()
        }.getOrDefault(emptyList())
    }

    @Volatile var running = false
        private set
    private var server: ServerSocket? = null
    private val viewers = AtomicInteger(0)
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestAt = 0L
    private val frameLock = Object()

    val viewerCount: Int get() = viewers.get()

    fun urls(): List<String> = localAddresses().map { "http://$it:$port" }

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(port)
            server = s
            running = true
            thread(name = "casque-serveur", isDaemon = true) { acceptLoop(s) }
            thread(name = "casque-images", isDaemon = true) { captureLoop() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "démarrage", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    // ---- images ------------------------------------------------------------------------------
    private fun captureOnMain(): Bitmap? {
        val latch = CountDownLatch(1)
        var b: Bitmap? = null
        Handler(Looper.getMainLooper()).post {
            b = runCatching { grab() }.getOrNull()
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return b
    }

    /** Capture des images seulement quand quelqu'un regarde. */
    private fun captureLoop() {
        while (running) {
            val t0 = System.currentTimeMillis()
            if (viewers.get() > 0) {
                val bmp = captureOnMain()
                if (bmp != null) {
                    try {
                        val bright = brighten(bmp)
                        val out = java.io.ByteArrayOutputStream()
                        bright.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        if (bright !== bmp) bright.recycle()
                        synchronized(frameLock) {
                            latest = out.toByteArray()
                            latestAt = System.currentTimeMillis()
                            frameLock.notifyAll()
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            val wait = 80 - (System.currentTimeMillis() - t0)
            Thread.sleep(if (viewers.get() > 0) maxOf(5, wait) else 300)
        }
    }

    // ---- serveur HTTP minimal ---------------------------------------------------------------
    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try { s.accept() } catch (e: Exception) { break }
            thread(name = "casque-client", isDaemon = true) {
                try {
                    handle(client)
                } catch (e: Exception) {
                    // client parti
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val request = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = request.split(' ').getOrNull(1) ?: "/"
        val out = c.getOutputStream()
        when {
            path.startsWith("/stream") -> stream(out)
            path.startsWith("/state") -> send(out, "application/json; charset=utf-8", state().toByteArray())
            path.startsWith("/frame") -> {
                val f = latest
                if (f == null) send(out, "text/plain", "pas d'image".toByteArray(), "404 Not Found")
                else send(out, "image/jpeg", f)
            }
            else -> send(out, "text/html; charset=utf-8", PAGE.toByteArray())
        }
    }

    private fun send(out: OutputStream, type: String, body: ByteArray, status: String = "200 OK") {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun stream(out: OutputStream) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.flush()
        viewers.incrementAndGet()
        try {
            var sentAt = 0L
            while (running) {
                val frame: ByteArray
                synchronized(frameLock) {
                    while (running && latestAt == sentAt) frameLock.wait(1000)
                    frame = latest ?: ByteArray(0)
                    sentAt = latestAt
                }
                if (!running) break
                if (frame.isEmpty()) continue
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            viewers.decrementAndGet()
        }
    }

    /** Page affichée dans le navigateur du casque : vidéo plein écran + infos. */
    private val PAGE = """
<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bebop IA – vue du drone</title>
<style>
  html,body{margin:0;height:100%;background:#000;color:#fff;font-family:system-ui,sans-serif;overflow:hidden}
  #v{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
  .bar{position:absolute;left:0;right:0;padding:10px 16px;background:linear-gradient(#000c,#0000);font-size:20px;display:flex;gap:18px;flex-wrap:wrap}
  #top{top:0}
  #say{position:absolute;left:5%;right:5%;bottom:24px;text-align:center;font-size:24px;text-shadow:0 2px 6px #000}
  #alert{position:absolute;top:45%;left:0;right:0;text-align:center;font-size:40px;font-weight:bold;color:#fff;display:none}
  #alert span{background:#d32f2f;padding:10px 24px;border-radius:12px}
  button{position:absolute;right:16px;bottom:16px;font-size:18px;padding:10px 16px;border-radius:10px;border:0;background:#4fc3f7}
</style></head><body>
<img id="v" src="/stream" alt="vidéo du drone">
<div class="bar" id="top">chargement…</div>
<div id="alert"><span>⚠ OBSTACLE</span></div>
<div id="say"></div>
<button onclick="document.documentElement.requestFullscreen&&document.documentElement.requestFullscreen()">Plein écran</button>
<script>
const v=document.getElementById('v');
v.onerror=()=>setTimeout(()=>{v.src='/stream?'+Date.now()},1000);
async function poll(){
  try{
    const s=await (await fetch('/state',{cache:'no-store'})).json();
    document.getElementById('top').textContent=s.top||'';
    document.getElementById('say').textContent=s.say||'';
    document.getElementById('alert').style.display=s.obstacle?'block':'none';
  }catch(e){document.getElementById('top').textContent='téléphone injoignable…'}
  setTimeout(poll,500);
}
poll();
</script></body></html>
""".trimIndent()
}
