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

    fun install(ctx: Context) {
        val app = ctx.applicationContext
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
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }
}

private object BuildConfigInfo {
    fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
