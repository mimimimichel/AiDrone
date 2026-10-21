package com.bebopia.app

import android.app.Application
import android.content.Context
import com.bebopia.app.dji.DjiBoot

/** Application : charge le kit DJI au démarrage (sans effet sur le Bebop s'il échoue). */
class BebopApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Ne jamais toucher aux classes DJI avant cette ligne (voir DjiBoot).
        DjiBoot.install(this)
    }
}
