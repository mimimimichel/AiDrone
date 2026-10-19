package com.bebopia.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bebopia.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lastCrash = CrashReport.take(this)
        CrashReport.install(this)
        CrashReport.mark("lancement")
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // l'écran reste allumé en vol
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7))) {
                val vm: MainViewModel = viewModel()
                MainScreen(vm)
                var crash by remember { mutableStateOf(lastCrash) }
                crash?.let { report ->
                    val clipboard = LocalClipboardManager.current
                    AlertDialog(
                        onDismissRequest = { crash = null },
                        title = { Text("L'application a planté la dernière fois") },
                        text = {
                            Text(
                                "Copie ce rapport et envoie-le pour corriger le problème :\n\n$report",
                                fontSize = 11.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(report))
                                crash = null
                            }) { Text("Copier") }
                        },
                        dismissButton = { TextButton(onClick = { crash = null }) { Text("Fermer") } },
                    )
                }
            }
        }
    }
}
