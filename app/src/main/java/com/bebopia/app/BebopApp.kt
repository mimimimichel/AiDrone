package com.bebopia.app

import android.app.Application
import android.content.Context
import com.bebopia.app.dji.DjiLink

/** Application : charge le kit DJI au démarrage (sans effet sur le Bebop s'il échoue). */
class BebopApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DjiLink.install(this)
    }
}
