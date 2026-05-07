package com.unianx.osmo.remotecontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unianx.osmo.remotecontrol.ble.CameraMode
import com.unianx.osmo.remotecontrol.ble.CameraTelemetry
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import com.unianx.osmo.remotecontrol.ble.localizedModeLabel
import com.unianx.osmo.remotecontrol.data.ConnectionHistoryEntry
import com.unianx.osmo.remotecontrol.data.GpsSample
import com.unianx.osmo.remotecontrol.data.GpsSessionSummary
import com.unianx.osmo.remotecontrol.data.GpsTrackStore
import com.unianx.osmo.remotecontrol.data.ThemeMode
import com.unianx.osmo.remotecontrol.data.TrackBrowserUiState
import com.unianx.osmo.remotecontrol.data.TrackExportFormat
import com.unianx.osmo.remotecontrol.data.TrackShareRequest
import com.unianx.osmo.remotecontrol.data.TrackTimeFilter
import com.unianx.osmo.remotecontrol.data.defaultTrackCustomFilter
import com.unianx.osmo.remotecontrol.data.filterTrackSummaries
import com.unianx.osmo.remotecontrol.data.normalizeTrackCustomFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

data class RemoteControlUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val controllerLabel: String = "",
    val connectionState: com.unianx.osmo.remotecontrol.ble.CameraConnectionState = com.unianx.osmo.remotecontrol.ble.CameraConnectionState.Idle,
    val scannedDevices: List<ScannedCamera> = emptyList(),
    val connectedCamera: ScannedCamera? = null,
    val telemetry: CameraTelemetry = CameraTelemetry(),
    val gpsSyncEnabled: Boolean = false,
    val latestLocation: GpsSample? = null,
    val activeTrackPoints: Int = 0,
    val backgroundServiceActive: Boolean = false,
    val notificationChannelEnabled: Boolean = true,
    val notificationChannelImportance: Int? = null,
    val notificationChannelVisibleOnLockScreen: Boolean = false,
    val recentSessions: List<GpsSessionSummary> = emptyList(),
    val connectionHistory: List<ConnectionHistoryEntry> = emptyList(),
    val message: String? = null,
) {
    fun shouldKeepForegroundServiceRunning(): Boolean {
        return connectedCamera != null || telemetry.isRecording
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = (application as RemoteControlApp).remoteControlController
    private val gpsTrackStore = GpsTrackStore(application)

    val uiState: StateFlow<RemoteControlUiState> = controller.uiState

    private val _trackBrowserUiState = MutableStateFlow(
        TrackBrowserUiState(
            draftCustomFilter = defaultTrackCustomFilter(),
        ),
    )
    val trackBrowserUiState: StateFlow<TrackBrowserUiState> = _trackBrowserUiState.asStateFlow()

    init {
        refreshTrackSummaries()
        viewModelScope.launch {
            uiState
                .map { it.recentSessions }
                .distinctUntilChanged()
                .collect {
                    refreshTrackSummaries()
                }
        }
    }

    fun dismissMessage() {
        controller.dismissMessage()
    }

    fun dismissTrackBrowserMessage() {
        _trackBrowserUiState.update { it.copy(message = null) }
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

    fun switchMode(mode: CameraMode) {
        controller.switchMode(mode)
    }

    fun setGpsSyncEnabled(enabled: Boolean) {
        controller.setGpsSyncEnabled(enabled)
    }

    fun refreshNotificationSettings() {
        controller.refreshNotificationSettings()
    }

    fun setThemeMode(mode: ThemeMode) {
        controller.setThemeMode(mode)
    }

    fun refreshTrackSummaries() {
        viewModelScope.launch {
            _trackBrowserUiState.update { it.copy(isLoadingList = true, message = null) }
            val summaries = gpsTrackStore.loadAllSummaries()
            _trackBrowserUiState.update { current ->
                current.copy(
                    allSummaries = summaries,
                    filteredSummaries = filterTrackSummaries(
                        summaries = summaries,
                        filter = current.currentFilter,
                    ),
                    isLoadingList = false,
                )
            }
        }
    }

    fun selectTrackFilter(filter: TrackTimeFilter) {
        _trackBrowserUiState.update { current ->
            val normalizedFilter = when (filter) {
                is TrackTimeFilter.Custom -> normalizeTrackCustomFilter(filter.startDate, filter.endDate)
                else -> filter
            }
            current.copy(
                currentFilter = normalizedFilter,
                filteredSummaries = filterTrackSummaries(current.allSummaries, normalizedFilter),
            )
        }
    }

    fun updateCustomTrackFilter(startDate: LocalDate? = null, endDate: LocalDate? = null) {
        _trackBrowserUiState.update { current ->
            val draft = current.draftCustomFilter
            current.copy(
                draftCustomFilter = normalizeTrackCustomFilter(
                    startDate = startDate ?: draft.startDate,
                    endDate = endDate ?: draft.endDate,
                ),
            )
        }
    }

    fun applyCustomTrackFilter() {
        selectTrackFilter(_trackBrowserUiState.value.draftCustomFilter)
    }

    fun selectTrackSession(sessionId: String) {
        viewModelScope.launch {
            _trackBrowserUiState.update {
                it.copy(
                    selectedSessionId = sessionId,
                    selectedSession = null,
                    isLoadingDetail = true,
                    message = null,
                )
            }
            val session = gpsTrackStore.loadSession(sessionId)
            _trackBrowserUiState.update {
                it.copy(
                    selectedSession = session,
                    isLoadingDetail = false,
                    message = if (session == null) "轨迹不存在或已损坏" else null,
                )
            }
        }
    }

    fun clearSelectedTrackSession() {
        _trackBrowserUiState.update {
            it.copy(
                selectedSessionId = null,
                selectedSession = null,
                isLoadingDetail = false,
                isExporting = false,
            )
        }
    }

    fun exportTrackSession(format: TrackExportFormat) {
        val sessionId = _trackBrowserUiState.value.selectedSessionId ?: return
        viewModelScope.launch {
            _trackBrowserUiState.update { it.copy(isExporting = true, message = null) }
            val file = gpsTrackStore.exportSession(sessionId = sessionId, format = format)
            _trackBrowserUiState.update {
                it.copy(
                    isExporting = false,
                    pendingShareRequest = file?.let { created ->
                        TrackShareRequest(
                            absolutePath = created.absolutePath,
                            format = format,
                        )
                    },
                    message = if (file == null) "导出失败，请稍后重试" else null,
                )
            }
        }
    }

    fun onTrackShareRequestHandled() {
        _trackBrowserUiState.update { it.copy(pendingShareRequest = null) }
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
        val sessionDateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)

        fun formatSessionTime(timestampMs: Long): String = sessionTimeFormatter.format(Date(timestampMs))

        fun formatSessionDateTime(timestampMs: Long): String = sessionDateTimeFormatter.format(Date(timestampMs))

        fun formatSessionModeLabel(label: String): String = localizedModeLabel(label)
    }
}
