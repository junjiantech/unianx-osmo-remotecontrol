package com.unianx.osmo.remotecontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unianx.osmo.remotecontrol.ble.CameraConnectionState
import com.unianx.osmo.remotecontrol.ble.CameraMode
import com.unianx.osmo.remotecontrol.ble.CameraTelemetry
import com.unianx.osmo.remotecontrol.ble.DjiBleManager
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import com.unianx.osmo.remotecontrol.ble.localizedModeLabel
import com.unianx.osmo.remotecontrol.data.ControllerIdentityStore
import com.unianx.osmo.remotecontrol.data.GpsSample
import com.unianx.osmo.remotecontrol.data.GpsSession
import com.unianx.osmo.remotecontrol.data.GpsSessionSummary
import com.unianx.osmo.remotecontrol.data.GpsTrackStore
import com.unianx.osmo.remotecontrol.location.PhoneLocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RemoteControlUiState(
    val controllerLabel: String = "",
    val connectionState: CameraConnectionState = CameraConnectionState.Idle,
    val scannedDevices: List<ScannedCamera> = emptyList(),
    val connectedCamera: ScannedCamera? = null,
    val telemetry: CameraTelemetry = CameraTelemetry(),
    val gpsSyncEnabled: Boolean = false,
    val latestLocation: GpsSample? = null,
    val activeTrackPoints: Int = 0,
    val recentSessions: List<GpsSessionSummary> = emptyList(),
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bleManager = DjiBleManager(
        context = application,
        identityStore = ControllerIdentityStore(application),
    )
    private val locationRepository = PhoneLocationRepository(application)
    private val gpsTrackStore = GpsTrackStore(application)

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var recordingPreviously = false
    private var activeSessionId: String? = null
    private var activeSessionStartedAtMs: Long = 0L
    private val activeSamples = mutableListOf<GpsSample>()

    init {
        viewModelScope.launch {
            bleManager.controllerIdentity.collect { identity ->
                _uiState.update { it.copy(controllerLabel = identity.pseudoMacHex) }
            }
        }

        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                if (state != CameraConnectionState.Ready && activeSessionId != null) {
                    finishActiveSession()
                }
            }
        }

        viewModelScope.launch {
            bleManager.scannedDevices.collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }

        viewModelScope.launch {
            bleManager.connectedCamera.collect { device ->
                _uiState.update { it.copy(connectedCamera = device) }
            }
        }

        viewModelScope.launch {
            bleManager.telemetry.collect { telemetry ->
                _uiState.update { it.copy(telemetry = telemetry) }
                if (_uiState.value.gpsSyncEnabled) {
                    when {
                        telemetry.isRecording && !recordingPreviously -> startActiveSessionIfNeeded()
                        !telemetry.isRecording && recordingPreviously -> finishActiveSession()
                    }
                }
                recordingPreviously = telemetry.isRecording
            }
        }

        viewModelScope.launch {
            bleManager.messages.collect { message ->
                _uiState.update { it.copy(message = message) }
            }
        }

        refreshRecentSessions()
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connect(address: String) {
        viewModelScope.launch {
            bleManager.connect(address)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
            stopGpsRelay()
        }
    }

    fun capturePhoto() {
        viewModelScope.launch {
            val success = bleManager.capturePhoto()
            if (!success) {
                _uiState.update { it.copy(message = "拍照指令未生效") }
            }
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            val success = bleManager.toggleRecording()
            if (!success) {
                _uiState.update { it.copy(message = "录像指令发送失败") }
            }
        }
    }

    fun switchMode(mode: CameraMode) {
        viewModelScope.launch {
            val success = bleManager.switchMode(mode)
            if (!success) {
                _uiState.update { it.copy(message = "模式切换失败") }
            }
        }
    }

    fun setGpsSyncEnabled(enabled: Boolean) {
        _uiState.update { it.copy(gpsSyncEnabled = enabled) }
        if (enabled) {
            startGpsRelay()
            if (_uiState.value.telemetry.isRecording) {
                startActiveSessionIfNeeded()
            }
        } else {
            stopGpsRelay()
        }
    }

    private fun startGpsRelay() {
        if (locationJob != null) return

        locationJob = viewModelScope.launch {
            locationRepository.locationUpdates(intervalMillis = 1_000L).collect { sample ->
                _uiState.update { current ->
                    current.copy(
                        latestLocation = sample,
                        activeTrackPoints = activeSamples.size,
                    )
                }

                if (_uiState.value.connectionState == CameraConnectionState.Ready) {
                    bleManager.pushGpsSample(sample)
                }

                if (_uiState.value.gpsSyncEnabled && _uiState.value.telemetry.isRecording) {
                    startActiveSessionIfNeeded()
                    activeSamples += sample
                    _uiState.update { it.copy(activeTrackPoints = activeSamples.size) }
                }
            }
        }
    }

    private fun stopGpsRelay() {
        locationJob?.cancel()
        locationJob = null
        viewModelScope.launch {
            finishActiveSession()
        }
    }

    private fun startActiveSessionIfNeeded() {
        if (activeSessionId != null) return

        activeSessionId = UUID.randomUUID().toString()
        activeSessionStartedAtMs = System.currentTimeMillis()
        activeSamples.clear()
        _uiState.update { it.copy(activeTrackPoints = 0) }
    }

    private suspend fun finishActiveSession() {
        val sessionId = activeSessionId ?: return
        activeSessionId = null

        if (activeSamples.isEmpty()) {
            activeSamples.clear()
            _uiState.update { it.copy(activeTrackPoints = 0) }
            return
        }

        val connectedCamera = _uiState.value.connectedCamera
        val session = GpsSession(
            id = sessionId,
            startedAtMs = activeSessionStartedAtMs,
            endedAtMs = System.currentTimeMillis(),
            cameraName = connectedCamera?.displayName ?: "DJI 相机",
            cameraAddress = connectedCamera?.address,
            recordModeLabel = _uiState.value.telemetry.modeLabel,
            samples = activeSamples.toList(),
        )

        gpsTrackStore.saveSession(session)
        activeSamples.clear()
        _uiState.update { it.copy(activeTrackPoints = 0) }
        refreshRecentSessions()
    }

    private fun refreshRecentSessions() {
        viewModelScope.launch {
            val sessions = gpsTrackStore.loadRecentSummaries()
            _uiState.update { it.copy(recentSessions = sessions) }
        }
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
