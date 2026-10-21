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
    /** Manettes du casque : manche gauche (x, y) et droit (x, y), de -1 à 1 (y vers le haut = négatif). */
    private val onSticks: (lx: Double, ly: Double, rx: Double, ry: Double) -> Unit = { _, _, _, _ -> },
    /** Boutons tenus : « takeoff » (A) ou « land » (B). */
    private val onButton: (String) -> Unit = {},
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
                val lx = q["lx"]?.toDoubleOrNull()
                if (lx != null) onSticks(lx, q["ly"]?.toDoubleOrNull() ?: 0.0, q["rx"]?.toDoubleOrNull() ?: 0.0, q["ry"]?.toDoubleOrNull() ?: 0.0)
                q["bt"]?.let { onButton(it) }
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
const HUD='#9EEBFF', WARN='#FFB547', RED='#FF4D5E', CYAN='#5FE1FF', GREY='#7FA3AE', DIM='rgba(4,14,22,.80)';
let state={}, frame=null, follow=true, inVR=false, audioOn=false, dirty=true;

function rr(x,y,w,h,r){g.beginPath();g.moveTo(x+r,y);g.arcTo(x+w,y,x+w,y+h,r);g.arcTo(x+w,y+h,x,y+h,r);g.arcTo(x,y+h,x,y,r);g.arcTo(x,y,x+w,y,r);g.closePath();}
function panel(x,y,w,h,title){g.fillStyle=DIM;rr(x,y,w,h,14);g.fill();g.strokeStyle='rgba(189,244,255,.35)';g.lineWidth=2;rr(x,y,w,h,14);g.stroke();
  if(title){g.fillStyle=HUD;g.font='bold 22px sans-serif';g.textAlign='left';g.fillText(title,x+16,y+32);}}
function t(s,x,y,size,color,align,bold){g.font=(bold?'bold ':'')+size+'px sans-serif';g.fillStyle=color||'#fff';g.textAlign=align||'left';g.fillText(s,x,y);}
function wrap(s,x,y,maxW,lh,maxLines,size,color){g.font=size+'px sans-serif';g.fillStyle=color;g.textAlign='left';
  const words=String(s).split(' ');let line='',n=0;
  for(const w of words){const test=line?line+' '+w:w;if(g.measureText(test).width>maxW&&line){g.fillText(line,x,y);y+=lh;line=w;if(++n>=maxLines-1){line=line+' …';break;}}else line=test;}
  if(line){g.fillText(line,x,y);y+=lh;}return y;}
