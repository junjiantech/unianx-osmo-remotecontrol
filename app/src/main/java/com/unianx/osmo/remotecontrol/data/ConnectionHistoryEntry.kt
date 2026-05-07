package com.unianx.osmo.remotecontrol.data

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHistoryEntry(
    val name: String,
    val address: String,
    val connectedAtMs: Long,
) {
    val displayName: String
        get() = name.ifBlank { "DJI 相机 ${address.takeLast(5)}" }
}
