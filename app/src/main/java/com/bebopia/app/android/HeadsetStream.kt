package com.bebopia.app.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    /** Orientation de la tête (pitch, yaw en degrés), suivi de la tête par le drone, recentrage. */
    private val onHead: (pitch: Double, yaw: Double, follow: Boolean, recenter: Boolean) -> Unit = { _, _, _, _ -> },
    /** Pour l'adresse sécurisée (https), obligatoire pour la réalité virtuelle dans le navigateur du casque. */
    private val tls: javax.net.ssl.SSLServerSocketFactory? = null,
    private val port: Int = 8080,
    private val tlsPort: Int = 8443,
    /** Son du micro du casque (PCM 16 bits, 16 kHz). */
    private val onMic: (ByteArray) -> Unit = {},
    /** Nombre de casques reliés en audio (0 = la voix repasse par le téléphone). */
    private val onAudioClients: (Int) -> Unit = {},
    /** Tuile de carte (z, x, y) en JPEG, téléchargée par le téléphone (null si indisponible). */
    private val onTile: (Int, Int, Int) -> ByteArray? = { _, _, _ -> null },
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
    private var tlsServer: ServerSocket? = null
    @Volatile private var pollAt = 0L
    private val viewers = AtomicInteger(0)
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestAt = 0L
    private val frameLock = Object()

    val viewerCount: Int get() = viewers.get()

    /** Adresses à ouvrir dans le casque : https d'abord (réalité virtuelle et suivi de la tête). */
    fun urls(): List<String> = localAddresses().flatMap { ip ->
        listOfNotNull(if (tlsServer != null) "https://$ip:$tlsPort" else null, "http://$ip:$port")
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(port)
            server = s
            running = true
            thread(name = "casque-serveur", isDaemon = true) { acceptLoop(s) }
            tls?.let { f ->
                runCatching { f.createServerSocket(tlsPort) }
                    .onSuccess { ts ->
                        tlsServer = ts
                        thread(name = "casque-serveur-https", isDaemon = true) { acceptLoop(ts) }
                    }
                    .onFailure { Log.e(TAG, "https", it) }
            }
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
        runCatching { tlsServer?.close() }
        server = null
        tlsServer = null
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
            val watched = viewers.get() > 0 || System.currentTimeMillis() - pollAt < 2000
            if (watched) {
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
            Thread.sleep(if (watched) maxOf(5, wait) else 300)
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

    /** Lit une ligne d'en-tête HTTP octet par octet (sans tampon : le flux sert ensuite au WebSocket). */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = c.getInputStream()
        val request = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val path = request.split(' ').getOrNull(1) ?: "/"
        val out = c.getOutputStream()
        when {
            path.startsWith("/audio") && headers["upgrade"]?.lowercase() == "websocket" -> audio(c, input, out, headers["sec-websocket-key"] ?: return)
            path.startsWith("/stream") -> stream(out)
            path.startsWith("/state") -> send(out, "application/json; charset=utf-8", state().toByteArray())
            path.startsWith("/head") -> {
                val q = query(path)
                val p = q["p"]?.toDoubleOrNull()
                val y = q["y"]?.toDoubleOrNull()
                if (p != null && y != null) onHead(p, y, q["f"] == "1", q["c"] == "1")
                send(out, "application/json; charset=utf-8", state().toByteArray())
            }
            path.startsWith("/tile") -> {
                val q = query(path)
                val z = q["z"]?.toIntOrNull(); val x = q["x"]?.toIntOrNull(); val y = q["y"]?.toIntOrNull()
                val img = if (z != null && x != null && y != null) runCatching { onTile(z, x, y) }.getOrNull() else null
                if (img == null) send(out, "text/plain", "pas de tuile".toByteArray(), "404 Not Found")
                else send(out, "image/jpeg", img)
            }
            path.startsWith("/frame") -> {
                pollAt = System.currentTimeMillis()
                val f = latest
                if (f == null) send(out, "text/plain", "pas d'image".toByteArray(), "404 Not Found")
                else send(out, "image/jpeg", f)
            }
            else -> send(out, "text/html; charset=utf-8", PAGE.toByteArray())
        }
    }

    // ---- audio du casque (WebSocket) ---------------------------------------------------------
    private class AudioClient(val out: OutputStream)
    private val audioClients = java.util.concurrent.CopyOnWriteArrayList<AudioClient>()

    /** Envoie la voix de l'IA à tous les casques reliés (PCM 16 bits, 24 kHz). */
    fun sendAudio(pcm: ByteArray) {
        for (cl in audioClients) runCatching { wsSend(cl, 0x2, pcm) }.onFailure { audioClients.remove(cl) }
    }

    /** Message de commande au casque (ex. « flush » : jeter le son en attente). */
    fun sendText(text: String) {
        for (cl in audioClients) runCatching { wsSend(cl, 0x1, text.toByteArray()) }.onFailure { audioClients.remove(cl) }
    }

    private fun wsSend(cl: AudioClient, opcode: Int, payload: ByteArray) {
        val n = payload.size
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            n < 126 -> header.write(n)
            n < 65536 -> { header.write(126); header.write(n shr 8); header.write(n and 0xFF) }
            else -> { header.write(127); for (i in 7 downTo 0) header.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        synchronized(cl) {
            cl.out.write(header.toByteArray())
            cl.out.write(payload)
            cl.out.flush()
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }

    private fun audio(c: Socket, input: java.io.InputStream, out: OutputStream, key: String) {
        val accept = android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            android.util.Base64.NO_WRAP,
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        c.soTimeout = 0
        val cl = AudioClient(out)
        audioClients.add(cl)
        onAudioClients(audioClients.size)
        try {
            val head = ByteArray(2)
            while (running) {
                readFully(input, head)
                val opcode = head[0].toInt() and 0x0F
                val masked = (head[1].toInt() and 0x80) != 0
                var len = (head[1].toInt() and 0x7F).toLong()
                if (len == 126L) { val e = ByteArray(2); readFully(input, e); len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong() }
                else if (len == 127L) { val e = ByteArray(8); readFully(input, e); len = e.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } }
                if (len > 1_000_000) break
                val mask = ByteArray(4)
                if (masked) readFully(input, mask)
                val payload = ByteArray(len.toInt())
                readFully(input, payload)
                if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x2 -> onMic(payload)
                    0x8 -> break
                    0x9 -> wsSend(cl, 0xA, payload) // ping -> pong
                }
            }
        } finally {
            audioClients.remove(cl)
            onAudioClients(audioClients.size)
        }
    }

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()

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

    /**
     * Page du casque : vidéo plein écran, et bouton « Entrer en VR » (adresse https) où la nacelle
     * suit la tête. En VR : la nacelle suit la tête (haut/bas) et le drone son cap (gauche/droite), en 1 pour 1 ;
     * gâchette = recentrer, grip = coupe/remet la rotation du drone.
     */
    private val PAGE = """
<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bebop IA – cockpit</title>
<style>
  html,body{margin:0;height:100%;background:#050a08;color:#fff;font-family:system-ui,sans-serif;overflow:hidden}
  #cockpit{display:block;margin:0 auto;max-width:100%;max-height:calc(100% - 64px);width:100%;height:auto;aspect-ratio:2/1}
  #btns{position:absolute;right:12px;bottom:12px;display:flex;gap:10px;align-items:center}
  button{font-size:18px;padding:10px 16px;border-radius:10px;border:0;background:#4fc3f7;color:#000}
  #vr{background:#66bb6a;font-weight:bold}
  #note{font-size:14px;max-width:480px;text-align:right;color:#cfd8dc}
  #gl{display:none}
</style></head><body>
<canvas id="cockpit" width="2048" height="1024"></canvas>
<div id="btns"><span id="note"></span>
  <button onclick="document.documentElement.requestFullscreen&&document.documentElement.requestFullscreen()">Plein écran</button>
  <button id="talk">🎙 Parler à l'IA ici</button>
  <button id="vr" style="display:none">🥽 Entrer en VR</button></div>
<canvas id="gl"></canvas>
<script>
// ======================= Cockpit (dessiné sur une image 2048 x 1024) =======================
const C=document.getElementById('cockpit'), g=C.getContext('2d');
const W=2048, H=1024, VX=384, VY=150, VW=1280, VH=720;
const HUD='#7CFC9A', WARN='#FFC107', RED='#FF5252', CYAN='#4FC3F7', GREY='#9fb3a8', DIM='rgba(6,18,12,.80)';
let state={}, frame=null, follow=true, inVR=false, audioOn=false, dirty=true;

function rr(x,y,w,h,r){g.beginPath();g.moveTo(x+r,y);g.arcTo(x+w,y,x+w,y+h,r);g.arcTo(x+w,y+h,x,y+h,r);g.arcTo(x,y+h,x,y,r);g.arcTo(x,y,x+w,y,r);g.closePath();}
function panel(x,y,w,h,title){g.fillStyle=DIM;rr(x,y,w,h,14);g.fill();g.strokeStyle='rgba(124,252,154,.35)';g.lineWidth=2;rr(x,y,w,h,14);g.stroke();
  if(title){g.fillStyle=HUD;g.font='bold 22px sans-serif';g.textAlign='left';g.fillText(title,x+16,y+32);}}
function t(s,x,y,size,color,align,bold){g.font=(bold?'bold ':'')+size+'px sans-serif';g.fillStyle=color||'#fff';g.textAlign=align||'left';g.fillText(s,x,y);}
function wrap(s,x,y,maxW,lh,maxLines,size,color){g.font=size+'px sans-serif';g.fillStyle=color;g.textAlign='left';
  const words=String(s).split(' ');let line='',n=0;
  for(const w of words){const test=line?line+' '+w:w;if(g.measureText(test).width>maxW&&line){g.fillText(line,x,y);y+=lh;line=w;if(++n>=maxLines-1){line=line+' …';break;}}else line=test;}
  if(line){g.fillText(line,x,y);y+=lh;}return y;}
function num(v,d){return (typeof v==='number'&&isFinite(v))?v:d;}
function f1(v){return num(v,0).toFixed(1).replace('.',',');}
function norm(a){return ((a%360)+540)%360-180;}
function card(h){const n=['N','NE','E','SE','S','SO','O','NO'];return n[Math.round((((h%360)+360)%360)/45)%8];}
function mmss(s){s=Math.max(0,Math.round(num(s,0)));return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');}

function draw(){
  const s=state, tel=s.tel||{};
  g.fillStyle='#050a08';g.fillRect(0,0,W,H);
  // ---- bandeau haut ----
  panel(8,8,W-16,128);
  t('BEBOP IA',28,52,30,HUD,'left',true);
  t({BEBOP:'Bebop 2',MAVIC:'Mavic Mini',SIM:'Simulation',CONNECTING:'Connexion…',NONE:'Aucun drone'}[s.mode]||'—',28,92,24,'#fff');
  t((s.droneState||'')+(tel.flightMode?'  ·  '+tel.flightMode:''),28,122,20,GREY);
  const now=new Date();
  t(now.toLocaleTimeString('fr-FR'),W/2,62,40,'#fff','center',true);
  t('vol '+mmss(tel.flightTime),W/2,102,24,GREY,'center');
  const warns=[];
  if(s.mode&&s.mode!=='NONE'&&s.linkUp===false)warns.push([RED,'LIAISON PERDUE']);
  if(s.obstacle)warns.push([RED,'OBSTACLE']);
  if(num(s.battery,100)<30)warns.push([RED,'BATTERIE FAIBLE']);
  if(s.video===false&&s.mode!=='NONE')warns.push([WARN,'PAS DE VIDÉO']);
  if(tel.imuPreheating)warns.push([WARN,'PRÉCHAUFFAGE']);
  if(s.paused)warns.push([WARN,'IA EN PAUSE']);
  if(s.confirm===false)warns.push([WARN,'MODE CONFIANCE']);
  if(s.manual)warns.push([CYAN,'MANUEL']);
  let wx=W-28;
  for(const [c,w] of warns.slice(0,4).reverse()){g.font='bold 22px sans-serif';const ww=g.measureText(w).width+28;wx-=ww;
    g.fillStyle=c;rr(wx,40,ww,44,8);g.fill();t(w,wx+ww/2,70,22,'#000','center',true);wx-=10;}
  if(!warns.length)t('SYSTÈMES OK',W-28,72,24,HUD,'right',true);

  // ---- vidéo ----
  g.save();rr(VX,VY,VW,VH,10);g.clip();
  g.fillStyle='#000';g.fillRect(VX,VY,VW,VH);
  if(frame)g.drawImage(frame,VX,VY,VW,VH);else t('PAS DE VIDÉO',VX+VW/2,VY+VH/2,40,GREY,'center',true);
  drawHUD(s,tel);
  g.restore();
  g.strokeStyle='rgba(124,252,154,.5)';g.lineWidth=3;rr(VX,VY,VW,VH,10);g.stroke();

  drawLeft(s,tel);drawRight(s);
  // ---- bandeau bas ----
  panel(8,H-132,W-16,124);
  t('🥽 Tête → nacelle',28,H-86,24,HUD,'left',true);
  t('↻ Rotation du drone : '+(follow?'MARCHE':'ARRÊT'),28,H-46,24,follow?HUD:GREY,'left',true);
  t('Grip : rotation marche/arrêt   ·   Gâchette : recentrer',W/2,H-86,22,'#fff','center');
  t('Dis « stop » ou « atterris » à tout moment',W/2,H-46,22,WARN,'center');
  t(audioOn?'🎙 Audio dans le casque':'🎙 Audio sur le téléphone',W-28,H-86,22,audioOn?HUD:GREY,'right',true);
  t((s.voice||'').replace(/^[^ ]+ /,'')||'Conversation fermée',W-28,H-46,22,GREY,'right');
}

function drawHUD(s,tel){
  const cx=VX+VW/2, cy=VY+VH/2, ppd=VH/40;
  // Horizon artificiel (assiette du drone)
  const pitch=num(tel.pitch,0), roll=num(tel.roll,0);
  g.save();g.translate(cx,cy);g.rotate(-roll*Math.PI/180);
  g.strokeStyle=HUD;g.fillStyle=HUD;g.lineWidth=3;g.font='18px sans-serif';
  g.beginPath();g.moveTo(-560,pitch*ppd);g.lineTo(-120,pitch*ppd);g.moveTo(120,pitch*ppd);g.lineTo(560,pitch*ppd);g.stroke();
  for(let a=-30;a<=30;a+=5){if(a===0)continue;const y=(pitch-a)*ppd;if(Math.abs(y)>VH/2-40)continue;
    g.setLineDash(a<0?[10,8]:[]);g.lineWidth=2;g.beginPath();g.moveTo(-90,y);g.lineTo(-40,y);g.moveTo(40,y);g.lineTo(90,y);g.stroke();
    g.setLineDash([]);g.textAlign='right';g.fillText(String(a),-98,y+6);g.textAlign='left';g.fillText(String(a),98,y+6);}
  g.restore();
  // Réticule fixe
  g.strokeStyle=WARN;g.lineWidth=4;g.beginPath();g.moveTo(cx-70,cy);g.lineTo(cx-25,cy);g.lineTo(cx-12,cy+14);g.lineTo(cx,cy);g.lineTo(cx+12,cy+14);g.lineTo(cx+25,cy);g.lineTo(cx+70,cy);g.stroke();
  // Indicateur de roulis
  g.save();g.translate(cx,cy);g.strokeStyle=HUD;g.lineWidth=2;
  g.beginPath();g.arc(0,0,300,-Math.PI/2-0.6,-Math.PI/2+0.6);g.stroke();
  for(const a of [-30,-20,-10,0,10,20,30]){g.save();g.rotate(a*Math.PI/180);g.beginPath();g.moveTo(0,-300);g.lineTo(0,a===0?-325:-314);g.stroke();g.restore();}
  g.rotate(-roll*Math.PI/180);g.fillStyle=HUD;g.beginPath();g.moveTo(0,-296);g.lineTo(-10,-278);g.lineTo(10,-278);g.closePath();g.fill();g.restore();

  // Cap (boussole) en haut, avec le point de départ
  const hd=num(tel.heading,0), tw=760, tx=cx-tw/2, ty=VY+14, ppx=tw/90;
  g.fillStyle='rgba(0,0,0,.45)';g.fillRect(tx,ty,tw,58);
  g.strokeStyle=HUD;g.fillStyle=HUD;g.lineWidth=2;
  for(let a=Math.ceil((hd-45)/5)*5;a<=hd+45;a+=5){const x=cx+(a-hd)*ppx;g.beginPath();g.moveTo(x,ty+58);g.lineTo(x,ty+(a%15===0?36:46));g.stroke();
    if(a%15===0){const n=((a%360)+360)%360;t(n%45===0?card(n):String(n),x,ty+30,20,HUD,'center',n%45===0);}}
  if(typeof tel.homeBearing==='number'){const rel=norm(tel.homeBearing-hd);const x=cx+Math.max(-45,Math.min(45,rel))*ppx;
    g.fillStyle=CYAN;g.beginPath();g.moveTo(x,ty+58);g.lineTo(x-12,ty+78);g.lineTo(x+12,ty+78);g.closePath();g.fill();t('H',x,ty+100,20,CYAN,'center',true);}
  g.fillStyle='#000';rr(cx-52,ty+60,104,40,6);g.fill();g.strokeStyle=HUD;rr(cx-52,ty+60,104,40,6);g.stroke();
  t(String(Math.round(((hd%360)+360)%360)).padStart(3,'0')+'°',cx,ty+90,26,HUD,'center',true);

  // Vitesse (gauche) et altitude (droite)
  tape(VX+24,cy,num(tel.hSpeed,0),'m/s',1,40,'VIT',false,null);
  tape(VX+VW-24,cy,num(s.alt,0),'m',1,40,'ALT',true,num(s.ceiling,null));
  const vs=num(tel.vSpeed,0);
  t((vs>=0?'▲ ':'▼ ')+f1(Math.abs(vs))+' m/s',VX+VW-24,cy+230,22,Math.abs(vs)>0.2?WARN:GREY,'right',true);

  // Nacelle
  const gp=num(tel.gimbal,0), gx=VX+VW-230, gy=VY+VH-250;
  g.strokeStyle=HUD;g.lineWidth=3;g.beginPath();g.arc(gx,gy,70,0,Math.PI/2);g.stroke();
  const ga=Math.min(90,Math.max(0,-gp))*Math.PI/180;
  g.strokeStyle=WARN;g.lineWidth=5;g.beginPath();g.moveTo(gx,gy);g.lineTo(gx+70*Math.cos(ga),gy+70*Math.sin(ga));g.stroke();
  t('NACELLE '+Math.round(gp)+'°',gx+35,gy-14,20,HUD,'center',true);

  // Détecteur d'obstacles
  if(s.guardWatching){const tc=num(s.ttc,null);t('🛡 '+(tc===null||tc>9.9?'rien n\'approche':'contact '+f1(tc)+' s'),cx,cy+70,24,tc!==null&&tc<3?WARN:HUD,'center',true);}
  if(s.obstacle){g.fillStyle='rgba(211,47,47,.9)';rr(cx-260,cy-130,520,90,14);g.fill();t('⚠ OBSTACLE : ARRÊT',cx,cy-72,44,'#fff','center',true);}
  // Proposition à valider
  let by=VY+VH-24;
  if(s.pending){g.fillStyle='rgba(255,193,7,.92)';rr(cx-460,by-62,920,62,12);g.fill();t('L\'IA PROPOSE : '+s.pending+'  —  dis OUI ou NON',cx,by-20,26,'#000','center',true);by-=76;}
  // Sous-titres de l'IA
  if(s.say){g.font='28px sans-serif';const lines=[];let line='';for(const w of s.say.split(' ')){const tt=line?line+' '+w:w;if(g.measureText(tt).width>1000&&line){lines.push(line);line=w;}else line=tt;}if(line)lines.push(line);
    const show=lines.slice(-2);for(let i=show.length-1;i>=0;i--){g.lineWidth=7;g.strokeStyle='#000';g.textAlign='center';g.strokeText(show[i],cx,by-8);g.fillStyle='#fff';g.fillText(show[i],cx,by-8);by-=38;}}
}

function tape(x,cy,val,unit,step,ppu,label,right,limit){
  const h=440, top=cy-h/2, w=110, x0=right?x-w:x;
  g.fillStyle='rgba(0,0,0,.45)';g.fillRect(x0,top,w,h);
  g.strokeStyle=HUD;g.lineWidth=2;g.fillStyle=HUD;
  for(let v=Math.floor((val-h/2/ppu)/step)*step;v<=val+h/2/ppu;v+=step){const y=cy-(v-val)*ppu;if(y<top+6||y>top+h-6)continue;
    const xe=right?x0:x0+w;g.beginPath();g.moveTo(xe,y);g.lineTo(right?xe+16:xe-16,y);g.stroke();
    t(String(Math.round(v*10)/10).replace('.',','),right?x0+w-8:x0+8,y+7,18,HUD,right?'right':'left');}
  if(limit!==null&&limit!==undefined){const y=cy-(limit-val)*ppu;if(y>top&&y<top+h){g.strokeStyle=RED;g.lineWidth=4;g.beginPath();g.moveTo(x0,y);g.lineTo(x0+w,y);g.stroke();t('PLAFOND',x0+w/2,y-8,16,RED,'center',true);}}
  g.fillStyle='#000';rr(x0-6,cy-24,w+12,48,6);g.fill();g.strokeStyle=HUD;g.lineWidth=2;rr(x0-6,cy-24,w+12,48,6);g.stroke();
  t(f1(val),x0+w/2,cy+10,28,HUD,'center',true);
  t(label+' ('+unit+')',x0+w/2,top-10,18,GREY,'center',true);
}

function drawLeft(s,tel){
  const x=8,w=VX-20;
  // Énergie
  panel(x,VY,w,190,'ÉNERGIE');
  const b=num(s.battery,null);const bc=b===null?GREY:b<30?RED:b<50?WARN:HUD;
  g.strokeStyle=bc;g.lineWidth=3;rr(x+18,VY+52,w-90,56,8);g.stroke();
  if(b!==null){g.fillStyle=bc;rr(x+24,VY+58,(w-102)*b/100,44,5);g.fill();}
  g.fillStyle=bc;g.fillRect(x+w-68,VY+68,10,24);
  t(b===null?'—':b+' %',x+w-48,VY+95,30,bc,'left',true);
  t('Temps de vol : '+mmss(tel.flightTime),x+18,VY+150,24,'#fff');
  t(tel.lowBattery?'Seuil d\'alerte atteint':'',x+18,VY+178,20,RED);
  // Navigation
  const ny=VY+202;panel(x,ny,w,270,'NAVIGATION');
  drawMap(g,x+90,ny+140,72,tel);
  const hd=num(tel.homeDist,num(s.dist,0));
  t('Départ',x+172,ny+90,20,GREY);
  t(f1(hd)+' m',x+172,ny+130,34,CYAN,'left',true);
  t(tel.homeSource==='GPS'?'par GPS':'estimé',x+172,ny+160,18,GREY);
  const r=num(s.radius,15);g.fillStyle='rgba(255,255,255,.15)';g.fillRect(x+18,ny+222,w-36,14);
  g.fillStyle=hd/r>0.8?RED:CYAN;g.fillRect(x+18,ny+222,(w-36)*Math.min(1,hd/r),14);
  t('Rayon '+Math.round(r)+' m  ·  Plafond '+Math.round(num(s.ceiling,3))+' m',x+18,ny+258,18,GREY);
  // Capteurs
  const cy2=ny+282;panel(x,cy2,w,VY+VH-cy2,'CAPTEURS');
  let ly=cy2+66;
  function row(k,v,c){t(k,x+18,ly,20,GREY);t(v,x+w-18,ly,20,c||'#fff','right',true);ly+=34;}
  row('GPS',(tel.gps||'—')+(typeof tel.sats==='number'?' · '+tel.sats+' sat':''),num(tel.sats,0)>=8?HUD:WARN);
  row('Mode de vol',tel.flightMode||'—');
  row('Capteurs dessous',tel.vps===undefined?'—':tel.vps?'actifs':'inactifs',tel.vps?HUD:WARN);
  row('Détecteur obstacles',s.guardOn?(s.guardWatching?'surveille':'prêt'):'coupé',s.guardOn?HUD:WARN);
  row('Vidéo',s.video?'reçue':'absente',s.video?HUD:RED);
}

function drawRight(s){
  const x=VX+VW+12,w=W-x-8;
  // IA
  panel(x,VY,w,190,'INTELLIGENCE ARTIFICIELLE');
  const st=s.paused?['PAUSE',WARN]:s.thinking?['RÉFLÉCHIT',CYAN]:['ACTIVE',HUD];
  t(st[0],x+18,VY+86,40,st[1],'left',true);
  t((s.voice||'Conversation fermée'),x+18,VY+124,20,'#fff');
  t('Actions '+(s.steps||0)+' / '+(s.maxSteps||40)+'  ·  pas max '+f1(s.maxStep)+' m',x+18,VY+156,19,GREY);
  t(s.confirm?'✋ Mode prudent : je valide':'🤝 Mode confiance : l\'IA agit seule',x+18,VY+180,18,s.confirm?HUD:WARN,'left',!s.confirm);
  // Plan
  const py=VY+202;panel(x,py,w,290,'PLAN');
  let y=py+64;
  if(!s.plan){t('Aucun plan.',x+18,y,20,GREY);t('Donne un objectif à l\'IA.',x+18,y+30,20,GREY);}
  else{const p=s.plan;
    y=wrap((p.finished?(p.success?'✅ ':'❌ '):p.running?'▶ ':'📋 ')+p.goal,x+18,y,w-36,26,2,21,'#fff');
    const icon={TODO:'⬜',DOING:'▶',DONE:'✅',FAILED:'❌',SKIPPED:'⏭'};
    for(const [i,st2] of (p.steps||[]).entries()){if(y>py+280)break;
      y=wrap(icon[st2.s]+' '+(i+1)+'. '+st2.t,x+18,y,w-36,24,2,18,st2.s==='DOING'?CYAN:st2.s==='DONE'?HUD:'#ddd');}
    if(!p.approved&&!p.finished)t('Dis OUI pour lancer',x+18,py+278,18,WARN,'left',true);}
  // Conversation
  const cy3=py+302;panel(x,cy3,w,VY+VH-cy3,'CONVERSATION');
  y=cy3+62;
  const lines=(s.chat||[]).slice(-5);
  for(const l of lines){if(y>VY+VH-14)break;
    const who=l.r==='P'?['Toi : ',CYAN]:l.r==='D'?['IA : ',HUD]:['',GREY];
    y=wrap(who[0]+l.t,x+18,y,w-36,23,3,18,who[1])+4;}
}

// ======================= Mini-carte satellite (position GPS) =======================
const tiles=new Map();
function tile(z,x,y){const k=z+'/'+x+'/'+y;let im=tiles.get(k);
  if(!im){im=new Image();im.onload=()=>{dirty=true};im.src='/tile?z='+z+'&x='+x+'&y='+y;tiles.set(k,im);
    if(tiles.size>400)tiles.delete(tiles.keys().next().value);}
  return (im.complete&&im.naturalWidth)?im:null;}
function proj(lat,lon,z){const n=Math.pow(2,z)*256,r=lat*Math.PI/180;
  return [(lon+180)/360*n,(1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n];}
// Carte ronde centrée sur le drone, orientée dans sa direction (le haut = devant le drone).
function drawMap(c,cx,cy,R,tel){
  c.save();c.beginPath();c.arc(cx,cy,R,0,Math.PI*2);c.closePath();
  c.fillStyle='rgba(10,20,15,.85)';c.fill();c.clip();
  const lat=tel.lat,lon=tel.lon;
  if(typeof lat!=='number'||typeof lon!=='number'){
    c.font='bold 22px sans-serif';c.textAlign='center';c.fillStyle=GREY;c.fillText('PAS DE GPS',cx,cy+8);c.restore();
    c.strokeStyle=CYAN;c.lineWidth=3;c.beginPath();c.arc(cx,cy,R,0,Math.PI*2);c.stroke();return;}
  // Zoom : le point de départ et le pilote restent visibles
  let far=20;
  for(const [a,b] of [[tel.homeLat,tel.homeLon],[tel.pilotLat,tel.pilotLon]]){
    if(typeof a==='number'&&typeof b==='number'){const dy=(a-lat)*111320,dx=(b-lon)*111320*Math.cos(lat*Math.PI/180);far=Math.max(far,Math.hypot(dx,dy));}}
  let z=19;const mpp=zz=>156543.03*Math.cos(lat*Math.PI/180)/Math.pow(2,zz);
  while(z>12&&far/mpp(z)>R*0.8)z--;
  const [px,py]=proj(lat,lon,z), hd=(typeof tel.heading==='number'?tel.heading:0)*Math.PI/180;
  c.translate(cx,cy);c.rotate(-hd);
  const span=R*1.5;
  for(let tx=Math.floor((px-span)/256);tx<=Math.floor((px+span)/256);tx++)
    for(let ty=Math.floor((py-span)/256);ty<=Math.floor((py+span)/256);ty++){
      const im=tile(z,tx,ty);if(im)c.drawImage(im,tx*256-px,ty*256-py,256,256);}
  function mark(a,b,color,label){if(typeof a!=='number'||typeof b!=='number')return;const [x,y]=proj(a,b,z);
    c.save();c.translate(x-px,y-py);c.rotate(hd);c.fillStyle=color;c.strokeStyle='#000';c.lineWidth=3;
    c.beginPath();c.arc(0,0,label?13:8,0,Math.PI*2);c.fill();c.stroke();
    if(label){c.fillStyle='#000';c.font='bold 16px sans-serif';c.textAlign='center';c.fillText(label,0,6);}c.restore();}
  mark(tel.homeLat,tel.homeLon,CYAN,'H');
  mark(tel.pilotLat,tel.pilotLon,'#448AFF','');
  // Nord sur le bord
  c.save();c.rotate(0);c.fillStyle='#fff';c.font='bold 20px sans-serif';c.textAlign='center';c.strokeStyle='#000';c.lineWidth=4;
  c.strokeText('N',0,-R+24);c.fillText('N',0,-R+24);c.restore();
  c.restore();
  // Drone au centre, pointe vers le haut (= sa direction)
  c.fillStyle=WARN;c.strokeStyle='#000';c.lineWidth=3;c.beginPath();c.moveTo(cx,cy-18);c.lineTo(cx-12,cy+12);c.lineTo(cx,cy+5);c.lineTo(cx+12,cy+12);c.closePath();c.fill();c.stroke();
  c.strokeStyle=CYAN;c.lineWidth=3;c.beginPath();c.arc(cx,cy,R,0,Math.PI*2);c.stroke();
  // Échelle et crédit des images
  c.font='bold 16px sans-serif';c.textAlign='center';c.fillStyle='#fff';c.strokeStyle='#000';c.lineWidth=4;
  const scale='rayon '+Math.round(mpp(z)*R)+' m';c.strokeText(scale,cx,cy+R-14);c.fillText(scale,cx,cy+R-14);
  c.font='11px sans-serif';c.fillStyle=GREY;c.fillText('Imagerie © Esri',cx,cy+R+16);
}

// ======================= VR : la caméra en grand, infos discrètes dans les coins (style lunettes FPV) =======================
const V=document.createElement('canvas');V.width=1920;V.height=1080;
function drawOSD(){
  const o=V.getContext('2d'), s=state, tel=s.tel||{}, w=1920, h=1080, cx=w/2, cy=h/2;
  o.fillStyle='#000';o.fillRect(0,0,w,h);
  if(frame)o.drawImage(frame,0,0,w,h);else{o.fillStyle=GREY;o.font='bold 48px sans-serif';o.textAlign='center';o.fillText('PAS DE VIDÉO',cx,cy);}
  function tx(str,x,y,size,color,align,bold){o.font=(bold?'bold ':'')+size+'px sans-serif';o.textAlign=align||'left';o.lineWidth=6;o.strokeStyle='rgba(0,0,0,.85)';o.strokeText(str,x,y);o.fillStyle=color||'#fff';o.fillText(str,x,y);}
  // Haut gauche : batterie, temps de vol
  const b=num(s.battery,null), bc=b===null?GREY:b<30?RED:b<50?WARN:HUD;
  o.strokeStyle=bc;o.lineWidth=4;o.strokeRect(60,60,120,44);o.fillStyle=bc;if(b!==null)o.fillRect(66,66,108*b/100,32);o.fillRect(180,72,8,20);
  tx(b===null?'—':b+' %',200,96,40,bc,'left',true);
  tx('⏱ '+mmss(tel.flightTime),60,152,32,'#fff');
  // Haut droite : mode, GPS, état de l'IA
  tx(({BEBOP:'Bebop',MAVIC:'Mavic Mini',SIM:'Simulation'}[s.mode]||'')+(tel.flightMode?' · '+tel.flightMode:''),w-60,96,32,'#fff','right',true);
  tx((typeof tel.sats==='number'?'🛰 '+tel.sats+' sat':'')+(s.guardOn?'   🛡':''),w-60,140,28,num(tel.sats,0)>=8?HUD:WARN,'right');
  const ia=s.manual?['🕹 MANUEL',CYAN]:s.paused?['IA EN PAUSE',WARN]:[(s.confirm===false?'🤝 IA SEULE':'✋ IA (je valide)'),HUD];
  tx(ia[0],w-60,186,30,ia[1],'right',true);
  // Cap
  const hd=num(tel.heading,0), tw=640, ppx=tw/90, ty=70;
  o.strokeStyle='rgba(124,252,154,.9)';o.lineWidth=3;
  for(let a=Math.ceil((hd-45)/5)*5;a<=hd+45;a+=5){const x=cx+(a-hd)*ppx;o.beginPath();o.moveTo(x,ty);o.lineTo(x,ty+(a%15===0?24:12));o.stroke();
    if(a%15===0){const n=((a%360)+360)%360;tx(n%45===0?card(n):String(n),x,ty+54,24,HUD,'center',n%45===0);}}
  if(typeof tel.homeBearing==='number'){const rel=norm(tel.homeBearing-hd);const x=cx+Math.max(-45,Math.min(45,rel))*ppx;tx('▼H',x,ty-6,26,CYAN,'center',true);}
  tx(String(Math.round(((hd%360)+360)%360)).padStart(3,'0')+'°',cx,ty+96,34,HUD,'center',true);
  // Horizon (fin) et réticule
  const pitch=num(tel.pitch,0), roll=num(tel.roll,0), ppd=h/45;
  o.save();o.translate(cx,cy);o.rotate(-roll*Math.PI/180);o.strokeStyle='rgba(124,252,154,.75)';o.lineWidth=3;
  o.beginPath();o.moveTo(-420,pitch*ppd);o.lineTo(-140,pitch*ppd);o.moveTo(140,pitch*ppd);o.lineTo(420,pitch*ppd);o.stroke();o.restore();
  o.strokeStyle=WARN;o.lineWidth=4;o.beginPath();o.moveTo(cx-50,cy);o.lineTo(cx-18,cy);o.lineTo(cx-9,cy+10);o.lineTo(cx,cy);o.lineTo(cx+9,cy+10);o.lineTo(cx+18,cy);o.lineTo(cx+50,cy);o.stroke();
  // Gauche : vitesse ; droite : altitude, vitesse verticale, plafond
  tx(f1(tel.hSpeed)+' m/s',120,cy+12,40,HUD,'left',true);tx('VIT',120,cy-36,22,GREY,'left',true);
  tx(f1(s.alt)+' m',w-120,cy+12,44,num(s.alt,0)>num(s.ceiling,99)-0.3?RED:HUD,'right',true);tx('ALT  (max '+Math.round(num(s.ceiling,3))+')',w-120,cy-36,22,GREY,'right',true);
  const vs=num(tel.vSpeed,0);if(Math.abs(vs)>0.1)tx((vs>0?'▲ ':'▼ ')+f1(Math.abs(vs)),w-120,cy+60,28,WARN,'right',true);
  // Bas gauche : mini-carte et distance du départ
  drawMap(o,200,h-230,150,tel);
  tx('H '+f1(num(tel.homeDist,num(s.dist,0)))+' m',50,h-400,36,CYAN,'left',true);
  // Bas droite : nacelle, détecteur
  tx('🎥 '+Math.round(num(tel.gimbal,0))+'°',w-60,h-170,32,HUD,'right',true);
  if(s.guardWatching){const tc=num(s.ttc,null);tx('🛡 '+(tc===null||tc>9.9?'libre':f1(tc)+' s'),w-60,h-124,28,tc!==null&&tc<3?WARN:HUD,'right',true);}
  tx(follow?'↻ suit la tête':'↻ rotation coupée',w-60,h-82,24,follow?GREY:WARN,'right');
  // Étape du plan en cours
  if(s.plan&&s.plan.steps){const cur=s.plan.steps.findIndex(x=>x.s==='DOING');const st=cur>=0?s.plan.steps[cur]:null;
    if(st)tx('▶ '+(cur+1)+'/'+s.plan.steps.length+' '+st.t.slice(0,60),cx,h-210,28,CYAN,'center',true);}
  // Centre : alertes
  if(s.obstacle){o.fillStyle='rgba(211,47,47,.9)';o.fillRect(cx-300,cy-170,600,90);tx('⚠ OBSTACLE : ARRÊT',cx,cy-110,46,'#fff','center',true);}
  if(s.manual)tx('🕹 TU AS LA MAIN',cx,cy+150,40,CYAN,'center',true);
  if(num(s.battery,100)<30)tx('BATTERIE FAIBLE',cx,cy-210,40,RED,'center',true);
  if(s.mode&&s.mode!=='NONE'&&s.linkUp===false)tx('LIAISON PERDUE',cx,cy-260,44,RED,'center',true);
  // Bas centre : proposition, sous-titres
  let by=h-60;
  if(s.pending){o.fillStyle='rgba(255,193,7,.92)';o.fillRect(cx-480,by-56,960,60);o.font='bold 28px sans-serif';o.textAlign='center';o.fillStyle='#000';o.fillText('L\'IA PROPOSE : '+s.pending+' — OUI / NON',cx,by-16);by-=74;}
  if(s.say){o.font='32px sans-serif';const lines=[];let line='';for(const wd of s.say.split(' ')){const tt=line?line+' '+wd:wd;if(o.measureText(tt).width>1100&&line){lines.push(line);line=wd;}else line=tt;}if(line)lines.push(line);
    for(const l of lines.slice(-2).reverse()){tx(l,cx,by,32,'#fff','center');by-=42;}}
}

// ======================= Images et état =======================
async function frameLoop(){
  while(true){
    try{const r=await fetch('/frame?'+Date.now(),{cache:'no-store'});
      if(r.ok){const img=await createImageBitmap(await r.blob());if(frame&&frame.close)frame.close();frame=img;dirty=true;}}catch(e){}
    await new Promise(r=>setTimeout(r,70));
  }
}
async function statePoll(){
  while(true){
    if(!inVR){try{state=await (await fetch('/state',{cache:'no-store'})).json();dirty=true;}catch(e){state={};dirty=true;}}
    await new Promise(r=>setTimeout(r,200));
  }
}
function paint(){if(dirty){draw();dirty=false;}requestAnimationFrame(paint);}
setInterval(()=>{dirty=true;},1000); // horloge
frameLoop();statePoll();paint();

// ======================= Réalité virtuelle =======================
const note=document.getElementById('note'), vrBtn=document.getElementById('vr');
if(!window.isSecureContext){
  note.textContent='Pour la VR, le suivi de la tête et l\'audio : ouvre l\'adresse https://…:8443 (accepte l\'avertissement de sécurité).';
}else if(navigator.xr){
  navigator.xr.isSessionSupported('immersive-vr').then(ok=>{if(ok)vrBtn.style.display='inline-block';else note.textContent='VR non disponible dans ce navigateur.'});
}
vrBtn.onclick=()=>{startAudio();startVR();};
document.getElementById('talk').onclick=startAudio;
let recenter=true, lastHead=0;

async function startVR(){
  const canvas=document.getElementById('gl');
  const gl=canvas.getContext('webgl',{xrCompatible:true});
  const session=await navigator.xr.requestSession('immersive-vr',{optionalFeatures:['local']});
  inVR=true;
  await gl.makeXRCompatible();
  session.updateRenderState({baseLayer:new XRWebGLLayer(session,gl)});
  const ref=await session.requestReferenceSpace('local');
  const vs='attribute vec3 p;attribute vec2 t;uniform mat4 P;varying vec2 u;void main(){u=t;gl_Position=P*vec4(p,1.0);}';
  const fs='precision mediump float;varying vec2 u;uniform sampler2D s;void main(){gl_FragColor=texture2D(s,u);}';
  function sh(type,src){const s=gl.createShader(type);gl.shaderSource(s,src);gl.compileShader(s);return s;}
  const pr=gl.createProgram();gl.attachShader(pr,sh(gl.VERTEX_SHADER,vs));gl.attachShader(pr,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(pr);
  // Vidéo en grand devant les yeux (16:9). Joystick haut/bas : agrandir ou réduire.
  let size=1.0;
  const buf=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buf);
  function setQuad(){const Z=-2.0,QW=1.75*size,QH=QW*9/16;
    gl.bindBuffer(gl.ARRAY_BUFFER,buf);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-QW,-QH,Z,0,1, QW,-QH,Z,1,1, -QW,QH,Z,0,0, QW,QH,Z,1,0]),gl.STATIC_DRAW);}
  setQuad();
  const tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  const aP=gl.getAttribLocation(pr,'p'),aT=gl.getAttribLocation(pr,'t'),uP=gl.getUniformLocation(pr,'P');
  session.addEventListener('select',()=>{recenter=true;});
  session.addEventListener('squeeze',()=>{follow=!follow;recenter=true;dirty=true;});
  session.addEventListener('end',()=>{inVR=false;fetch('/head?p=0&y=0&f=0').catch(()=>{});});
  let uploaded=false;
  function onFrame(tm,fr){
    session.requestAnimationFrame(onFrame);
    for(const src of session.inputSources){const gp=src.gamepad;if(!gp||gp.axes.length<4)continue;const ay=gp.axes[3];
      if(Math.abs(ay)>0.5){size=Math.max(0.6,Math.min(1.5,size-ay*0.01));setQuad();}}
    const pose=fr.getViewerPose(ref);if(!pose)return;
    const q=pose.transform.orientation;
    const fx=-2*(q.x*q.z+q.w*q.y), fy=-2*(q.y*q.z-q.w*q.x), fz=-(1-2*(q.x*q.x+q.y*q.y));
    const yaw=Math.atan2(fx,-fz)*180/Math.PI, pitch=Math.asin(Math.max(-1,Math.min(1,fy)))*180/Math.PI;
    if(tm-lastHead>50){lastHead=tm;
      fetch('/head?p='+pitch.toFixed(1)+'&y='+yaw.toFixed(1)+'&f='+(follow?1:0)+'&c='+(recenter?1:0),{cache:'no-store'})
        .then(r=>r.json()).then(s=>{state=s;dirty=true;}).catch(()=>{});
      recenter=false;}
    if(dirty||!uploaded){drawOSD();dirty=false;gl.bindTexture(gl.TEXTURE_2D,tex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,V);uploaded=true;}
    const layer=session.renderState.baseLayer;
    gl.bindFramebuffer(gl.FRAMEBUFFER,layer.framebuffer);
    gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
    gl.useProgram(pr);gl.bindBuffer(gl.ARRAY_BUFFER,buf);
    gl.enableVertexAttribArray(aP);gl.vertexAttribPointer(aP,3,gl.FLOAT,false,20,0);
    gl.enableVertexAttribArray(aT);gl.vertexAttribPointer(aT,2,gl.FLOAT,false,20,12);
    gl.bindTexture(gl.TEXTURE_2D,tex);
    for(const view of pose.views){const vp=layer.getViewport(view);gl.viewport(vp.x,vp.y,vp.width,vp.height);
      gl.uniformMatrix4fv(uP,false,view.projectionMatrix);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);}
  }
  session.requestAnimationFrame(onFrame);
}

// ======================= Audio : micro du casque et voix de l'IA =======================
const WORKLET="class MicPcm extends AudioWorkletProcessor{constructor(){super();this.b=new Int16Array(1600);this.n=0}"+
 "process(i){const c=i[0][0];if(c)for(let k=0;k<c.length;k++){const s=Math.max(-1,Math.min(1,c[k]));"+
 "this.b[this.n++]=s<0?s*0x8000:s*0x7fff;if(this.n===this.b.length){this.port.postMessage(this.b.buffer.slice(0));this.n=0}}return true}}"+
 "registerProcessor('mic-pcm',MicPcm);";
let audio=null;
async function startAudio(){
  if(audio)return;
  const talk=document.getElementById('talk');
  if(!window.isSecureContext||!navigator.mediaDevices){note.textContent='Pour l\'audio dans le casque, ouvre l\'adresse https://…:8443.';return;}
  try{
    const ws=new WebSocket('wss://'+location.host+'/audio');ws.binaryType='arraybuffer';
    const out=new AudioContext({sampleRate:24000});let next=0;const playing=new Set();
    ws.onmessage=e=>{
      if(typeof e.data==='string'){if(e.data==='flush'){playing.forEach(s=>{try{s.stop()}catch(_){}});playing.clear();next=0;}return;}
      const pcm=new Int16Array(e.data);const buf=out.createBuffer(1,pcm.length,24000);const ch=buf.getChannelData(0);
      for(let i=0;i<pcm.length;i++)ch[i]=pcm[i]/32768;
      const src=out.createBufferSource();src.buffer=buf;src.connect(out.destination);
      next=Math.max(next,out.currentTime+0.05);src.start(next);next+=buf.duration;
      playing.add(src);src.onended=()=>playing.delete(src);
    };
    const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
    const mic=new AudioContext({sampleRate:16000});
    await mic.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET],{type:'application/javascript'})));
    const node=new AudioWorkletNode(mic,'mic-pcm');
    node.port.onmessage=({data})=>{if(ws.readyState===1)ws.send(data);};
    mic.createMediaStreamSource(stream).connect(node);
    out.resume();mic.resume();
    audio={ws,out,mic,stream};audioOn=true;dirty=true;
    talk.textContent='🎙 Conversation dans le casque';talk.disabled=true;
    ws.onclose=()=>{stream.getTracks().forEach(t2=>t2.stop());mic.close();out.close();audio=null;audioOn=false;dirty=true;
      talk.textContent='🎙 Parler à l\'IA ici';talk.disabled=false;};
  }catch(e){note.textContent='Audio impossible : '+e.message;}
}
</script></body></html>
""".trimIndent()
}
