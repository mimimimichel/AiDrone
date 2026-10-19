package com.bebopia.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Garde la trace du dernier plantage pour l'afficher au lancement suivant
 * (et pouvoir la copier pour la transmettre).
 */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "dernier_plantage.txt")
    private var stepsFile: File? = null
    private val steps = ArrayDeque<String>()

    /** Note une étape (écrite tout de suite sur le disque : survit même à un plantage natif). */
    fun mark(step: String) {
        val f = stepsFile ?: return
        synchronized(steps) {
            steps.addLast("${System.currentTimeMillis() % 100_000} [${Thread.currentThread().name}] $step")
            while (steps.size > 25) steps.removeFirst()
            runCatching { f.writeText(steps.joinToString("\n")) }
        }
    }

    private fun lastSteps(ctx: Context): String {
        val f = File(ctx.filesDir, "dernieres_etapes.txt")
        val t = runCatching { f.readText() }.getOrNull().orEmpty()
        return if (t.isBlank()) "" else "\n\nDernières étapes :\n$t"
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        stepsFile = File(app.filesDir, "dernieres_etapes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = "Bebop IA ${BuildConfigInfo.version(app)} — Android ${android.os.Build.VERSION.RELEASE} " +
                    "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}) — fil « ${thread.name} »\n\n"
                file(app).writeText(info + sw.toString().take(12_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Le rapport du dernier plantage, puis l'efface. null s'il n'y en a pas. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        val fromSystem = runCatching { systemExit(ctx) }.getOrNull()
        val steps = lastSteps(ctx)
        if (!f.exists()) return fromSystem?.plus(steps)
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.plus(steps)
    }

    /**
     * Plantages que l'application ne peut pas attraper elle-même (code natif, caméra, blocage) :
     * Android garde la raison de la dernière fermeture (Android 11 et plus).
     */
    private fun systemExit(ctx: Context): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
        val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong("seen", 0L)) return null
        prefs.edit().putLong("seen", info.timestamp).apply()
        val kind = when (info.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH -> "plantage Java"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
            android.app.ApplicationExitInfo.REASON_ANR -> "application bloquée (ANR)"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "plus de mémoire"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "trop de ressources"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "échec au démarrage"
            else -> return null // fermeture normale
        }
        val traceText = runCatching {
            info.traceInputStream?.bufferedReader()?.use { r ->
                // Pour un plantage natif, la trace est binaire : on garde seulement les lignes lisibles.
                r.readText().lineSequence().filter { line -> line.isNotBlank() && line.all { it.code in 9..126 || it.code > 160 } }
                    .take(80).joinToString("\n")
            }
        }.getOrNull()
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return "Bebop IA $ver — Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n" +
            "Raison : $kind (${info.reason}, statut ${info.status})\n" +
            "Détail : ${info.description ?: "-"}\n\n" + (traceText?.take(10_000) ?: "(pas de trace)")
    }
}

private object BuildConfigInfo {
    fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
