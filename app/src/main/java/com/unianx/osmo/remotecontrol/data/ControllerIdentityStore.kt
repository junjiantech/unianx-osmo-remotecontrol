package com.unianx.osmo.remotecontrol.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.Locale

data class ControllerIdentity(
    val deviceId: Int,
    val pseudoMac: ByteArray,
) {
    val pseudoMacHex: String
        get() = pseudoMac.joinToString(":") { byte ->
            "%02X".format(Locale.US, byte.toInt() and 0xFF)
        }
}

class ControllerIdentityStore(context: Context) {
    private val settingsStore = AppSettingsStore(context)
    private val secureRandom = SecureRandom()

    fun getOrCreate(): ControllerIdentity {
        runBlocking {
            settingsStore.loadControllerIdentity()
        }?.let { stored ->
            return stored
        }

        val deviceId = secureRandom.nextInt().let {
            if (it == 0) 0x12345678 else it
        }
        val mac = ByteArray(6).also(secureRandom::nextBytes).apply {
            this[0] = (this[0].toInt() and 0xFE or 0x02).toByte()
        }

        val identity = ControllerIdentity(
            deviceId = deviceId,
            pseudoMac = mac,
        )

        runBlocking {
            settingsStore.saveControllerIdentity(identity)
        }

        return identity
    }
}
