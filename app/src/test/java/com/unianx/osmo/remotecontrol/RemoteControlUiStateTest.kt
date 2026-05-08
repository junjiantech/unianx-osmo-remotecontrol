package com.unianx.osmo.remotecontrol

import com.unianx.osmo.remotecontrol.ble.CameraConnectionState
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlUiStateTest {
    @Test
    fun `foreground service should stay alive while connection is in progress`() {
        assertTrue(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.GattConnecting,
                connectedCamera = null,
                hasActiveSession = false,
            ),
        )

        assertTrue(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.Handshaking,
                connectedCamera = null,
                hasActiveSession = false,
            ),
        )
    }

    @Test
    fun `foreground service should stay alive when camera is connected`() {
        val camera = ScannedCamera(
            name = "Osmo Action",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -40,
            lastSeenAtMs = 1L,
        )

        assertTrue(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.Ready,
                connectedCamera = camera,
                hasActiveSession = false,
            ),
        )
    }

    @Test
    fun `foreground service should stay alive while active session is being saved`() {
        assertTrue(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.Idle,
                connectedCamera = null,
                hasActiveSession = true,
            ),
        )
    }

    @Test
    fun `foreground service can stop when idle and disconnected`() {
        assertFalse(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.Idle,
                connectedCamera = null,
                hasActiveSession = false,
            ),
        )
    }

    @Test
    fun `foreground service can stop after connection error is cleaned up`() {
        assertFalse(
            shouldKeepRemoteControlForegroundServiceRunning(
                connectionState = CameraConnectionState.Error,
                connectedCamera = null,
                hasActiveSession = false,
            ),
        )
    }
}
