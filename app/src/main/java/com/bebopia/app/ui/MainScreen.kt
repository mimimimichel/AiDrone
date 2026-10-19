package com.bebopia.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bebopia.app.MainViewModel
import com.bebopia.app.Mode
import com.bebopia.app.UiState
import com.bebopia.app.core.ai.ChatLine
import com.bebopia.app.core.ai.Role

private val Bg = Color(0xFF0E1216)
private val Panel = Color(0xFF1A2027)
private val Accent = Color(0xFF4FC3F7)
private val Danger = Color(0xFFD32F2F)
private val DarkDanger = Color(0xFF4A1416)
private val Ok = Color(0xFF66BB6A)
private val Muted = Color(0xFF8A96A3)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.micPressed() else vm.sysMessage("Autorise le micro pour parler au drone.")
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startSimulation() else vm.sysMessage("Autorise la caméra pour la simulation.")
    }

    fun sendInput() {
        val t = input.trim()
        if (t.isNotEmpty()) {
            vm.send(t)
            input = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .padding(horizontal = 12.dp),
    ) {
        // Barre du haut
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bebop IA", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            }
            Text(" v$version", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            val (label, color) = when (s.mode) {
                Mode.BEBOP -> (if (s.linkUp) "Bebop connecté" else "Liaison perdue") to (if (s.linkUp) Ok else Danger)
                Mode.SIM -> "Simulation" to Accent
                Mode.CONNECTING -> "Connexion…" to Muted
                Mode.NONE -> "Déconnecté" to Muted
            }
            Text(label, color = color, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (s.mode == Mode.BEBOP || s.mode == Mode.SIM) {
                TextButton(onClick = { vm.disconnect() }) { Text("Déconnecter", fontSize = 13.sp) }
            }
            TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 22.sp, color = Color.White) }
        }

        // Vidéo
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            when (s.mode) {
                Mode.BEBOP -> BebopVideoView(vm)
                Mode.SIM -> CameraPreview(vm)
                else -> ConnectPanel(
                    connecting = s.mode == Mode.CONNECTING,
                    onConnect = { vm.connectBebop() },
                    onSimulate = {
                        if (granted(Manifest.permission.CAMERA)) vm.startSimulation()
                        else camPermission.launch(Manifest.permission.CAMERA)
                    },
                )
            }
            if (s.mode == Mode.BEBOP || s.mode == Mode.SIM) {
                Text(
                    statusLabel(s),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (s.obstacle) {
                    Text(
                        "⚠ OBSTACLE : arrêt",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Danger, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                } else if (s.guardWatching) {
                    Text(
                        "🛡",
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (s.mode == Mode.BEBOP && !s.hasVideo) {
                    Text("En attente de la vidéo…", color = Muted, modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        // Télémétrie
        Text(
            telemetry(s),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // Proposition en attente
        s.pending?.let { proposal ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Je propose : $proposal", color = Color.White, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.approve() }, colors = ButtonDefaults.buttonColors(containerColor = Ok)) { Text("Oui") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { vm.reject() }) { Text("Non") }
                }
            }
        }

        // Conversation
        val listState = rememberLazyListState()
        LaunchedEffect(s.chat.size) {
            if (s.chat.isNotEmpty()) listState.animateScrollToItem(s.chat.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(s.chat) { ChatBubble(it) }
        }

        // Saisie + micro
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Écris au drone…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendInput() }),
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { sendInput() }, modifier = Modifier.height(52.dp)) { Text("➤") }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (granted(Manifest.permission.RECORD_AUDIO)) vm.micPressed()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (s.listening) Danger else Accent),
                modifier = Modifier.size(width = 64.dp, height = 52.dp),
            ) { Text(if (s.listening) "■" else "🎤", fontSize = 18.sp) }
        }

        // Commandes
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { vm.toggleAi() }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text(if (s.paused) "▶ IA" else "⏸ Pause IA", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { vm.takeoff() }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Décoller")
            }
            Button(
                onClick = { vm.land() },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.weight(1.3f).height(52.dp),
            ) { Text("ATTERRIR", fontWeight = FontWeight.Bold) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkDanger)
                .combinedClickable(
                    onClick = { vm.sysMessage("Arrêt d'urgence : fais un appui long pour couper les moteurs (le drone tombe).") },
                    onLongClick = { vm.emergency() },
                )
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Arrêt d'urgence (appui long) : coupe les moteurs", color = Color(0xFFFFCDD2), fontSize = 13.sp)
        }
    }

    if (showSettings) SettingsDialog(vm, s) { showSettings = false }
}

private fun statusLabel(s: UiState): String {
    if (s.pending != null && s.voice.isNotEmpty()) return "Attend ton accord : dis oui ou non"
    val base = when {
        s.voice.isNotEmpty() -> s.voice
        s.paused -> "PAUSE"
        s.thinking -> "Réflexion…"
        s.pending != null -> "Attend ton accord"
        s.idle -> "Attend une instruction"
        else -> "IA active"
    }
    return if (s.thinking && s.listening) "$base (réflexion)" else base
}

