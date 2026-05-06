package com.unianx.osmo.remotecontrol

import com.unianx.osmo.remotecontrol.ble.CameraTelemetry
import com.unianx.osmo.remotecontrol.ble.CameraWorkState
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlUiStateTest {
    @Test
    fun `background service should stay alive when camera connected`() {
        val state = RemoteControlUiState(
            connectedCamera = ScannedCamera(
                name = "Osmo Action",
                address = "AA:BB:CC:DD:EE:FF",
                rssi = -40,
                lastSeenAtMs = 1L,
            ),
        )

        assertTrue(state.shouldKeepForegroundServiceRunning())
    }

    @Test
    fun `background service should stay alive while recording even without camera object`() {
        val state = RemoteControlUiState(
            telemetry = CameraTelemetry(workState = CameraWorkState.Busy),
        )

        assertTrue(state.shouldKeepForegroundServiceRunning())
    }

    @Test
    fun `background service can stop when idle and disconnected`() {
        val state = RemoteControlUiState()

        assertFalse(state.shouldKeepForegroundServiceRunning())
    }
}
