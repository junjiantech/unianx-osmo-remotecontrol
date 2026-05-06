package com.unianx.osmo.remotecontrol

import android.app.Application
import android.bluetooth.BluetoothManager
import com.unianx.osmo.remotecontrol.ble.CameraConnectionState
import com.unianx.osmo.remotecontrol.ble.CameraMode
import com.unianx.osmo.remotecontrol.ble.DjiBleManager
import com.unianx.osmo.remotecontrol.data.AppSettingsStore
import com.unianx.osmo.remotecontrol.data.ConnectionHistoryEntry
import com.unianx.osmo.remotecontrol.data.ControllerIdentityStore
import com.unianx.osmo.remotecontrol.data.GpsSample
import com.unianx.osmo.remotecontrol.data.GpsSession
import com.unianx.osmo.remotecontrol.data.GpsSessionSummary
import com.unianx.osmo.remotecontrol.data.GpsTrackStore
import com.unianx.osmo.remotecontrol.location.PhoneLocationRepository
import com.unianx.osmo.remotecontrol.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RemoteControlController(application: Application) {
    private val appContext = application.applicationContext
    private val settingsStore = AppSettingsStore(application)
    private val bleManager = DjiBleManager(
        context = application,
        identityStore = ControllerIdentityStore(application),
    )
    private val locationRepository = PhoneLocationRepository(application)
    private val gpsTrackStore = GpsTrackStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var recordingPreviously = false
    private var activeSessionId: String? = null
    private var activeSessionStartedAtMs: Long = 0L
    private val activeSamples = mutableListOf<GpsSample>()
    private var autoReconnectAttempted = false
    private var foregroundServiceDemandActive = false

    init {
        scope.launch {
            bleManager.controllerIdentity.collect { identity ->
                _uiState.update { it.copy(controllerLabel = identity.pseudoMacHex) }
            }
        }

        scope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                if (state != CameraConnectionState.Ready && activeSessionId != null) {
                    finishActiveSession()
                }
                syncForegroundServiceDemand()
            }
        }

        scope.launch {
            bleManager.scannedDevices.collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }

        scope.launch {
            bleManager.connectedCamera.collect { device ->
                val wakeSupported = device?.let { current ->
                    _uiState.value.connectionHistory.firstOrNull { it.address == current.address }?.let(::isWakeWindowValid) == true
                } == true
                _uiState.update {
                    it.copy(
                        connectedCamera = device,
                        sleepWakeSupported = wakeSupported,
                    )
                }
                if (device != null && _uiState.value.gpsSyncEnabled) {
                    startGpsRelay()
                } else if (device == null && activeSessionId == null) {
                    stopGpsRelay()
                }
                syncForegroundServiceDemand()
            }
        }

        scope.launch {
            bleManager.telemetry.collect { telemetry ->
                _uiState.update { it.copy(telemetry = telemetry) }
                if (_uiState.value.gpsSyncEnabled) {
                    when {
                        telemetry.isRecording && !recordingPreviously -> startActiveSessionIfNeeded()
                        !telemetry.isRecording && recordingPreviously -> finishActiveSession()
                    }
                }
                recordingPreviously = telemetry.isRecording
                syncForegroundServiceDemand()
            }
        }

        scope.launch {
            bleManager.messages.collect { message ->
                _uiState.update { it.copy(message = message) }
            }
        }

        refreshRecentSessions()
        refreshConnectionHistory()
        attemptAutoReconnectIfEligible()
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun startScan() {
        AppLogger.i("RemoteControlController", "startScan requested")
        bleManager.startScan()
    }

    fun stopScan() {
        AppLogger.i("RemoteControlController", "stopScan requested")
        bleManager.stopScan()
    }

    fun connect(address: String) {
        AppLogger.i("RemoteControlController", "connect requested address=$address")
        scope.launch {
            runCatching {
                bleManager.connect(address)
                refreshConnectionHistory()
                if (_uiState.value.gpsSyncEnabled) {
                    startGpsRelay()
                }
            }.onFailure { throwable ->
                AppLogger.w("RemoteControlController", "connect failed address=$address", throwable)
            }
        }
    }

    fun disconnect() {
        AppLogger.i("RemoteControlController", "disconnect requested")
        scope.launch {
            bleManager.disconnect()
            if (!_uiState.value.gpsSyncEnabled) {
                stopGpsRelay()
            }
        }
    }

    fun capturePhoto() {
        scope.launch {
            val success = bleManager.capturePhoto()
            if (!success) {
                _uiState.update { it.copy(message = "拍照指令未生效") }
            }
        }
    }

    fun toggleRecording() {
        scope.launch {
            val success = bleManager.toggleRecording()
            if (!success) {
                _uiState.update { it.copy(message = "录像指令发送失败") }
            }
        }
    }

    fun lockScreen() {
        scope.launch {
            val success = bleManager.sleepCamera()
            if (!success) {
                _uiState.update { it.copy(message = "锁屏指令发送失败") }
            }
        }
    }

    fun wakeAndSnapshot() {
        scope.launch {
            val success = bleManager.wakeAndTriggerSnapshot()
            if (!success) {
                _uiState.update { it.copy(message = "唤醒拍录失败") }
            }
        }
    }

    fun switchMode(mode: CameraMode) {
        scope.launch {
            val success = bleManager.switchMode(mode)
            if (!success) {
                _uiState.update { it.copy(message = "模式切换失败") }
            }
        }
    }

    fun setGpsSyncEnabled(enabled: Boolean) {
        _uiState.update { it.copy(gpsSyncEnabled = enabled) }
        if (enabled) {
            if (_uiState.value.connectedCamera != null) {
                startGpsRelay()
            }
            if (_uiState.value.telemetry.isRecording) {
                startActiveSessionIfNeeded()
            }
        } else {
            stopGpsRelay()
        }
        syncForegroundServiceDemand()
    }

    fun shouldKeepForegroundServiceRunning(): Boolean {
        return _uiState.value.connectedCamera != null || activeSessionId != null
    }

    private fun startGpsRelay() {
        if (locationJob != null) return

        locationJob = scope.launch {
            locationRepository.locationUpdates(intervalMillis = 1_000L)
                .catch { throwable ->
                    AppLogger.w("RemoteControlController", "location updates stopped", throwable)
                    _uiState.update {
                        it.copy(
                            latestLocation = null,
                            activeTrackPoints = activeSamples.size,
                            message = locationFailureMessage(throwable),
                        )
                    }
                    locationJob = null
                }
                .collect { sample ->
                    _uiState.update { current ->
                        current.copy(
                            latestLocation = sample,
                            activeTrackPoints = activeSamples.size,
                        )
                    }

                    if (_uiState.value.connectionState == CameraConnectionState.Ready && !bleManager.isCameraSleeping()) {
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
        scope.launch {
            finishActiveSession()
        }
    }

    private fun startActiveSessionIfNeeded() {
        if (activeSessionId != null) return

        activeSessionId = UUID.randomUUID().toString()
        activeSessionStartedAtMs = System.currentTimeMillis()
        activeSamples.clear()
        _uiState.update { it.copy(activeTrackPoints = 0) }
        syncForegroundServiceDemand()
    }

    private suspend fun finishActiveSession() {
        val sessionId = activeSessionId ?: return
        activeSessionId = null
        syncForegroundServiceDemand()

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
        scope.launch {
            val sessions = gpsTrackStore.loadRecentSummaries()
            _uiState.update { it.copy(recentSessions = sessions) }
        }
    }

    private fun refreshConnectionHistory() {
        scope.launch {
            val connections = settingsStore.loadRecentConnections()
            val connectedCamera = _uiState.value.connectedCamera
            val wakeSupported = connectedCamera?.let { current ->
                connections.firstOrNull { it.address == current.address }?.let(::isWakeWindowValid) == true
            } == true
            _uiState.update {
                it.copy(
                    connectionHistory = connections,
                    sleepWakeSupported = wakeSupported,
                )
            }
        }
    }

    private fun attemptAutoReconnectIfEligible() {
        if (autoReconnectAttempted) return
        autoReconnectAttempted = true

        scope.launch {
            val permissionsGranted = appContext.hasPermissions(bluetoothPermissions())
            val lastAddress = settingsStore.loadLastConnectedCameraAddress()
            val bluetoothEnabled = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            val connectionState = bleManager.connectionState.value
            AppLogger.i(
                "RemoteControlController",
                "auto reconnect check permissionsGranted=$permissionsGranted bluetoothEnabled=$bluetoothEnabled lastAddress=${lastAddress.orEmpty()} state=$connectionState",
            )

            if (!permissionsGranted) return@launch
            if (!bluetoothEnabled) return@launch
            if (lastAddress.isNullOrBlank()) return@launch
            if (connectionState != CameraConnectionState.Idle) return@launch

            runCatching {
                bleManager.connect(lastAddress, notifyUserOnFailure = false)
                refreshConnectionHistory()
                if (_uiState.value.gpsSyncEnabled) {
                    startGpsRelay()
                }
            }.onFailure { throwable ->
                AppLogger.w("RemoteControlController", "auto reconnect failed address=$lastAddress", throwable)
            }
        }
    }

    private fun syncForegroundServiceDemand() {
        val shouldRun = shouldKeepForegroundServiceRunning()
        _uiState.update { it.copy(backgroundServiceActive = shouldRun) }
        if (foregroundServiceDemandActive == shouldRun) return

        foregroundServiceDemandActive = shouldRun
        if (shouldRun) {
            RemoteControlForegroundService.start(appContext)
        } else {
            RemoteControlForegroundService.stop(appContext)
        }
    }

    private fun isWakeWindowValid(entry: ConnectionHistoryEntry): Boolean {
        return System.currentTimeMillis() - entry.lastWakeCapableAtMs <= WAKE_WINDOW_MS
    }

    private fun locationFailureMessage(throwable: Throwable): String {
        return when (throwable.message) {
            "No location providers enabled" -> "系统定位未开启，轨迹记录已暂停"
            "LocationManager unavailable" -> "当前设备无法提供定位服务"
            else -> "定位采集已中断，请检查系统定位和后台限制"
        }
    }

    companion object {
        const val WAKE_WINDOW_MS = 30 * 60 * 1000L

        val sessionTimeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)

        fun formatSessionTime(timestampMs: Long): String = sessionTimeFormatter.format(Date(timestampMs))
    }
}
