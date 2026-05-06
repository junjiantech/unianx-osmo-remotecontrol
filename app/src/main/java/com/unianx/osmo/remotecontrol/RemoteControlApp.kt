package com.unianx.osmo.remotecontrol

import android.app.Application
import com.unianx.osmo.remotecontrol.logging.AppLogger

class RemoteControlApp : Application() {
    val remoteControlController: RemoteControlController by lazy {
        RemoteControlController(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
    }
}
