package com.unianx.osmo.remotecontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.unianx.osmo.remotecontrol.ble.CameraMode
import com.unianx.osmo.remotecontrol.ble.CameraTelemetry
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import com.unianx.osmo.remotecontrol.ble.localizedModeLabel
import com.unianx.osmo.remotecontrol.data.ConnectionHistoryEntry
import com.unianx.osmo.remotecontrol.data.GpsSample
import com.unianx.osmo.remotecontrol.data.GpsSessionSummary
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RemoteControlUiState(
    val controllerLabel: String = "",
    val connectionState: com.unianx.osmo.remotecontrol.ble.CameraConnectionState = com.unianx.osmo.remotecontrol.ble.CameraConnectionState.Idle,
    val scannedDevices: List<ScannedCamera> = emptyList(),
    val connectedCamera: ScannedCamera? = null,
    val telemetry: CameraTelemetry = CameraTelemetry(),
    val gpsSyncEnabled: Boolean = false,
    val latestLocation: GpsSample? = null,
    val activeTrackPoints: Int = 0,
    val backgroundServiceActive: Boolean = false,
    val recentSessions: List<GpsSessionSummary> = emptyList(),
    val connectionHistory: List<ConnectionHistoryEntry> = emptyList(),
    val sleepWakeSupported: Boolean = false,
    val message: String? = null,
) {
    fun shouldKeepForegroundServiceRunning(): Boolean {
        return connectedCamera != null || telemetry.isRecording
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = (application as RemoteControlApp).remoteControlController
    val uiState: StateFlow<RemoteControlUiState> = controller.uiState

    fun dismissMessage() {
        controller.dismissMessage()
    }

    fun startScan() {
        controller.startScan()
    }

    fun stopScan() {
        controller.stopScan()
    }

    fun connect(address: String) {
        controller.connect(address)
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun capturePhoto() {
        controller.capturePhoto()
    }

    fun toggleRecording() {
        controller.toggleRecording()
    }

    fun lockScreen() {
        controller.lockScreen()
    }

    fun wakeAndSnapshot() {
        controller.wakeAndSnapshot()
    }

    fun switchMode(mode: CameraMode) {
        controller.switchMode(mode)
    }

    fun setGpsSyncEnabled(enabled: Boolean) {
        controller.setGpsSyncEnabled(enabled)
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(application) as T
                }
            }
        }

        val sessionTimeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
        fun formatSessionTime(timestampMs: Long): String = sessionTimeFormatter.format(Date(timestampMs))
        fun formatSessionModeLabel(label: String): String = localizedModeLabel(label)
    }
}
