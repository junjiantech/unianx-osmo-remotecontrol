package com.unianx.osmo.remotecontrol

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.unianx.osmo.remotecontrol.data.AppSettingsStore
import com.unianx.osmo.remotecontrol.logging.AppLogger
import kotlinx.coroutines.runBlocking

class RemoteControlApp : Application() {
    val remoteControlController: RemoteControlController by lazy {
        RemoteControlController(this)
    }

    override fun onCreate() {
        super.onCreate()
        val themeMode = runBlocking {
            AppSettingsStore(this@RemoteControlApp).loadThemeMode()
        }
        AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
        AppLogger.initialize(this)
    }
}
