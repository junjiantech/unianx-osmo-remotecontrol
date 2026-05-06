package com.unianx.osmo.remotecontrol

import android.app.Application
import com.unianx.osmo.remotecontrol.logging.AppLogger

class RemoteControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
    }
}
