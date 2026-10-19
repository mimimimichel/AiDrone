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
<title>Bebop IA – vue du drone</title>
<style>
  html,body{margin:0;height:100%;background:#000;color:#fff;font-family:system-ui,sans-serif;overflow:hidden}
  #v{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
  .bar{position:absolute;left:0;right:0;padding:10px 16px;background:linear-gradient(#000c,#0000);font-size:20px}
  #top{top:0}
  #say{position:absolute;left:5%;right:5%;bottom:90px;text-align:center;font-size:24px;text-shadow:0 2px 6px #000}
  #alert{position:absolute;top:45%;left:0;right:0;text-align:center;font-size:40px;font-weight:bold;display:none}
  #alert span{background:#d32f2f;padding:10px 24px;border-radius:12px}
  #btns{position:absolute;right:16px;bottom:16px;display:flex;gap:10px;align-items:center}
  button{font-size:20px;padding:12px 18px;border-radius:10px;border:0;background:#4fc3f7;color:#000}
  #vr{background:#66bb6a;font-weight:bold}
  #note{font-size:15px;max-width:520px;text-align:right;color:#cfd8dc}
  canvas{display:none}
</style></head><body>
<img id="v" src="/stream" alt="vidéo du drone">
<div class="bar" id="top">chargement…</div>
<div id="alert"><span>⚠ OBSTACLE</span></div>
<div id="say"></div>
<div id="btns"><span id="note"></span>
  <button onclick="document.documentElement.requestFullscreen&&document.documentElement.requestFullscreen()">Plein écran</button>
  <button id="talk">🎙 Parler à l'IA ici</button>
  <button id="vr" style="display:none">🥽 Entrer en VR</button></div>
<canvas id="gl"></canvas>
<script>
const v=document.getElementById('v');
v.onerror=()=>setTimeout(()=>{v.src='/stream?'+Date.now()},1000);
let state={};
function show(s){state=s;
  document.getElementById('top').textContent=s.top||'';
  document.getElementById('say').textContent=s.say||'';
  document.getElementById('alert').style.display=s.obstacle?'block':'none';}
let inVR=false;
async function poll(){
  if(!inVR){try{show(await (await fetch('/state',{cache:'no-store'})).json())}catch(e){document.getElementById('top').textContent='téléphone injoignable…'}}
  setTimeout(poll,500);
}
poll();

// ---------- Réalité virtuelle : la vidéo suit le regard, la nacelle suit la tête ----------
const note=document.getElementById('note'), vrBtn=document.getElementById('vr');
if(!window.isSecureContext){
  note.textContent='Pour la VR et le suivi de la tête : ouvre l\'adresse https://…:8443 (accepte l\'avertissement de sécurité).';
}else if(navigator.xr){
  navigator.xr.isSessionSupported('immersive-vr').then(ok=>{if(ok)vrBtn.style.display='inline-block';else note.textContent='VR non disponible dans ce navigateur.'});
}
vrBtn.onclick=()=>{startAudio();startVR();};
document.getElementById('talk').onclick=startAudio;

// ---------- Audio : le micro du casque parle à l'IA, sa voix sort dans le casque ----------
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
    const ws=new WebSocket('wss://'+location.host+'/audio'); ws.binaryType='arraybuffer';
    const out=new AudioContext({sampleRate:24000}); let next=0; const playing=new Set();
    ws.onmessage=e=>{
      if(typeof e.data==='string'){ if(e.data==='flush'){playing.forEach(s=>{try{s.stop()}catch(_){}});playing.clear();next=0;} return; }
      const pcm=new Int16Array(e.data); const buf=out.createBuffer(1,pcm.length,24000); const ch=buf.getChannelData(0);
      for(let i=0;i<pcm.length;i++)ch[i]=pcm[i]/32768;
      const src=out.createBufferSource(); src.buffer=buf; src.connect(out.destination);
      next=Math.max(next,out.currentTime+0.05); src.start(next); next+=buf.duration;
      playing.add(src); src.onended=()=>playing.delete(src);
    };
    const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
    const mic=new AudioContext({sampleRate:16000});
    await mic.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET],{type:'application/javascript'})));
    const node=new AudioWorkletNode(mic,'mic-pcm');
    node.port.onmessage=({data})=>{ if(ws.readyState===1) ws.send(data); };
    mic.createMediaStreamSource(stream).connect(node);
    out.resume(); mic.resume();
    audio={ws,out,mic,stream};
    talk.textContent='🎙 Conversation dans le casque'; talk.disabled=true;
    ws.onclose=()=>{ stream.getTracks().forEach(t=>t.stop()); mic.close(); out.close(); audio=null;
      talk.textContent='🎙 Parler à l\'IA ici'; talk.disabled=false; };
  }catch(e){ note.textContent='Audio impossible : '+e.message; }
}

const comp=document.createElement('canvas'); comp.width=1280; comp.height=780;
const cx=comp.getContext('2d');
let frame=null, follow=true, recenter=true, lastHead=0, needUpload=true;