function num(v,d){return (typeof v==='number'&&isFinite(v))?v:d;}
function f1(v){return num(v,0).toFixed(1).replace('.',',');}
function norm(a){return ((a%360)+540)%360-180;}
const MOVE='#E040FB';
function card(h){const n=['N','NE','E','SE','S','SO','O','NO'];return n[Math.round((((h%360)+360)%360)/45)%8];}
function mmss(s){s=Math.max(0,Math.round(num(s,0)));return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');}

function draw(){
  const s=state, tel=s.tel||{};
  g.fillStyle='#03080d';g.fillRect(0,0,W,H);
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
  if(s.indoor)warns.push([CYAN,'INTÉRIEUR']);
  if(s.rec||tel.rec)warns.unshift([RED,'● REC']);
  if(s.activity)warns.unshift([WARN,String(s.activity).toUpperCase().slice(0,28)]);
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
  g.strokeStyle='rgba(189,244,255,.5)';g.lineWidth=3;rr(VX,VY,VW,VH,10);g.stroke();

  drawLeft(s,tel);drawRight(s);
  // ---- bandeau bas ----
  panel(8,H-132,W-16,124);
  t('🥽 Tête → nacelle',28,H-86,24,HUD,'left',true);
  t('↻ Rotation du drone : '+(follow?'MARCHE':'ARRÊT'),28,H-46,24,follow?HUD:GREY,'left',true);
  t('Manches : gauche gaz/rotation, droite avant/côtés  ·  A tenu : décoller  ·  B tenu : atterrir  ·  Grip : rotation  ·  Gâchette : recentrer',W/2,H-86,18,'#fff','center');
  t('Dis « stop » ou « atterris » à tout moment',W/2,H-46,22,WARN,'center');
  t(audioOn?'🎙 Audio dans le casque':'🎙 Audio sur le téléphone',W-28,H-86,22,audioOn?HUD:GREY,'right',true);
  t((s.voice||'').replace(/^[^ ]+ /,'')||'Conversation fermée',W-28,H-46,22,GREY,'right');
  {const n=20, lv=voiceLevels(n,s), act=voiceActive(s), ex=W-560, ey=H-70;
    for(let i=0;i<n;i++){const v=Math.max(0.05,lv[i]), bh=4+v*44;g.fillStyle=act?'rgba(95,225,255,'+(0.45+v*0.55)+')':'rgba(189,244,255,.22)';g.fillRect(ex+i*10,ey-bh/2,6,bh);}}
}

function drawHUD(s,tel){
  const cx=VX+VW/2, cy=VY+VH/2, ppd=VH/40;
  drawMarks(g,s.marks,VX,VY,VW,VH);
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
  {const ad=aimDist(s,tel);if(ad!==null&&ad<500){g.font='bold 24px sans-serif';g.textAlign='left';g.fillStyle=WARN;g.fillText('⌖ '+(ad<10?f1(ad):Math.round(ad))+' m (« va là »)',cx+80,cy+40);}}
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
  drawMap(g,x+90,ny+140,72,tel,s.nav,s.moveArrow,s.scene);
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
  {const bl={sol:['au sol',GREY],actifs:['actifs',HUD],gps:['GPS suffit',HUD],trop_haut:['trop haut (normal)',GREY],trop_bas:['près du sol',GREY],inactifs:['INACTIFS',RED]}[tel.below];
    row('Capteurs dessous',bl?bl[0]:(tel.vps===undefined?'—':tel.vps?'actifs':'—'),bl?bl[1]:GREY);}
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
function drawMap(c,cx,cy,R,tel,nav,mv,scene){
  nav=nav||{};
  c.save();c.beginPath();c.arc(cx,cy,R,0,Math.PI*2);c.closePath();
  c.fillStyle='rgba(10,20,15,.85)';c.fill();c.clip();
  const lat=tel.lat,lon=tel.lon, gps=typeof lat==='number'&&typeof lon==='number';
  const pos=nav.pos||[0,0], hd=(typeof tel.heading==='number'?tel.heading:0)*Math.PI/180;
  const near=(p)=>Math.hypot(p[0]-pos[0],p[1]-pos[1]);
  // Zoom : départ, pilote, points marqués et trace restent visibles
  let far=gps?20:3;
  far=Math.max(far,near([0,0]));
  for(const w of (nav.wps||[]))far=Math.max(far,Math.min(near(w),gps?400:30));
  for(const q of (nav.pins||[]))far=Math.max(far,Math.min(near(q),gps?400:30));
  if(scene&&scene.items)for(const q of scene.items)far=Math.max(far,Math.min(near(q),gps?100:12));
  if(gps)for(const [a,b] of [[tel.homeLat,tel.homeLon],[tel.pilotLat,tel.pilotLon]]){
    if(typeof a==='number'&&typeof b==='number'){const dy=(a-lat)*111320,dx=(b-lon)*111320*Math.cos(lat*Math.PI/180);far=Math.max(far,Math.hypot(dx,dy));}}
  let z=19,sc;const mpp=zz=>156543.03*Math.cos((gps?lat:45)*Math.PI/180)/Math.pow(2,zz);
  c.translate(cx,cy);c.rotate(-hd);
  if(gps){
    while(z>12&&far/mpp(z)>R*0.8)z--;
    sc=1/mpp(z);
    const [px,py]=proj(lat,lon,z), span=R*1.5;
    for(let tx=Math.floor((px-span)/256);tx<=Math.floor((px+span)/256);tx++)
      for(let ty=Math.floor((py-span)/256);ty<=Math.floor((py+span)/256);ty++){
        const im=tile(z,tx,ty);if(im)c.drawImage(im,tx*256-px,ty*256-py,256,256);}
  }else{
    sc=R*0.8/far;
    // Quadrillage d'un mètre (ou plus) pour la carte locale
    let grid=1;while(grid*sc<18)grid*=5;
    c.strokeStyle='rgba(189,244,255,.15)';c.lineWidth=1;
    const ox=-pos[1]*sc, oy=pos[0]*sc;
    for(let k=-Math.ceil(1.5*R/(grid*sc))-1;k<=Math.ceil(1.5*R/(grid*sc))+1;k++){
      const gx=(Math.round(pos[1]/grid)+k)*grid*sc+ox, gy=-(Math.round(pos[0]/grid)+k)*grid*sc+oy;
      c.beginPath();c.moveTo(gx,-R*1.5);c.lineTo(gx,R*1.5);c.stroke();c.beginPath();c.moveTo(-R*1.5,gy);c.lineTo(R*1.5,gy);c.stroke();}
  }
  const P=(n,e)=>[(e-pos[1])*sc,-(n-pos[0])*sc];
  // Repérage : place libre (zone verte) et ce qui a été vu (portes, cible, murs et meubles)
  if(scene){const fr=scene.free||[], o0=scene.o||[0,0];
    // Plan des pièces : contour de chaque pièce repérée, son nom ; la pièce actuelle en plus clair
    for(const r of (scene.rooms||[])){const pts=(r[1]||[]).map(q=>P(q[0],q[1]));if(pts.length<3)continue;
      c.fillStyle=r[4]?'rgba(95,225,255,.10)':'rgba(189,244,255,.05)';c.strokeStyle=r[4]?'rgba(95,225,255,.75)':'rgba(189,244,255,.4)';c.lineWidth=1.5;
      c.beginPath();pts.forEach((p,i)=>i?c.lineTo(p[0],p[1]):c.moveTo(p[0],p[1]));c.closePath();c.fill();c.stroke();
      const [lx,ly]=P(r[2],r[3]);c.save();c.translate(lx,ly);c.rotate(hd);c.font='600 11px sans-serif';c.textAlign='center';c.fillStyle=r[4]?ACC:'rgba(189,244,255,.7)';
      c.fillText(String(r[0]).toUpperCase(),0,-6);c.restore();}
    if(fr.length>2){const pts=fr.slice().sort((a,b)=>a[0]-b[0]).map(q=>P(o0[0]+q[1]*Math.cos(q[0]*Math.PI/180),o0[1]+q[1]*Math.sin(q[0]*Math.PI/180)));
      c.fillStyle='rgba(124,255,178,.14)';c.strokeStyle='rgba(124,255,178,.6)';c.lineWidth=1.5;c.beginPath();pts.forEach((p,i)=>i?c.lineTo(p[0],p[1]):c.moveTo(p[0],p[1]));c.closePath();c.fill();c.stroke();}
    for(const q of (scene.items||[])){const [x,y]=P(q[0],q[1]);
      if(q[3]==='porte'||q[3]==='passage'){c.save();c.translate(x,y);c.rotate(hd);c.fillStyle=q[4]?'rgba(232,166,255,.5)':'#E9A6FF';c.strokeStyle='#000';c.lineWidth=2;c.fillRect(-6,-9,12,18);c.strokeRect(-6,-9,12,18);c.restore();}
      else if(q[3]==='cible'){c.save();c.translate(x,y);c.rotate(hd);c.fillStyle=RED;c.strokeStyle='#000';c.lineWidth=2;c.beginPath();
        for(let k=0;k<10;k++){const rr2=k%2?5:12,a=k*Math.PI/5-Math.PI/2;c.lineTo(Math.cos(a)*rr2,Math.sin(a)*rr2);}c.closePath();c.fill();c.stroke();c.restore();}
      else{c.fillStyle='rgba(189,244,255,.55)';c.beginPath();c.arc(x,y,3,0,Math.PI*2);c.fill();}}}
  // Zones déjà fouillées
  for(const q of (nav.cov||[])){const [x,y]=P(q[0],q[1]);c.fillStyle='rgba(189,244,255,.16)';c.beginPath();c.arc(x,y,Math.max(3,q[2]*sc),0,Math.PI*2);c.fill();}
  // Trace du vol
  const tr=nav.track||[];
  if(tr.length>1){c.strokeStyle='rgba(255,213,79,.85)';c.lineWidth=3;c.setLineDash([8,6]);c.beginPath();
    tr.forEach((q,i)=>{const [x,y]=P(q[0],q[1]);if(i)c.lineTo(x,y);else c.moveTo(x,y);});
    const [x,y]=P(pos[0],pos[1]);c.lineTo(x,y);c.stroke();c.setLineDash([]);}
  function dot(x,y,color,label,r){c.save();c.translate(x,y);c.rotate(hd);c.fillStyle=color;c.strokeStyle='#000';c.lineWidth=3;
    c.beginPath();c.arc(0,0,r,0,Math.PI*2);c.fill();c.stroke();
    if(label){c.fillStyle='#000';c.font='bold '+Math.round(r*1.2)+'px sans-serif';c.textAlign='center';c.fillText(label,0,r*0.42);}c.restore();}
  function mark(a,b,color,label){if(typeof a!=='number'||typeof b!=='number')return;const [x,y]=proj(a,b,z),[px,py]=proj(lat,lon,z);
    dot(x-px,y-py,color,label,label?13:8);}
  // Repères : cibles trouvées (rouge), pistes (orange)
  for(const q of (nav.pins||[])){const [x,y]=P(q[0],q[1]);dot(x,y,q[3]?RED:'#FF9800','!',11);}
  // Points marqués
  for(const w of (nav.wps||[])){const [x,y]=P(w[0],w[1]);dot(x,y,'#FFD54F',String(w[2]),12);}
  // Départ et pilote
  if(nav.pos){const [x,y]=P(0,0);dot(x,y,CYAN,'H',13);}else if(gps)mark(tel.homeLat,tel.homeLon,CYAN,'H');
  if(gps)mark(tel.pilotLat,tel.pilotLon,'#448AFF','');
  c.restore();c.save();
  // Nord sur le bord
  const nx=cx+Math.sin(-hd)*(R-22), ny=cy-Math.cos(-hd)*(R-22);
  c.fillStyle='#fff';c.font='bold 20px sans-serif';c.textAlign='center';c.strokeStyle='#000';c.lineWidth=4;
  c.strokeText('N',nx,ny+7);c.fillText('N',nx,ny+7);c.restore();
  // Drone au centre, pointe vers le haut (= sa direction)
  c.fillStyle=WARN;c.strokeStyle='#000';c.lineWidth=3;c.beginPath();c.moveTo(cx,cy-18);c.lineTo(cx-12,cy+12);c.lineTo(cx,cy+5);c.lineTo(cx+12,cy+12);c.closePath();c.fill();c.stroke();
  c.strokeStyle=CYAN;c.lineWidth=3;c.beginPath();c.arc(cx,cy,R,0,Math.PI*2);c.stroke();
  // Flèche du déplacement demandé (violet) : direction de la vue au moment de l'ordre
  const hdD=hd*180/Math.PI;
  if(mv&&typeof mv.b==='number'){const a=norm(mv.b-hdD)*Math.PI/180, L=Math.max(30,Math.min(R*0.85,mv.d*sc)), ex=cx+Math.sin(a)*L, ey=cy-Math.cos(a)*L;
    c.save();c.strokeStyle=MOVE;c.fillStyle=MOVE;c.lineWidth=mv.pending?4:6;if(mv.pending)c.setLineDash([8,6]);
    c.beginPath();c.moveTo(cx,cy);c.lineTo(ex,ey);c.stroke();c.setLineDash([]);
    c.translate(ex,ey);c.rotate(a);c.beginPath();c.moveTo(0,-4);c.lineTo(-11,14);c.lineTo(11,14);c.closePath();c.fill();c.restore();
    c.font='bold 18px sans-serif';c.textAlign='center';c.strokeStyle='#000';c.lineWidth=4;const lb=f1(mv.d)+' m';
    c.strokeText(lb,ex,ey-16);c.fillStyle=MOVE;c.fillText(lb,ex,ey-16);}
  // Échelle et crédit des images
  c.font='bold 16px sans-serif';c.textAlign='center';c.fillStyle='#fff';c.strokeStyle='#000';c.lineWidth=4;
  const rm=R/sc, scale='rayon '+(rm<10?rm.toFixed(1).replace('.',','):Math.round(rm))+' m';c.strokeText(scale,cx,cy+R-14);c.fillText(scale,cx,cy+R-14);
  c.font='11px sans-serif';c.fillStyle=GREY;c.fillText(gps?'Imagerie © Esri':'sans GPS : position estimée',cx,cy+R+16);
}

/**
 * Cadres sur la vidéo : FOLLOWING (suivie, confirmée par l'IA : bleu plein), TRACKING (suivie par le téléphone seul),
 * PROPOSED (ce que l'IA compte suivre, attend ton oui : orange qui clignote), CANDIDATE (autres objets vus : pointillés),
 * FOUND (trouvé pendant une recherche).
 */
function drawMarks(c,list,X,Y,W2,H2){if(!list||!list.length)return;const tm=performance.now();
  for(const m of list){const x=X+(m.cx-m.w/2)*W2, y=Y+(m.cy-m.h/2)*H2, w=Math.max(16,m.w*W2), h=Math.max(16,m.h*H2), k=Math.min(w,h)*0.28;
    const st={FOLLOWING:['#5FE1FF',4,'◉ SUIVI'],TRACKING:['#BDF4FF',3,'◌ SUIVI'],PROPOSED:['#FFB547',4,'CIBLE ?'],
      FOUND:['#7CFFB2',4,'✓ TROUVÉ'],CANDIDATE:['rgba(189,244,255,.75)',2,'']}[m.k]||['#BDF4FF',2,''];
    c.save();c.shadowColor='rgba(0,0,0,.8)';c.shadowBlur=6;c.strokeStyle=st[0];c.lineWidth=st[1];
    if(m.k==='PROPOSED')c.globalAlpha=0.55+0.45*Math.abs(Math.sin(tm/260));
    if(m.k==='CANDIDATE'){c.setLineDash([6,6]);c.strokeRect(x,y,w,h);c.setLineDash([]);}
    else{c.beginPath();
      c.moveTo(x,y+k);c.lineTo(x,y);c.lineTo(x+k,y);c.moveTo(x+w-k,y);c.lineTo(x+w,y);c.lineTo(x+w,y+k);
      c.moveTo(x+w,y+h-k);c.lineTo(x+w,y+h);c.lineTo(x+w-k,y+h);c.moveTo(x+k,y+h);c.lineTo(x,y+h);c.lineTo(x,y+h-k);c.stroke();
      // petite croix au centre de la cible
      c.lineWidth=2;c.beginPath();c.moveTo(x+w/2-8,y+h/2);c.lineTo(x+w/2+8,y+h/2);c.moveTo(x+w/2,y+h/2-8);c.lineTo(x+w/2,y+h/2+8);c.stroke();}
    const lb=(st[2]?st[2]+' · ':'')+String(m.l||'').toUpperCase().slice(0,26)+(m.k==='PROPOSED'?' — OUI / NON':'');
    c.font='600 '+(m.k==='CANDIDATE'?18:24)+'px "Segoe UI",Roboto,sans-serif';c.letterSpacing='2px';c.textAlign='left';
    c.fillStyle=st[0];c.fillText(lb,x,y-10);c.restore();}}

/** Distance au sol du point visé (centre de l'image), d'après l'altitude et la nacelle. */
function aimDist(s,tel){const gp=num(tel.gimbal,0), a=num(s.alt,0);if(gp>-6||a<0.5)return null;return a/Math.tan(-gp*Math.PI/180);}

// ======================= Égaliseur : la voix de l'IA (et le micro) =======================
let voiceAn=null, micAn=null;
const eqData=new Uint8Array(128);
/** n niveaux de 0 à 1 : vraie voix de l'IA quand elle passe par le casque, sinon animation pendant qu'elle parle. */
function voiceLevels(n,s){
  const out=new Array(n).fill(0);let energy=0;
  if(voiceAn){voiceAn.getByteFrequencyData(eqData);
    for(let i=0;i<n;i++){const k=Math.floor(2+i*40/n);const v=eqData[k]/255;out[i]=v;energy+=v;}}
  const speaking=String(s.voice||'').indexOf('parle')>=0;
  if(energy/n<0.02&&speaking&&!audioOn){const t=performance.now()/1000;
    for(let i=0;i<n;i++)out[i]=0.25+0.55*Math.abs(Math.sin(t*7+i*0.9)*Math.sin(t*3.1+i*0.37));}
  return out;
}
function micLevel(){if(!micAn)return 0;micAn.getByteFrequencyData(eqData);let m=0;for(let i=2;i<40;i++)m=Math.max(m,eqData[i]);return m/255;}
function voiceActive(s){if(voiceAn){voiceAn.getByteFrequencyData(eqData);let e=0;for(let i=2;i<40;i++)e+=eqData[i];if(e>200)return true;}
  return String(s.voice||'').indexOf('parle')>=0;}

// ======================= VR : HUD épuré science-fiction, la caméra en grand =======================
const V=document.createElement('canvas');V.width=1920;V.height=1080;
const ICE='#BDF4FF', ACC='#5FE1FF', AMB='#FFB547', DNG='#FF4D5E', FAINT='rgba(189,244,255,.28)';
function drawOSD(){
  const o=V.getContext('2d'), s=state, tel=s.tel||{}, w=1920, h=1080, cx=w/2, cy=h/2;
  o.fillStyle='#000';o.fillRect(0,0,w,h);
  if(frame)o.drawImage(frame,0,0,w,h);else{o.fillStyle=FAINT;o.font='300 44px sans-serif';o.textAlign='center';o.fillText('—  PAS DE VIDÉO  —',cx,cy-120);}
  // léger voile sombre sur les bords : les traits fins restent lisibles sur un ciel clair
  const vg=o.createRadialGradient(cx,cy,h*0.35,cx,cy,h*0.95);vg.addColorStop(0,'rgba(0,0,0,0)');vg.addColorStop(1,'rgba(0,10,18,.45)');o.fillStyle=vg;o.fillRect(0,0,w,h);
  drawMarks(o,s.marks,0,0,w,h);
  o.save();o.shadowColor='rgba(0,0,0,.75)';o.shadowBlur=6;o.lineCap='round';
  function ln(x1,y1,x2,y2,c,lw){o.strokeStyle=c||ICE;o.lineWidth=lw||2;o.beginPath();o.moveTo(x1,y1);o.lineTo(x2,y2);o.stroke();}
  function tx(str,x,y,size,color,align,weight,sp){o.font=(weight||500)+' '+size+'px "Segoe UI",Roboto,"Helvetica Neue",sans-serif';
    o.letterSpacing=(sp===undefined?2:sp)+'px';o.textAlign=align||'left';o.fillStyle=color||ICE;o.fillText(str,x,y);}
  function box(x,y,bw,bh,c,r){o.strokeStyle=c||ICE;o.lineWidth=2;o.beginPath();o.roundRect(x,y,bw,bh,r||6);o.stroke();}
  // Cadre aux angles coupés
  o.strokeStyle='rgba(189,244,255,.55)';o.lineWidth=2;
  for(const [sx,sy] of [[1,1],[-1,1],[1,-1],[-1,-1]]){const x0=sx>0?40:w-40, y0=sy>0?40:h-40;
    o.beginPath();o.moveTo(x0,y0+sy*140);o.lineTo(x0,y0+sy*40);o.lineTo(x0+sx*40,y0);o.lineTo(x0+sx*180,y0);o.stroke();}
  // Petites croix de repère, très discrètes
  o.strokeStyle='rgba(189,244,255,.18)';o.lineWidth=1.5;
  for(let gx=160;gx<w;gx+=320)for(let gy=180;gy<h-200;gy+=240){if(Math.abs(gx-cx)<420&&Math.abs(gy-cy)<300)continue;
    o.beginPath();o.moveTo(gx-7,gy);o.lineTo(gx+7,gy);o.moveTo(gx,gy-7);o.lineTo(gx,gy+7);o.stroke();}

  // ---- Haut : cap ----
  const hd=num(tel.heading,0), tw=600, ppx=tw/90, ty=86;
  ln(cx-tw/2,ty,cx+tw/2,ty,'rgba(189,244,255,.6)',2);
  for(let a=Math.ceil((hd-45)/5)*5;a<=hd+45;a+=5){const x=cx+(a-hd)*ppx;const big=a%15===0;ln(x,ty,x,ty-(big?16:8),'rgba(189,244,255,.8)',2);
    if(big){const n=((a%360)+360)%360;tx(n%45===0?card(n):String(n),x,ty-26,20,n%45===0?ICE:'rgba(189,244,255,.75)','center',n%45===0?700:400,1);}}
  o.fillStyle=ACC;o.beginPath();o.moveTo(cx,ty+6);o.lineTo(cx-9,ty+20);o.lineTo(cx+9,ty+20);o.closePath();o.fill();
  box(cx-46,ty+28,92,36,ACC,4);tx(String(Math.round(((hd%360)+360)%360)).padStart(3,'0'),cx,ty+55,24,ICE,'center',600,3);
  if(typeof tel.homeBearing==='number'){const rel=norm(tel.homeBearing-hd);const x=cx+Math.max(-45,Math.min(45,rel))*ppx;tx('H',x,ty-48,20,ACC,'center',700,0);}
  tx('NAV',cx,ty+92,16,'rgba(189,244,255,.6)','center',600,4);
  // Cap de vol aux manettes, quand tu regardes ailleurs : repère sur le cap, ou flèche sur le côté
  if(typeof tel.travelHeading==='number'){const rel=norm(tel.travelHeading-hd);
    if(Math.abs(rel)>4){if(Math.abs(rel)<=45){const x=cx+rel*ppx;o.fillStyle=MOVE;o.beginPath();o.moveTo(x,ty+4);o.lineTo(x-8,ty+18);o.lineTo(x+8,ty+18);o.closePath();o.fill();tx('VOL',x,ty+36,14,MOVE,'center',700,2);}
      else tx(rel<0?'◀ CAP DE VOL '+Math.round(-rel)+'°':'CAP DE VOL '+Math.round(rel)+'° ▶',rel<0?cx-tw/2-16:cx+tw/2+16,ty+6,16,MOVE,rel<0?'right':'left',700,2);}
    if(Math.abs(rel)>35&&(s.sticks||pad.active)&&num(tel.hSpeed,0)>0.2)tx('⚠ TU NE REGARDES PAS OÙ TU VAS · DÉTECTEUR AVEUGLE',cx,ty+124,16,AMB,'center',700,2);}
  // Haut gauche : appareil, IA ; haut droite : heure, mode
  tx(({BEBOP:'BEBOP 2',MAVIC:'MAVIC MINI',SIM:'SIMULATION'}[s.mode]||'—')+(s.indoor?'  ·  INTÉRIEUR':''),110,118,22,ICE,'left',600,4);
  const ia=s.manual?['MANUEL',ACC]:s.paused?['IA · PAUSE',AMB]:[(s.confirm===false?'IA · AUTONOME':'IA · VALIDATION'),ACC];
  tx(ia[0],110,150,20,ia[1],'left',600,3);
  if(s.activity)tx('▸ '+String(s.activity).toUpperCase().slice(0,34),110,180,18,AMB,'left',600,2);
  const now=new Date();tx(now.toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'}),w-110,118,26,ICE,'right',500,3);
  box(w-238,96,30,26,'rgba(189,244,255,.6)',3);
  tx((tel.flightMode||'—').toUpperCase().slice(0,12),w-110,150,18,'rgba(189,244,255,.8)','right',600,3);
  if(s.rec||tel.rec){o.fillStyle=DNG;o.beginPath();o.arc(w-212,190,8,0,Math.PI*2);o.fill();tx('REC',w-110,197,18,DNG,'right',700,3);}

  // ---- Centre : réticule circulaire, échelle d'assiette ----
  const pitch=num(tel.pitch,0), roll=num(tel.roll,0), R=190, ppd=7;
  o.strokeStyle='rgba(189,244,255,.8)';o.lineWidth=2;
  o.beginPath();o.arc(cx,cy,R,Math.PI*0.80,Math.PI*1.20);o.stroke();o.beginPath();o.arc(cx,cy,R,-Math.PI*0.20,Math.PI*0.20);o.stroke();
  o.setLineDash([2,9]);o.beginPath();o.arc(cx,cy,R,Math.PI*1.25,Math.PI*1.75);o.stroke();o.beginPath();o.arc(cx,cy,R,Math.PI*0.25,Math.PI*0.75);o.stroke();o.setLineDash([]);
  o.fillStyle=ICE;for(const sy of [-1,1]){o.beginPath();o.moveTo(cx,cy+sy*(R+4));o.lineTo(cx-8,cy+sy*(R+16));o.lineTo(cx+8,cy+sy*(R+16));o.closePath();o.stroke();}
  // centre : cercle, petite croix, crochets
  o.beginPath();o.arc(cx,cy,10,0,Math.PI*2);o.stroke();ln(cx-18,cy,cx-26,cy);ln(cx+18,cy,cx+26,cy);ln(cx,cy-18,cx,cy-26);
  for(const sx of [-1,1]){o.beginPath();o.moveTo(cx+sx*40,cy-28);o.lineTo(cx+sx*48,cy-28);o.lineTo(cx+sx*48,cy+28);o.lineTo(cx+sx*40,cy+28);o.stroke();}
  // assiette (tourne avec le roulis)
  o.save();o.translate(cx,cy);o.rotate(-roll*Math.PI/180);
  for(let a=-20;a<=20;a+=10){const y=(pitch-a)*ppd;if(Math.abs(y)>R-20)continue;
    if(a===0){ln(-R-150,y,-R+6,y,'rgba(189,244,255,.75)',2);ln(R-6,y,R+150,y,'rgba(189,244,255,.75)',2);continue;}
    o.setLineDash(a<0?[6,6]:[]);ln(-120,y,-60,y,'rgba(189,244,255,.6)',2);ln(60,y,120,y,'rgba(189,244,255,.6)',2);o.setLineDash([]);
    tx(String(a),-130,y+6,16,'rgba(189,244,255,.7)','right',500,1);tx(String(a),130,y+6,16,'rgba(189,244,255,.7)','left',500,1);}
  o.restore();
  {const ad=aimDist(s,tel);if(ad!==null&&ad<500)tx('⌖ '+(ad<10?f1(ad):Math.round(ad))+' M',cx+60,cy+56,20,AMB,'left',600,2);}

  // ---- Échelles : vitesse à gauche, altitude à droite ----
  function tape(x,dir,val,unit,label,step,minor,warnAbove){
    const top=cy-230, bot=cy+230, pp=460/(step*6);
    ln(x,top,x,bot,'rgba(189,244,255,.45)',2);
    const v=num(val,0);
    for(let k=Math.floor((v-step*3)/minor)*minor;k<=v+step*3;k+=minor){const y=cy-(k-v)*pp;if(y<top||y>bot)continue;
      const major=Math.abs(k/step-Math.round(k/step))<1e-6;ln(x,y,x+dir*(major?18:9),y,'rgba(189,244,255,.75)',2);
      if(major)tx(k.toFixed(step<1?1:0).replace('.',','),x+dir*28,y+7,18,'rgba(189,244,255,.75)',dir>0?'left':'right',500,1);}
    if(warnAbove!==null){const y=cy-(warnAbove-v)*pp;if(y>=top&&y<=bot){ln(x-dir*4,y,x+dir*30,y,DNG,3);tx('MAX',x-dir*10,y-8,14,DNG,dir>0?'right':'left',700,2);}}
    // curseur de valeur
    const bx=dir>0?x-150:x+30;box(bx,cy-22,120,44,ACC,4);
    o.fillStyle=ACC;o.beginPath();if(dir>0){o.moveTo(x-30,cy-8);o.lineTo(x-18,cy);o.lineTo(x-30,cy+8);}else{o.moveTo(x+30,cy-8);o.lineTo(x+18,cy);o.lineTo(x+30,cy+8);}o.fill();
    tx(f1(v),bx+60,cy+9,26,ICE,'center',600,1);
    tx(label,dir>0?bx:bx+120,cy-36,16,'rgba(189,244,255,.75)',dir>0?'left':'right',700,4);
    tx(unit,dir>0?bx:bx+120,cy+48,14,'rgba(189,244,255,.6)',dir>0?'left':'right',600,3);}
  tape(cx-560,1,tel.hSpeed,'M/S','SPD',1,0.5,null);
  tape(cx+560,-1,s.alt,'M','ALT',1,0.5,num(s.ceiling,null));
  {const vs=num(tel.vSpeed,0);if(Math.abs(vs)>0.1)tx((vs>0?'▲ ':'▼ ')+f1(Math.abs(vs)),cx+590,cy+86,18,AMB,'left',600,2);}

  // ---- Bas : panneaux ----
  const py=h-250, ph=200;
  function panelF(x,pw,title){o.strokeStyle='rgba(189,244,255,.55)';o.lineWidth=2;o.beginPath();
    o.moveTo(x+18,py);o.lineTo(x+pw-18,py);o.lineTo(x+pw,py+18);o.lineTo(x+pw,py+ph);o.lineTo(x,py+ph);o.lineTo(x,py+18);o.closePath();o.stroke();
    o.fillStyle='rgba(4,16,24,.35)';o.fill();if(title)tx(title,x+18,py-10,16,'rgba(189,244,255,.8)','left',700,4);}
  // gauche : carte, départ, temps de vol
  panelF(90,500,'NAV · CARTE');
  drawMap(o,200,py+100,88,tel,s.nav,s.moveArrow,s.scene);
  tx('HOME',320,py+46,15,'rgba(189,244,255,.7)','left',700,4);tx(f1(num(tel.homeDist,num(s.dist,0)))+' M',320,py+80,30,ICE,'left',600,2);
  tx('DUR',320,py+124,15,'rgba(189,244,255,.7)','left',700,4);tx(mmss(tel.flightTime),320,py+156,26,ICE,'left',500,3);
  tx((typeof tel.sats==='number'?tel.sats:'—')+' SAT',470,py+46,15,num(tel.sats,0)>=8?ACC:AMB,'left',700,3);
  tx('CAM '+Math.round(num(tel.gimbal,0))+'°',470,py+124,15,'rgba(189,244,255,.8)','left',700,3);
  // droite : batterie (anneau), signal, protection
  const px2=w-590;panelF(px2,500,'SYS · ÉNERGIE');
  const b=num(s.battery,null), bc=b===null?FAINT:b<30?DNG:b<50?AMB:ACC, rx=px2+110, ry=py+104;
  o.lineWidth=10;o.strokeStyle='rgba(189,244,255,.15)';o.beginPath();o.arc(rx,ry,62,Math.PI*0.75,Math.PI*2.25);o.stroke();
  if(b!==null){o.strokeStyle=bc;o.beginPath();o.arc(rx,ry,62,Math.PI*0.75,Math.PI*(0.75+1.5*b/100));o.stroke();}
  o.lineWidth=2;tx(b===null?'—':String(b),rx,ry+10,34,ICE,'center',600,1);tx('BAT %',rx,ry+40,13,'rgba(189,244,255,.7)','center',700,3);
  const sig=num(tel.signal,null);
  tx('SIG',px2+220,py+46,15,'rgba(189,244,255,.7)','left',700,4);
  for(let i=0;i<5;i++){const on=sig!==null&&sig>i*20;o.fillStyle=on?(sig<30?AMB:ACC):'rgba(189,244,255,.15)';o.fillRect(px2+290+i*16,py+46-8-i*5,10,8+i*5);}
  tx('GUARD',px2+220,py+96,15,'rgba(189,244,255,.7)','left',700,4);
  {const tc=num(s.ttc,null);tx(!s.guardOn?'OFF':!s.guardWatching?'VEILLE':(tc===null||tc>9.9?'LIBRE':f1(tc)+' S'),px2+330,py+96,18,tc!==null&&tc<3?AMB:ICE,'left',600,2);}
  tx('HEAD',px2+220,py+146,15,'rgba(189,244,255,.7)','left',700,4);tx(follow?'SUIVI':'COUPÉ',px2+330,py+146,18,follow?ICE:AMB,'left',600,2);
  {const d=num(tel.homeDist,num(s.dist,0)), rad=Math.max(1,num(s.radius,15)), k=Math.min(1,d/rad);
    tx('RAYON',470,py+160,15,'rgba(189,244,255,.7)','left',700,4);o.fillStyle='rgba(189,244,255,.15)';o.fillRect(470,py+172,90,6);
    o.fillStyle=k>0.9?DNG:ACC;o.fillRect(470,py+172,90*k,6);}
  // ---- Manettes : les deux manches, et l'anneau quand A / B sont tenus ----
  if(pad.active||s.sticks){const by2=cy+R-30;
    for(const [ox,sx,sy,lb] of [[-R-130,pad.lx,pad.ly,'GAZ · ROTATION'],[R+130,pad.rx,pad.ry,'AVANT · CÔTÉS']]){
      o.strokeStyle='rgba(189,244,255,.6)';o.lineWidth=2;o.beginPath();o.arc(cx+ox,by2,34,0,Math.PI*2);o.stroke();
      ln(cx+ox-34,by2,cx+ox+34,by2,'rgba(189,244,255,.2)',1);ln(cx+ox,by2-34,cx+ox,by2+34,'rgba(189,244,255,.2)',1);
      o.fillStyle=ACC;o.beginPath();o.arc(cx+ox+sx*30,by2+sy*30,8,0,Math.PI*2);o.fill();
      tx(lb,cx+ox,by2+58,12,'rgba(189,244,255,.7)','center',700,3);}
    tx('◉ MANETTES',cx-R-130,by2-48,15,ACC,'center',700,4);
    tx(s.sticksMode==='regard'?'SUIT LE REGARD':'REGARD LIBRE',cx+R+130,by2-48,15,s.sticksMode==='regard'?AMB:ACC,'center',700,3);
    tx('clic manche gauche : changer',cx+R+130,by2+76,11,'rgba(189,244,255,.5)','center',600,1);}
  for(const [hold,lb,col] of [[pad.holdA,'DÉCOLLAGE',ACC],[pad.holdB,'ATTERRISSAGE',AMB]]){if(!hold)continue;
    const k=Math.min(1,(performance.now()-hold)/1500);o.lineWidth=8;o.strokeStyle='rgba(189,244,255,.2)';o.beginPath();o.arc(cx,cy,R+40,0,Math.PI*2);o.stroke();
    o.strokeStyle=col;o.beginPath();o.arc(cx,cy,R+40,-Math.PI/2,-Math.PI/2+k*Math.PI*2);o.stroke();o.lineWidth=2;
    tx(k<1?lb+' · MAINTIENS':lb+' ✓',cx,cy+R+82,22,col,'center',700,5);}

  // ---- Égaliseur de la voix de l'IA (coin haut gauche) ----
  {const ex=110, ey=220, n=24, lv=voiceLevels(n,s), act=voiceActive(s);
    tx(act?'IA · PARLE':'IA · VOIX',ex,ey,15,act?ACC:'rgba(189,244,255,.55)','left',700,4);
    for(let i=0;i<n;i++){const v=Math.max(0.04,lv[i]);const bh=4+v*56;o.fillStyle=act?'rgba(95,225,255,'+(0.45+v*0.55)+')':'rgba(189,244,255,.25)';
      o.fillRect(ex+i*11,ey+40-bh/2,6,bh);}
    const m=micLevel();if(audioOn){tx('MIC',ex,ey+96,13,'rgba(189,244,255,.6)','left',700,4);
      o.fillStyle='rgba(189,244,255,.15)';o.fillRect(ex+52,ey+88,200,6);o.fillStyle=m>0.5?ACC:'rgba(189,244,255,.7)';o.fillRect(ex+52,ey+88,200*Math.min(1,m*1.4),6);}}

  // ---- Étape du plan, alertes, proposition, sous-titres ----
  if(s.plan&&s.plan.steps){const cur=s.plan.steps.findIndex(x=>x.s==='DOING');const st=cur>=0?s.plan.steps[cur]:null;
    if(st)tx('PLAN '+(cur+1)+'/'+s.plan.steps.length+'  ▸  '+st.t.toUpperCase().slice(0,56),cx,cy-R-60,18,ACC,'center',600,3);}
  if(s.obstacle){o.strokeStyle=DNG;o.lineWidth=4;const k=60+10*Math.sin(performance.now()/120);
    for(const [sx,sy] of [[-1,-1],[1,-1],[-1,1],[1,1]]){o.beginPath();o.moveTo(cx+sx*(R+k),cy+sy*(R+k-50));o.lineTo(cx+sx*(R+k),cy+sy*(R+k));o.lineTo(cx+sx*(R+k-50),cy+sy*(R+k));o.stroke();}
    tx('OBSTACLE · ARRÊT',cx,cy-R-100,34,DNG,'center',700,6);}
  if(s.manual)tx('PILOTE · COMMANDES MANUELLES',cx,cy+R+70,24,ACC,'center',700,5);
  if(num(s.battery,100)<30)tx('BATTERIE FAIBLE',cx,cy-R-140,28,DNG,'center',700,6);
  if(s.mode&&s.mode!=='NONE'&&s.linkUp===false)tx('LIAISON PERDUE',cx,cy-R-180,32,DNG,'center',700,6);
  // Bas centre (entre les panneaux) : proposition puis sous-titres
  let by=h-150;
  if(s.pending){o.fillStyle='rgba(30,20,0,.55)';o.beginPath();o.roundRect(cx-330,py-6,660,54,6);o.fill();box(cx-330,py-6,660,54,AMB,6);
    tx(String(s.pending).toUpperCase().slice(0,40),cx,py+20,18,AMB,'center',700,2);tx('OUI  /  NON',cx,py+40,14,AMB,'center',700,6);
    if(s.moveArrow)tx('flèche violette (carte) = direction, selon ta vue quand tu as parlé',cx,py-16,16,'#E9A6FF','center',600,1);}
  if(s.say){o.font='400 26px "Segoe UI",Roboto,sans-serif';o.letterSpacing='0.5px';const lines=[];let line='';
    for(const wd of s.say.split(' ')){const tt=line?line+' '+wd:wd;if(o.measureText(tt).width>620&&line){lines.push(line);line=wd;}else line=tt;}if(line)lines.push(line);
    const L=lines.slice(-3);const top=h-60-L.length*34;
    ln(cx-330,top-8,cx-300,top-8,ACC,2);ln(cx+300,top-8,cx+330,top-8,ACC,2);
    L.forEach((l,i)=>tx(l,cx,top+24+i*34,26,'#F2FCFF','center',400,0.5));}
  o.restore();
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
function paint(){if(voiceActive(state)||state.obstacle||(state.marks&&state.marks.length))dirty=true;if(dirty){draw();dirty=false;}requestAnimationFrame(paint);}
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
const pad={lx:0,ly:0,rx:0,ry:0,active:false,holdA:0,holdB:0,sentA:false,sentB:false,clk:false,mode:false};

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
    // Manettes (mode 2) : gauche = monter/descendre + rotation ; droite = avancer/reculer + côtés.
    // A tenu 1,5 s = décoller, B tenu = atterrir ; X / Y (main gauche) = image plus petite / plus grande.
    pad.lx=0;pad.ly=0;pad.rx=0;pad.ry=0;let a=false,b=false;
    for(const src of session.inputSources){const gp=src.gamepad;if(!gp||gp.axes.length<4)continue;
      const x=gp.axes[2]||0, y=gp.axes[3]||0, b4=gp.buttons[4]&&gp.buttons[4].pressed, b5=gp.buttons[5]&&gp.buttons[5].pressed;
      if(src.handedness==='left'){pad.lx=x;pad.ly=y;
        const clk=gp.buttons[3]&&gp.buttons[3].pressed; if(clk&&!pad.clk)pad.mode=true; pad.clk=clk;
        if(b4){size=Math.max(0.6,size-0.01);setQuad();} if(b5){size=Math.min(1.5,size+0.01);setQuad();}}
      else{pad.rx=x;pad.ry=y;a=b4;b=b5;}}
    pad.active=Math.max(Math.abs(pad.lx),Math.abs(pad.ly),Math.abs(pad.rx),Math.abs(pad.ry))>0.12;
    pad.holdA=a?(pad.holdA||tm):0;pad.holdB=b?(pad.holdB||tm):0;
    let bt='';
    if(pad.holdA&&tm-pad.holdA>1500&&!pad.sentA){bt='takeoff';pad.sentA=true;} if(!a)pad.sentA=false;
    if(pad.holdB&&tm-pad.holdB>1500&&!pad.sentB){bt='land';pad.sentB=true;} if(!b)pad.sentB=false;
    if(pad.mode&&!bt){bt='mode';pad.mode=false;dirty=true;}
    if(pad.active||pad.holdA||pad.holdB)dirty=true;
    const pose=fr.getViewerPose(ref);if(!pose)return;
    const q=pose.transform.orientation;
    const fx=-2*(q.x*q.z+q.w*q.y), fy=-2*(q.y*q.z-q.w*q.x), fz=-(1-2*(q.x*q.x+q.y*q.y));
    const yaw=Math.atan2(fx,-fz)*180/Math.PI, pitch=Math.asin(Math.max(-1,Math.min(1,fy)))*180/Math.PI;
    if(tm-lastHead>50){lastHead=tm;
      fetch('/head?p='+pitch.toFixed(1)+'&y='+yaw.toFixed(1)+'&f='+(follow?1:0)+'&c='+(recenter?1:0)+
        '&lx='+pad.lx.toFixed(2)+'&ly='+pad.ly.toFixed(2)+'&rx='+pad.rx.toFixed(2)+'&ry='+pad.ry.toFixed(2)+(bt?'&bt='+bt:''),{cache:'no-store'})
        .then(r=>r.json()).then(s=>{state=s;dirty=true;}).catch(()=>{});
      recenter=false;}
    if(voiceActive(state)||state.obstacle||audioOn||(state.marks&&state.marks.length))dirty=true;
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
    voiceAn=out.createAnalyser();voiceAn.fftSize=256;voiceAn.smoothingTimeConstant=0.55;voiceAn.connect(out.destination);
    ws.onmessage=e=>{
      if(typeof e.data==='string'){if(e.data==='flush'){playing.forEach(s=>{try{s.stop()}catch(_){}});playing.clear();next=0;}return;}
      const pcm=new Int16Array(e.data);const buf=out.createBuffer(1,pcm.length,24000);const ch=buf.getChannelData(0);
      for(let i=0;i<pcm.length;i++)ch[i]=pcm[i]/32768;
      const src=out.createBufferSource();src.buffer=buf;src.connect(voiceAn||out.destination);
      next=Math.max(next,out.currentTime+0.05);src.start(next);next+=buf.duration;
      playing.add(src);src.onended=()=>playing.delete(src);
    };
    const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
    const mic=new AudioContext({sampleRate:16000});
    await mic.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET],{type:'application/javascript'})));
    const node=new AudioWorkletNode(mic,'mic-pcm');
    node.port.onmessage=({data})=>{if(ws.readyState===1)ws.send(data);};
    const micSrc=mic.createMediaStreamSource(stream);micSrc.connect(node);
    micAn=mic.createAnalyser();micAn.fftSize=256;micAn.smoothingTimeConstant=0.6;micSrc.connect(micAn);
    out.resume();mic.resume();
    audio={ws,out,mic,stream};audioOn=true;dirty=true;
    talk.textContent='🎙 Conversation dans le casque';talk.disabled=true;
    ws.onclose=()=>{stream.getTracks().forEach(t2=>t2.stop());mic.close();out.close();audio=null;audioOn=false;voiceAn=null;micAn=null;dirty=true;
      talk.textContent='🎙 Parler à l\'IA ici';talk.disabled=false;};
  }catch(e){note.textContent='Audio impossible : '+e.message;}
}
</script></body></html>
""".trimIndent()
}
