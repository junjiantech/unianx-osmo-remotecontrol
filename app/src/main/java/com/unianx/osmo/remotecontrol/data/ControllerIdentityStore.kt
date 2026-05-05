package com.unianx.osmo.remotecontrol.data

import android.content.Context
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
    private val preferences = context.getSharedPreferences("controller_identity", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun getOrCreate(): ControllerIdentity {
        val storedDeviceId = preferences.getInt(KEY_DEVICE_ID, Int.MIN_VALUE)
        val storedMac = preferences.getString(KEY_PSEUDO_MAC, null)
        if (storedDeviceId != Int.MIN_VALUE && storedMac != null) {
            return ControllerIdentity(
                deviceId = storedDeviceId,
                pseudoMac = storedMac.split(':')
                    .map { it.toInt(16).toByte() }
                    .toByteArray(),
            )
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

        preferences.edit()
            .putInt(KEY_DEVICE_ID, identity.deviceId)
            .putString(KEY_PSEUDO_MAC, identity.pseudoMacHex)
            .apply()

        return identity
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PSEUDO_MAC = "pseudo_mac"
    }
}