private fun telemetry(s: UiState): String {
    if (s.mode != Mode.BEBOP && s.mode != Mode.SIM) return "Réseau : ${s.network}"
    val bat = s.battery?.let { "$it %" } ?: "?"
    return "🔋 $bat   ⬆ %.1f m   ↔ %.1f m   • %s   • étape %d/%d   • %s".format(
        s.altitude, s.distance, s.droneState, s.steps, s.maxSteps, s.network,
    )
}

@Composable
private fun ChatBubble(line: ChatLine) {
    when (line.role) {
        Role.SYSTEM -> Text(
            line.text,
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Role.PILOT, Role.DRONE -> {
            val pilot = line.role == Role.PILOT
            Box(Modifier.fillMaxWidth(), contentAlignment = if (pilot) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(
                    (if (pilot) "" else "🚁 ") + line.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .background(if (pilot) Color(0xFF1E4F6B) else Color(0xFF2E3B2F), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectPanel(connecting: Boolean, onConnect: () -> Unit, onSimulate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (connecting) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(8.dp))
            Text("Connexion au Bebop…", color = Color.White)
        } else {
            Text("Drone non connecté", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Wi-Fi du téléphone sur le Bebop, Skycontroller éteinte, données mobiles activées.",
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text("Connecter le Bebop") }
                OutlinedButton(onClick = onSimulate) { Text("Simulation") }
            }
        }
    }
}

@Composable
private fun BebopVideoView(vm: MainViewModel) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(st).also { vm.onSurface(it) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        vm.onSurface(null)
                        surface?.release()
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                vm.textureView = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) {
        onDispose { vm.textureView = null }
    }
}

@Composable
private fun CameraPreview(vm: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    AndroidView(
        factory = { ctx ->
            com.bebopia.app.CrashReport.mark("caméra : création de l'aperçu")
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                vm.previewView = this
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        com.bebopia.app.CrashReport.mark("caméra : fournisseur prêt")
                        val provider = future.get()
                        providerHolder[0] = provider
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(surfaceProvider)
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        com.bebopia.app.CrashReport.mark("caméra : ouverte")
                    } catch (e: Throwable) {
                        vm.sysMessage("Caméra indisponible : ${e.javaClass.simpleName} ${e.message ?: ""}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) {
        onDispose {
            vm.previewView = null
            providerHolder[0]?.unbindAll()
        }
    }
}

@Composable
private fun SettingsDialog(vm: MainViewModel, s: UiState, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var gemini by remember { mutableStateOf("") }
    var bargeIn by remember { mutableStateOf(s.bargeIn) }
    var maxStep by remember { mutableStateOf(s.maxStep.toFloat()) }
    var guardOn by remember { mutableStateOf(s.guardOn) }
    var robotVoice by remember { mutableStateOf(s.robotVoice) }
    var model by remember { mutableStateOf(vm.model) }
    var confirm by remember { mutableStateOf(s.confirm) }
    var speak by remember { mutableStateOf(s.speak) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Réglages") },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(key, gemini, model, confirm, speak, bargeIn, Math.round(maxStep * 10) / 10.0, guardOn, robotVoice)
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = gemini,
                    onValueChange = { gemini = it },
                    label = { Text(if (s.hasGeminiKey) "Clé Gemini (déjà enregistrée)" else "Clé Gemini") },
                    placeholder = { Text("AIza…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Gratuite sur aistudio.google.com/apikey (la même que pour Vr2). Avec elle, tu discutes en direct avec le drone.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                SettingSwitch("Je peux couper la parole au drone (désactive si le drone s'interrompt tout seul)", bargeIn) { bargeIn = it }
                Spacer(Modifier.height(12.dp))
                Text("Optionnel : Claude à la place de Gemini (clé payante)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (s.hasClaudeKey) "Clé API Claude (déjà enregistrée)" else "Clé API Claude") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Modèle Claude") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingSwitch("Valider chaque déplacement (conseillé)", confirm) { confirm = it }
                Text(
                    "Distance max par pas : %.1f m".format(Math.round(maxStep * 10) / 10.0),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = maxStep,
                    onValueChange = { maxStep = it },
                    valueRange = 0.2f..1.0f,
                    steps = 7,
                )
                SettingSwitch("Détecteur d'obstacles (arrête le drone s'il va toucher quelque chose devant)", guardOn) { guardOn = it }
                Text(
                    "Le détecteur regarde la vidéo quand le drone avance. Il ne voit pas sur les côtés, " +
                        "ni les vitres ou les murs blancs unis, et se met en veille si la caméra regarde vers le sol.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch("Le drone parle à voix haute", speak) { speak = it }
                SettingSwitch("Voix de robot", robotVoice) { robotVoice = it }
                Text(
                    "Limites de sécurité : plafond 3 m, rayon 15 m, 90° max par action, 40 actions max, " +
                        "atterrissage automatique sous 25 % de batterie. Dis « stop » ou « atterris » à tout moment.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
