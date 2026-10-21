package com.bebopia.app

import android.app.Application
import android.content.Context
import com.bebopia.app.dji.DjiBoot
import java.io.File

/** Application : charge le kit DJI au démarrage (sans effet sur le Bebop s'il échoue). */
class BebopApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installCrashLog(base)
        // Ne jamais toucher aux classes DJI avant cette ligne (voir DjiBoot).
        DjiBoot.install(this)
        installCrashLog(base) // le kit DJI peut remplacer le gestionnaire : on se remet devant
    }

    /** En cas de plantage, la cause est écrite dans un fichier et affichée au lancement suivant. */
    private fun installCrashLog(base: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLog) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLog(File(base.filesDir, "crash.txt"), prev))
    }

    private class CrashLog(val file: File, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                file.writeText("Version ${BuildConfig.VERSION_NAME}, fil « ${t.name} »\n" + android.util.Log.getStackTraceString(e).take(4000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        /** Cause du dernier plantage (puis efface le fichier), null s'il n'y en a pas. */
        fun takeLastCrash(context: Context): String? {
            val f = File(context.filesDir, "crash.txt")
            if (!f.exists()) return null
            val s = runCatching { f.readText() }.getOrNull()
            f.delete()
            return s
        }
    }
}
