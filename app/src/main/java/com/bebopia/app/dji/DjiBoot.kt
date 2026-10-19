package com.bebopia.app.dji

import android.app.Application
import android.util.Log

/**
 * Démarrage du kit DJI. Ce fichier ne doit utiliser AUCUNE classe DJI (à part le Helper) :
 * elles n'existent qu'après Helper.install, et Android plante s'il les rencontre avant.
 */
object DjiBoot {
    @Volatile var ready = false
        private set
    @Volatile var error: String? = null
        private set

    fun install(app: Application) {
        try {
            com.cySdkyc.clx.Helper.install(app)
            ready = true
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName} ${t.message ?: ""}"
            Log.e("DjiBoot", "kit DJI non chargé", t)
        }
    }
}
