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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.launch
import com.bebopia.app.Mode
import com.bebopia.app.UiState
import com.bebopia.app.core.ai.ChatLine
import com.bebopia.app.core.ai.Plan
import com.bebopia.app.core.ai.StepState
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
    var showDiag by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.micPressed() else vm.sysMessage("Autorise le micro pour parler au drone.")
    }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        vm.connectMavic()
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
                Mode.MAVIC -> (if (s.linkUp) "Mavic connecté" else "Liaison perdue") to (if (s.linkUp) Ok else Danger)
                Mode.SIM -> "Simulation" to Accent
                Mode.CONNECTING -> "Connexion…" to Muted
                Mode.NONE -> "Déconnecté" to Muted
            }
            Text(label, color = color, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (s.mode == Mode.BEBOP || s.mode == Mode.MAVIC || s.mode == Mode.SIM) {
                TextButton(onClick = { vm.disconnect() }) { Text("Déconnecter", fontSize = 13.sp) }
            }
            // Mode confiance (🤝) ou prudent (✋) : un appui pour changer
            TextButton(onClick = { vm.setTrust(s.confirm) }) {
                Text(if (s.confirm) "✋" else "🤝", fontSize = 20.sp)
            }
            if (s.mode == Mode.MAVIC) TextButton(onClick = { showDiag = true }) { Text("🔧", fontSize = 20.sp) }
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
                Mode.BEBOP, Mode.MAVIC -> BebopVideoView(vm)
                Mode.SIM -> CameraPreview(vm)
                else -> ConnectPanel(
                    connecting = s.mode == Mode.CONNECTING,
                    onConnect = { vm.connectBebop() },
                    onMavic = {
                        // Le kit DJI a besoin de la localisation pour s'enregistrer.
                        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) vm.connectMavic()
                        else locPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    onSimulate = {
                        if (granted(Manifest.permission.CAMERA)) vm.startSimulation()
                        else camPermission.launch(Manifest.permission.CAMERA)
                    },
                )
            }
            if (s.mode == Mode.BEBOP || s.mode == Mode.MAVIC || s.mode == Mode.SIM) {
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
                    // Ce que mesure le détecteur : temps avant contact avec ce qui approche devant.
                    val ttc = s.guardTtc
                    Text(
                        if (ttc == null || ttc > 9.9) "🛡 rien n'approche" else "🛡 contact dans %.1f s".format(ttc),
                        color = if (ttc != null && ttc < 3.0) Color(0xFFFFCC80) else Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if ((s.mode == Mode.BEBOP || s.mode == Mode.MAVIC) && !s.hasVideo) {
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

        // Plan de l'IA pour l'objectif donné
        s.activity?.let { act ->
            Text(
                (if (s.recording) "🔴 " else "") + "⏳ " + act + " — dis « stop » pour arrêter",
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        s.plan?.let { PlanCard(it, vm) }

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
    if (showDiag) MavicDiagDialog(vm) { showDiag = false }
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
    if (s.mode != Mode.BEBOP && s.mode != Mode.MAVIC && s.mode != Mode.SIM) return "Réseau : ${s.network}"
    // La batterie est passée en paramètre, jamais collée dans le modèle : son « % » ferait planter format().
    val bat = s.battery?.let { "$it %" } ?: "?"
    val casque = if (s.headsetViewers > 0) "   • 🥽 casque" else ""
    return "🔋 %s   ⬆ %.1f m   ↔ %.1f m   • %s   • étape %d/%d   • %s".format(
        bat, s.altitude, s.distance, s.droneState, s.steps, s.maxSteps, s.network,
    ) + casque
}

@Composable
private fun PlanCard(plan: Plan, vm: MainViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A33)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            val title = when {
                plan.finished -> if (plan.success) "✅ Objectif atteint" else "❌ Objectif non atteint"
                plan.approved -> "▶ Plan en cours"
                else -> "📋 Plan proposé"
            }
            Text("$title : ${plan.goal}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Column(
                Modifier
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
            ) {
                plan.steps.forEachIndexed { i, step ->
                    val icon = when (step.state) {
                        StepState.TODO -> "⬜"
                        StepState.DOING -> "▶"
                        StepState.DONE -> "✅"
                        StepState.FAILED -> "❌"
                        StepState.SKIPPED -> "⏭"
                    }
                    val color = if (step.state == StepState.DOING) Color(0xFF4FC3F7) else Color(0xFFCFD8DC)
                    Text("$icon ${i + 1}. ${step.text}", color = color, fontSize = 13.sp)
                    if (step.note.isNotBlank()) {
                        Text("     ${step.note}", color = Muted, fontSize = 11.sp)
                    }
                }
                if (plan.finished && plan.summary.isNotBlank()) {
                    Text(plan.summary, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    plan.finished -> OutlinedButton(onClick = { vm.clearPlan() }) { Text("Fermer") }
                    plan.approved -> Button(
                        onClick = { vm.cancelPlan() },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    ) { Text("Arrêter le plan") }
                    else -> {
                        Button(onClick = { vm.approvePlan() }, colors = ButtonDefaults.buttonColors(containerColor = Ok)) {
                            Text("Lancer le plan")
                        }
                        OutlinedButton(onClick = { vm.rejectPlan() }) { Text("Refuser") }
                    }
                }
            }
        }
    }
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
private fun ConnectPanel(connecting: Boolean, onConnect: () -> Unit, onMavic: () -> Unit, onSimulate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (connecting) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(8.dp))
            Text("Connexion au drone…", color = Color.White)
        } else {
            Text("Drone non connecté", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Bebop : Wi-Fi du téléphone sur le drone, Skycontroller éteinte. " +
                    "Mavic Mini : radiocommande branchée au téléphone en USB, DJI Fly fermée.",
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text("Bebop") }
                Button(onClick = onMavic) { Text("Mavic Mini") }
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
                        if (vm.state.value.mode == Mode.MAVIC) {
                            // Le kit DJI décode directement dans la texture.
                            vm.onSurfaceTexture(st, width, height)
                        } else {
                            surface = Surface(st).also { vm.onSurface(it) }
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        vm.onSurface(null)
                        vm.onSurfaceTexture(null, 0, 0)
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
    var ceiling by remember { mutableStateOf(s.ceiling.toFloat()) }
    var radius by remember { mutableStateOf(s.radius.toFloat()) }
    var model by remember { mutableStateOf(vm.model) }
    var confirm by remember { mutableStateOf(s.confirm) }
    var speak by remember { mutableStateOf(s.speak) }
    var showJournal by remember { mutableStateOf(false) }
    if (showJournal) JournalDialog(vm) { showJournal = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Réglages") },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(key, gemini, model, confirm, speak, bargeIn, Math.round(maxStep * 10) / 10.0, guardOn, robotVoice, Math.round(ceiling).toDouble(), (Math.round(radius / 5f) * 5).toDouble())
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
                SettingSwitch("🤝 Mode confiance : l'IA agit sans me demander (déplacements et plans)", !confirm) { confirm = !it }
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
                Text("Plafond : %d m".format(Math.round(ceiling)), modifier = Modifier.padding(top = 12.dp))
                Slider(value = ceiling, onValueChange = { ceiling = it }, valueRange = 2f..120f)
                Text("Rayon autour du décollage : %d m".format(Math.round(radius / 5f) * 5))
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 5f..500f)
                Text(
                    "En intérieur, garde 3 m et 15 m. Sur un terrain : 20 à 50 m de plafond, 50 à 300 m de rayon. " +
                        "120 m est la limite légale ; garde toujours le drone en vue, loin des gens et des lignes électriques. " +
                        "Le Wi-Fi du Bebop porte environ 100 à 300 m avec un téléphone.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch("Détecteur d'obstacles (arrête le drone s'il va toucher quelque chose devant)", guardOn) { guardOn = it }
                Text(
                    "Le détecteur regarde la vidéo quand le drone avance. Il ne voit pas sur les côtés, " +
                        "ni les vitres ou les murs blancs unis, et se met en veille si la caméra regarde vers le sol.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch("Vue dans le casque (Meta Quest)", s.headsetOn) { vm.setHeadset(it) }
                if (s.headsetOn) {
                    Text(
                        if (s.headsetUrl.isEmpty()) "Connecte le téléphone au même Wi-Fi que le casque."
                        else "Dans le navigateur du casque : ${s.headsetUrl}" +
                            if (s.headsetViewers > 0) " (${s.headsetViewers} connecté)" else "",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                SettingSwitch("Le drone parle à voix haute", speak) { speak = it }
                SettingSwitch("Voix de robot", robotVoice) { robotVoice = it }
                SettingSwitch("🎬 Filmer chaque vol en entier (carte mémoire du drone)", s.autoRecord) { vm.changeAutoRecord(it) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📔 Journal des vols (résumés et photos)", modifier = Modifier.weight(1f))
                    TextButton(onClick = { showJournal = true }) { Text("Ouvrir") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠 Mémoire de l'IA : ${s.memoryCount} souvenir${if (s.memoryCount > 1) "s" else ""}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearMemory() }, enabled = s.memoryCount > 0) { Text("Effacer") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("📍 Points marqués sur la carte : ${s.waypointCount}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearWaypoints() }) { Text("Effacer") }
                }
                Text(
                    "Autres limites : 90° max par action, 40 actions max, " +
                        "atterrissage automatique sous 25 % de batterie. Dis « stop » ou « atterris » à tout moment.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

/** Diagnostic du Mavic : pourquoi il ne décolle pas, et les déblocages possibles (comme dans DJI Fly). */
@Composable
private fun MavicDiagDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<com.bebopia.app.MavicReport?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.mavicReport() }
    }
    // Rafraîchi toutes les 2 s (suivi d'un étalonnage, capteurs qui s'activent…)
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); if (busy == null) tick++ } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun act(kind: String) {
        busy = kind
        scope.launch {
            last = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.mavicAction(kind) }
            busy = null
            tick++
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔧 Diagnostic du Mavic") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            val r = report
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r == null) {
                    CircularProgressIndicator()
                    return@Column
                }
                r.checks.forEach { c ->
                    val (icon, col) = when (c.state) {
                        "ok" -> "✅" to Color(0xFF7CFC9A)
                        "attention" -> "⚠️" to Color(0xFFFFD54F)
                        else -> "⛔" to Color(0xFFFF6E6E)
                    }
                    Text("$icon ${c.title}", fontWeight = FontWeight.Bold, color = col, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(c.detail, fontSize = 13.sp)
                }
                if (r.compassCal != "NOT_CALIBRATING" && r.compassCal != "UNKNOWN") {
                    Text(
                        when (r.compassCal) {
                            "HORIZONTAL" -> "🧭 Étape 1 : drone à plat, tourne sur toi-même d'un tour complet"
                            "VERTICAL" -> "🧭 Étape 2 : drone nez vers le bas, tourne sur toi-même d'un tour complet"
                            "SUCCESSFUL" -> "🧭 Boussole étalonnée ✅"
                            "FAILED" -> "🧭 Étalonnage raté : éloigne-toi du métal (et des téléphones, clés, voitures) et recommence"
                            else -> "🧭 Étalonnage : ${r.compassCal}"
                        },
                        fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7), modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (r.messages.isNotEmpty()) {
                    Text("Messages du drone", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    if (r.explained != null) Text(r.explained, fontSize = 13.sp)
                    else r.messages.forEach { (reason, sol) -> Text("• $reason" + if (sol.isNotBlank()) " → $sol" else "", fontSize = 13.sp) }
                }
                Text("Déblocages", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                if (busy != null) Text("⏳ En cours…", fontSize = 13.sp)
                if (last.isNotBlank()) Text(last, fontSize = 13.sp, color = Color(0xFF4FC3F7))
                if (r.visionOn == false) OutlinedButton(onClick = { act("vision_on") }, enabled = busy == null) { Text("Activer les capteurs du dessous") }
                when (r.noGpsAllowed) {
                    false -> {
                        OutlinedButton(onClick = { act("nogps_allow") }, enabled = busy == null) { Text("Autoriser le décollage sans GPS") }
                        Text("Utile en intérieur. Sans GPS, il faut que les capteurs du dessous marchent, sinon le drone dérive.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    true -> OutlinedButton(onClick = { act("nogps_lock") }, enabled = busy == null) { Text("Reverrouiller le décollage sans GPS") }
                    null -> {}
                }
                if (r.compassCal == "HORIZONTAL" || r.compassCal == "VERTICAL") {
                    OutlinedButton(onClick = { act("compass_stop") }, enabled = busy == null) { Text("Arrêter l'étalonnage de la boussole") }
                } else {
                    OutlinedButton(onClick = { act("compass") }, enabled = busy == null) { Text("Étalonner la boussole") }
                }
                OutlinedButton(onClick = { act("imu") }, enabled = busy == null) { Text("Étalonner la centrale inertielle (5-10 min)") }
                OutlinedButton(onClick = { act("reboot") }, enabled = busy == null) { Text("Redémarrer le contrôleur de vol") }
                Text("Étalonnages et redémarrage : drone posé, moteurs arrêtés.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        },
    )
}

/** Les vols enregistrés : titre, résumé par l'IA, photos. */
@Composable
private fun JournalDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var flights by remember { mutableStateOf<List<com.bebopia.app.core.ai.FlightEntry>?>(null) }
    LaunchedEffect(Unit) {
        flights = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { vm.flights() }.getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📔 Journal des vols") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            val list = flights
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("Aucun vol enregistré pour l'instant. Chaque vol (du décollage à l'atterrissage) est ajouté ici, avec un résumé de l'IA.")
                else -> LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(list) { f ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(f.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(f.summary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                            if (f.photos.isNotEmpty()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                                    f.photos.take(12).forEach { file ->
                                        val bmp = remember(file) {
                                            runCatching {
                                                android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                                            }.getOrNull()
                                        }
                                        bmp?.let {
                                            Image(it.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Crop,
                                                modifier = Modifier.padding(end = 4.dp).size(96.dp, 54.dp).clip(RoundedCornerShape(4.dp)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