async function frameLoop(){
  while(inVR){
    try{
      const r=await fetch('/frame?'+Date.now(),{cache:'no-store'});
      if(r.ok){const b=await r.blob(); const img=await createImageBitmap(b); if(frame&&frame.close)frame.close(); frame=img; needUpload=true;}
    }catch(e){}
    await new Promise(r=>setTimeout(r,70));
  }
}
function compose(){
  cx.fillStyle='#000'; cx.fillRect(0,0,1280,780);
  if(frame) cx.drawImage(frame,0,30,1280,720);
  cx.fillStyle='rgba(0,0,0,.6)'; cx.fillRect(0,0,1280,46);
  cx.fillStyle='#fff'; cx.font='26px sans-serif'; cx.textAlign='left';
  cx.fillText((state.top||'')+(follow?'   ↻ le drone suit la tête':''),14,32);
  if(state.say){cx.font='30px sans-serif'; cx.textAlign='center'; cx.lineWidth=6; cx.strokeStyle='#000';
    cx.strokeText(state.say.slice(0,90),640,730); cx.fillText(state.say.slice(0,90),640,730);}
  if(state.obstacle){cx.fillStyle='#d32f2f'; cx.fillRect(390,340,500,80); cx.fillStyle='#fff'; cx.font='bold 48px sans-serif'; cx.textAlign='center'; cx.fillText('⚠ OBSTACLE',640,398);}
}

async function startVR(){
  const canvas=document.getElementById('gl');
  const gl=canvas.getContext('webgl',{xrCompatible:true});
  const session=await navigator.xr.requestSession('immersive-vr',{optionalFeatures:['local']});
  inVR=true; frameLoop();
  await gl.makeXRCompatible();
  session.updateRenderState({baseLayer:new XRWebGLLayer(session,gl)});
  const ref=await session.requestReferenceSpace('local');
  // Shader : un écran fixé devant les yeux (il suit le regard, comme une vue FPV)
  const vs='attribute vec3 p;attribute vec2 t;uniform mat4 P;varying vec2 u;void main(){u=t;gl_Position=P*vec4(p,1.0);}';
  const fs='precision mediump float;varying vec2 u;uniform sampler2D s;void main(){gl_FragColor=texture2D(s,u);}';
  function sh(type,src){const s=gl.createShader(type);gl.shaderSource(s,src);gl.compileShader(s);return s;}
  const pr=gl.createProgram();gl.attachShader(pr,sh(gl.VERTEX_SHADER,vs));gl.attachShader(pr,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(pr);
  const W=1.7,H=W*780/1280,Z=-2.2;
  const buf=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buf);
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-W,-H,Z,0,1, W,-H,Z,1,1, -W,H,Z,0,0, W,H,Z,1,0]),gl.STATIC_DRAW);
  const tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  const aP=gl.getAttribLocation(pr,'p'),aT=gl.getAttribLocation(pr,'t'),uP=gl.getUniformLocation(pr,'P');
  session.addEventListener('select',()=>{recenter=true;});
  session.addEventListener('squeeze',()=>{follow=!follow;recenter=true;});
  session.addEventListener('end',()=>{inVR=false;fetch('/head?p=0&y=0&f=0').catch(()=>{});});
  function onFrame(t,fr){
    session.requestAnimationFrame(onFrame);
    const pose=fr.getViewerPose(ref); if(!pose)return;
    // Orientation de la tête : direction du regard -> cap (droite > 0) et inclinaison (haut > 0)
    const q=pose.transform.orientation;
    const fx=-2*(q.x*q.z+q.w*q.y), fy=-2*(q.y*q.z-q.w*q.x), fz=-(1-2*(q.x*q.x+q.y*q.y));
    const yaw=Math.atan2(fx,-fz)*180/Math.PI, pitch=Math.asin(Math.max(-1,Math.min(1,fy)))*180/Math.PI;
    if(t-lastHead>50){lastHead=t;
      fetch('/head?p='+pitch.toFixed(1)+'&y='+yaw.toFixed(1)+'&f='+(follow?1:0)+'&c='+(recenter?1:0),{cache:'no-store'})
        .then(r=>r.json()).then(s=>{state=s;needUpload=true;}).catch(()=>{});
      recenter=false;}
    if(needUpload){compose();gl.bindTexture(gl.TEXTURE_2D,tex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,comp);needUpload=false;}
    const layer=session.renderState.baseLayer;
    gl.bindFramebuffer(gl.FRAMEBUFFER,layer.framebuffer);
    gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
    gl.useProgram(pr);gl.bindBuffer(gl.ARRAY_BUFFER,buf);
    gl.enableVertexAttribArray(aP);gl.vertexAttribPointer(aP,3,gl.FLOAT,false,20,0);
    gl.enableVertexAttribArray(aT);gl.vertexAttribPointer(aT,2,gl.FLOAT,false,20,12);
    gl.bindTexture(gl.TEXTURE_2D,tex);
    for(const view of pose.views){
      const vp=layer.getViewport(view);gl.viewport(vp.x,vp.y,vp.width,vp.height);
      gl.uniformMatrix4fv(uP,false,view.projectionMatrix);
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    }
  }
  session.requestAnimationFrame(onFrame);
}
</script></body></html>
""".trimIndent()
}
