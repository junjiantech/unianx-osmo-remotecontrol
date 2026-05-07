@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.unianx.osmo.remotecontrol.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.unianx.osmo.remotecontrol.MainViewModel
import com.unianx.osmo.remotecontrol.RemoteControlUiState
import com.unianx.osmo.remotecontrol.ble.CameraConnectionState
import com.unianx.osmo.remotecontrol.ble.CameraMode
import com.unianx.osmo.remotecontrol.ble.ScannedCamera
import com.unianx.osmo.remotecontrol.data.ConnectionHistoryEntry
import com.unianx.osmo.remotecontrol.data.GpsSession
import com.unianx.osmo.remotecontrol.data.GpsSessionSummary
import com.unianx.osmo.remotecontrol.data.ThemeMode
import com.unianx.osmo.remotecontrol.data.TrackBrowserUiState
import com.unianx.osmo.remotecontrol.data.TrackExportFormat
import com.unianx.osmo.remotecontrol.data.TrackTimeFilter
import com.unianx.osmo.remotecontrol.data.durationSeconds
import com.unianx.osmo.remotecontrol.data.toSummary
import com.unianx.osmo.remotecontrol.ui.theme.OsmoAccent
import com.unianx.osmo.remotecontrol.ui.theme.OsmoCanvas
import com.unianx.osmo.remotecontrol.ui.theme.OsmoHairline
import com.unianx.osmo.remotecontrol.ui.theme.OsmoHairlineStrong
import com.unianx.osmo.remotecontrol.ui.theme.OsmoInk
import com.unianx.osmo.remotecontrol.ui.theme.OsmoInkMuted
import com.unianx.osmo.remotecontrol.ui.theme.OsmoInkSubtle
import com.unianx.osmo.remotecontrol.ui.theme.OsmoSuccess
import com.unianx.osmo.remotecontrol.ui.theme.OsmoSurface1
import com.unianx.osmo.remotecontrol.ui.theme.OsmoSurface2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ScreenPage {
    Home,
    Settings,
    TrackList,
    TrackDetail,
}

private val localDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun RemoteControlScreen(
    uiState: RemoteControlUiState,
    trackBrowserUiState: TrackBrowserUiState,
    hasBluetoothPermission: Boolean,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundLocationPermission: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onLockScreen: () -> Unit,
    onSwitchMode: (CameraMode) -> Unit,
    onToggleGpsSync: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSelectTrackFilter: (TrackTimeFilter) -> Unit,
    onUpdateCustomTrackFilter: (LocalDate?, LocalDate?) -> Unit,
    onApplyCustomTrackFilter: () -> Unit,
    onOpenTrackSession: (String) -> Unit,
    onCloseTrackSession: () -> Unit,
    onExportTrackSession: (TrackExportFormat) -> Unit,
    onDismissTrackBrowserMessage: () -> Unit,
    onRequestBluetoothPermissionForScan: () -> Unit,
    onRequestBluetoothPermissionForConnect: (String) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationChannelSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var currentPage by rememberSaveable { mutableStateOf<ScreenPage>(ScreenPage.Home) }

    when (currentPage) {
        ScreenPage.TrackDetail -> {
            BackHandler {
                onCloseTrackSession()
                currentPage = ScreenPage.TrackList
            }
        }

        ScreenPage.TrackList,
        ScreenPage.Settings,
        -> {
            BackHandler {
                if (currentPage == ScreenPage.TrackList) {
                    onCloseTrackSession()
                }
                currentPage = ScreenPage.Home
            }
        }

        ScreenPage.Home -> Unit
    }

    uiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = {
                Text(
                    text = "提示",
                    color = OsmoInk,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = message,
                    color = OsmoInkMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissMessage) {
                    Text(text = "知道了", color = OsmoAccent)
                }
            },
            containerColor = OsmoSurface1,
            tonalElevation = 0.dp,
        )
    }

    trackBrowserUiState.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissTrackBrowserMessage,
            title = {
                Text(
                    text = "轨迹",
                    color = OsmoInk,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = message,
                    color = OsmoInkMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissTrackBrowserMessage) {
                    Text(text = "知道了", color = OsmoAccent)
                }
            },
            containerColor = OsmoSurface1,
            tonalElevation = 0.dp,
        )
    }

    when (currentPage) {
        ScreenPage.Home -> HomePage(
            uiState = uiState,
            recentSession = uiState.recentSessions.firstOrNull(),
            hasBluetoothPermission = hasBluetoothPermission,
            onStartScan = onStartScan,
            onRequestBluetoothPermissionForScan = onRequestBluetoothPermissionForScan,
            onOpenTrackList = { currentPage = ScreenPage.TrackList },
            onOpenSettings = { currentPage = ScreenPage.Settings },
            onConnect = onConnect,
            onRequestBluetoothPermissionForConnect = onRequestBluetoothPermissionForConnect,
            onDisconnect = onDisconnect,
            onCapturePhoto = onCapturePhoto,
            onToggleRecording = onToggleRecording,
            onLockScreen = onLockScreen,
            onSwitchMode = onSwitchMode,
        )

        ScreenPage.Settings -> SettingsPage(
            uiState = uiState,
            hasBluetoothPermission = hasBluetoothPermission,
            hasLocationPermission = hasLocationPermission,
            hasNotificationPermission = hasNotificationPermission,
            hasBackgroundLocationPermission = hasBackgroundLocationPermission,
            onScan = {
                if (hasBluetoothPermission) onStartScan() else onRequestBluetoothPermissionForScan()
            },
            onStopScan = onStopScan,
            onSetThemeMode = onSetThemeMode,
            onConnect = onConnect,
            onRequestBluetoothPermissionForConnect = onRequestBluetoothPermissionForConnect,
            onToggleGpsSync = onToggleGpsSync,
            onRequestLocationPermission = onRequestLocationPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenNotificationChannelSettings = onOpenNotificationChannelSettings,
            onOpenAppSettings = onOpenAppSettings,
            onBackHome = { currentPage = ScreenPage.Home },
        )

        ScreenPage.TrackList -> TrackListPage(
            trackBrowserUiState = trackBrowserUiState,
            onBackHome = {
                onCloseTrackSession()
                currentPage = ScreenPage.Home
            },
            onSelectTrackFilter = onSelectTrackFilter,
            onUpdateCustomTrackFilter = onUpdateCustomTrackFilter,
            onApplyCustomTrackFilter = onApplyCustomTrackFilter,
            onOpenTrackSession = { sessionId ->
                onOpenTrackSession(sessionId)
                currentPage = ScreenPage.TrackDetail
            },
        )

        ScreenPage.TrackDetail -> TrackDetailPage(
            session = trackBrowserUiState.selectedSession,
            isLoading = trackBrowserUiState.isLoadingDetail,
            isExporting = trackBrowserUiState.isExporting,
            onBack = {
                onCloseTrackSession()
                currentPage = ScreenPage.TrackList
            },
            onExportTrackSession = onExportTrackSession,
        )
    }
}

@Composable
private fun HomePage(
    uiState: RemoteControlUiState,
    recentSession: GpsSessionSummary?,
    hasBluetoothPermission: Boolean,
    onStartScan: () -> Unit,
    onRequestBluetoothPermissionForScan: () -> Unit,
    onOpenTrackList: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnect: (String) -> Unit,
    onRequestBluetoothPermissionForConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onLockScreen: () -> Unit,
    onSwitchMode: (CameraMode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .background(OsmoCanvas)
            .statusBarsPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            HeroPanel(
                connectionState = uiState.connectionState,
                connectedCamera = uiState.connectedCamera?.displayName,
                onDisconnect = onDisconnect,
                onOpenSettings = onOpenSettings,
            )
        }

        item {
            QuickControlPanel(
                uiState = uiState,
                onCapturePhoto = onCapturePhoto,
                onToggleRecording = onToggleRecording,
                onLockScreen = onLockScreen,
                onSwitchMode = onSwitchMode,
            )
        }

        item {
            ConnectionHistoryPanel(
                connections = uiState.connectionHistory,
                connectionState = uiState.connectionState,
                connectedCamera = uiState.connectedCamera,
                onConnect = {
                    if (hasBluetoothPermission) onConnect(it) else onRequestBluetoothPermissionForConnect(it)
                },
                onScan = {
                    if (hasBluetoothPermission) onStartScan() else onRequestBluetoothPermissionForScan()
                },
            )
        }

        item {
            TrackEntryPanel(
                recentSession = recentSession,
                onOpenTrackList = onOpenTrackList,
            )
        }

        item {
            LiveStatusPanel(uiState = uiState)
        }
    }
}

@Composable
private fun SettingsPage(
    uiState: RemoteControlUiState,
    hasBluetoothPermission: Boolean,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundLocationPermission: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onConnect: (String) -> Unit,
    onRequestBluetoothPermissionForConnect: (String) -> Unit,
    onToggleGpsSync: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationChannelSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBackHome: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .background(OsmoCanvas)
            .statusBarsPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingsSummaryPanel(
                uiState = uiState,
                hasBluetoothPermission = hasBluetoothPermission,
                hasLocationPermission = hasLocationPermission,
                hasNotificationPermission = hasNotificationPermission,
                onScan = onScan,
                onStopScan = onStopScan,
                onOpenNotificationChannelSettings = onOpenNotificationChannelSettings,
                onBackHome = onBackHome,
            )
        }

        item {
            AppearancePanel(
                themeMode = uiState.themeMode,
                onSetThemeMode = onSetThemeMode,
            )
        }

        item {
            DeviceScannerPanel(
                devices = uiState.scannedDevices,
                connectionState = uiState.connectionState,
                connectedCamera = uiState.connectedCamera,
                onConnect = {
                    if (hasBluetoothPermission) onConnect(it) else onRequestBluetoothPermissionForConnect(it)
                },
            )
        }

        item {
            GpsSyncPanel(
                uiState = uiState,
                hasLocationPermission = hasLocationPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBackgroundLocationPermission = hasBackgroundLocationPermission,
                onToggleGpsSync = { enabled ->
                    when {
                        enabled && !hasLocationPermission -> onRequestLocationPermission()
                        else -> onToggleGpsSync(enabled)
                    }
                },
                onRequestLocationPermission = onRequestLocationPermission,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenNotificationChannelSettings = onOpenNotificationChannelSettings,
                onOpenAppSettings = onOpenAppSettings,
            )
        }
    }
}

@Composable
private fun TrackListPage(
    trackBrowserUiState: TrackBrowserUiState,
    onBackHome: () -> Unit,
    onSelectTrackFilter: (TrackTimeFilter) -> Unit,
    onUpdateCustomTrackFilter: (LocalDate?, LocalDate?) -> Unit,
    onApplyCustomTrackFilter: () -> Unit,
    onOpenTrackSession: (String) -> Unit,
) {
    val currentFilter = trackBrowserUiState.currentFilter
    val customFilter = trackBrowserUiState.draftCustomFilter
    val context = LocalContext.current
    val startPicker = remember(customFilter.startDate, customFilter.endDate) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onUpdateCustomTrackFilter(LocalDate.of(year, month + 1, dayOfMonth), customFilter.endDate)
                onApplyCustomTrackFilter()
            },
            customFilter.startDate.year,
            customFilter.startDate.monthValue - 1,
            customFilter.startDate.dayOfMonth,
        )
    }
    val endPicker = remember(customFilter.startDate, customFilter.endDate) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onUpdateCustomTrackFilter(customFilter.startDate, LocalDate.of(year, month + 1, dayOfMonth))
                onApplyCustomTrackFilter()
            },
            customFilter.endDate.year,
            customFilter.endDate.monthValue - 1,
            customFilter.endDate.dayOfMonth,
        )
    }
    LazyColumn(
        modifier = Modifier
            .background(OsmoCanvas)
            .statusBarsPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ConsolePanel(
                eyebrow = "记录",
                title = "轨迹记录",
                headerAction = {
                    HeaderActionButton(
                        label = "主页",
                        icon = Icons.Rounded.Home,
                        onClick = onBackHome,
                    )
                },
            ) {
                Text(
                    text = "全部历史轨迹按开始时间倒序展示，可按时间过滤并进入详情导出。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OsmoInkMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        label = "全部",
                        selected = currentFilter == TrackTimeFilter.All,
                        onClick = { onSelectTrackFilter(TrackTimeFilter.All) },
                    )
                    FilterChip(
                        label = "近7天",
                        selected = currentFilter == TrackTimeFilter.Last7Days,
                        onClick = { onSelectTrackFilter(TrackTimeFilter.Last7Days) },
                    )
                    FilterChip(
                        label = "近30天",
                        selected = currentFilter == TrackTimeFilter.Last30Days,
                        onClick = { onSelectTrackFilter(TrackTimeFilter.Last30Days) },
                    )
                    FilterChip(
                        label = "自定义",
                        selected = currentFilter is TrackTimeFilter.Custom,
                        onClick = onApplyCustomTrackFilter,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(
                        label = "开始 ${localDateFormatter.format(customFilter.startDate)}",
                        onClick = { startPicker.show() },
                    )
                    GhostButton(
                        label = "结束 ${localDateFormatter.format(customFilter.endDate)}",
                        onClick = { endPicker.show() },
                    )
                    GhostButton(
                        label = "重置自定义",
                        onClick = {
                            val today = LocalDate.now()
                            onUpdateCustomTrackFilter(
                                today.minusDays(6),
                                today,
                            )
                            onApplyCustomTrackFilter()
                        },
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TinyToken("总数", trackBrowserUiState.allSummaries.size.toString())
                    TinyToken("筛选后", trackBrowserUiState.filteredSummaries.size.toString())
                    TinyToken("加载", if (trackBrowserUiState.isLoadingList) "进行中" else "完成", trackBrowserUiState.isLoadingList)
                }
            }
        }

        item {
            if (trackBrowserUiState.filteredSummaries.isEmpty()) {
                EmptyTrackPanel()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trackBrowserUiState.filteredSummaries.forEach { session ->
                        SessionRow(
                            session = session,
                            onClick = { onOpenTrackSession(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackDetailPage(
    session: GpsSession?,
    isLoading: Boolean,
    isExporting: Boolean,
    onBack: () -> Unit,
    onExportTrackSession: (TrackExportFormat) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .background(OsmoCanvas)
            .statusBarsPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ConsolePanel(
                eyebrow = "详情",
                title = session?.cameraName ?: "轨迹详情",
                headerAction = {
                    HeaderActionButton(
                        label = "返回",
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        onClick = onBack,
                    )
                },
            ) {
                if (isLoading) {
                    Text(
                        text = "正在读取轨迹详情...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OsmoInkMuted,
                    )
                } else if (session == null) {
                    Text(
                        text = "轨迹不存在或已被清理。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OsmoInkMuted,
                    )
                } else {
                    val summary = session.toSummary()
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TinyToken("开始", MainViewModel.formatSessionDateTime(session.startedAtMs))
                        TinyToken("结束", MainViewModel.formatSessionDateTime(session.endedAtMs))
                        TinyToken("模式", MainViewModel.formatSessionModeLabel(session.recordModeLabel))
                        TinyToken("点数", summary.sampleCount.toString())
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricTile("距离", summary.distanceLabel)
                        MetricTile("时长", formatDuration(session.durationSeconds))
                        MetricTile("均速", "%.1f 公里/时".format(Locale.US, summary.averageSpeedKmh))
                        MetricTile("峰值", "%.1f 公里/时".format(Locale.US, summary.maxSpeedKmh))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            label = if (isExporting) "导出中..." else "导出 GPX",
                            enabled = !isExporting,
                            onClick = { onExportTrackSession(TrackExportFormat.GPX) },
                        )
                        GhostButton(
                            label = if (isExporting) "导出中..." else "导出 TCX",
                            enabled = !isExporting,
                            onClick = { onExportTrackSession(TrackExportFormat.TCX) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPanel(
    connectionState: CameraConnectionState,
    connectedCamera: String?,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ConsolePanel(
        eyebrow = "总览",
        title = "",
        headerAction = {
            HeaderActionButton(
                label = "设置",
                icon = Icons.Rounded.Settings,
                onClick = onOpenSettings,
            )
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateBadge(connectionState)
            TinyToken("相机", connectedCamera ?: "--")
        }

        if (connectedCamera != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(label = "断开连接", onClick = onDisconnect)
            }
        }
    }
}

@Composable
private fun TrackEntryPanel(
    recentSession: GpsSessionSummary?,
    onOpenTrackList: () -> Unit,
) {
    ConsolePanel(
        eyebrow = "轨迹",
        title = "",
        headerAction = {
            HeaderActionButton(
                label = "查看轨迹",
                icon = Icons.Rounded.Map,
                onClick = onOpenTrackList,
            )
        },
    ) {
        if (recentSession == null) {
            Text(
                text = "还没有轨迹记录。开启 GPS 同步并开始录像后会自动沉淀轨迹。",
                style = MaterialTheme.typography.bodyMedium,
                color = OsmoInkMuted,
            )
        } else {
            Text(
                text = "${recentSession.cameraName} · ${MainViewModel.formatSessionTime(recentSession.startedAtMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = OsmoInk,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinyToken("距离", recentSession.distanceLabel, true)
                TinyToken("模式", MainViewModel.formatSessionModeLabel(recentSession.recordModeLabel))
                TinyToken("时长", formatDuration(recentSession.durationSeconds))
                TinyToken("点数", recentSession.sampleCount.toString())
            }
        }
    }
}

@Composable
private fun SettingsSummaryPanel(
    uiState: RemoteControlUiState,
    hasBluetoothPermission: Boolean,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onOpenNotificationChannelSettings: () -> Unit,
    onBackHome: () -> Unit,
) {
    ConsolePanel(
        eyebrow = "设置",
        title = "配对与同步",
        headerAction = {
            HeaderActionButton(
                label = "主页",
                icon = Icons.Rounded.Home,
                onClick = onBackHome,
            )
        },
    ) {
        Text(
            text = "扫描配对、权限、同步和轨迹记录集中在这里，主页只保留高频控制。",
            style = MaterialTheme.typography.bodyMedium,
            color = OsmoInkMuted,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyToken("遥控", uiState.controllerLabel.ifBlank { "--" })
            TinyToken("设备", uiState.scannedDevices.size.toString())
            TinyToken("同步", if (uiState.gpsSyncEnabled) "已开" else "已关", uiState.gpsSyncEnabled)
            TinyToken("轨迹", uiState.activeTrackPoints.toString())
            TinyToken("后台", if (uiState.backgroundServiceActive) "保持中" else "未保持", uiState.backgroundServiceActive)
            PermissionBadge("蓝牙", hasBluetoothPermission)
            PermissionBadge("定位", hasLocationPermission)
            PermissionBadge("通知", hasNotificationPermission)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.connectionState == CameraConnectionState.Scanning) {
                GhostButton(label = "停止扫描", onClick = onStopScan)
            } else {
                PrimaryButton(label = "扫描配对相机", onClick = onScan)
            }
            GhostButton(label = "打开通知频道设置", onClick = onOpenNotificationChannelSettings)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyToken("频道", if (uiState.notificationChannelImportance != null) "已创建" else "未创建", uiState.notificationChannelImportance != null)
            TinyToken("可用", if (uiState.notificationChannelEnabled) "已开" else "已关", uiState.notificationChannelEnabled)
            TinyToken("锁屏", if (uiState.notificationChannelVisibleOnLockScreen) "公开" else "受限", uiState.notificationChannelVisibleOnLockScreen)
            TinyToken("级别", notificationImportanceLabel(uiState.notificationChannelImportance))
        }
    }
}

@Composable
private fun GpsSyncPanel(
    uiState: RemoteControlUiState,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundLocationPermission: Boolean,
    onToggleGpsSync: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationChannelSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val latestLocation = uiState.latestLocation

    ConsolePanel(
        eyebrow = "同步",
        title = "GPS 轨迹同步",
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = OsmoSurface2,
            border = BorderStroke(1.dp, OsmoHairline),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "录像时同步手机定位到相机，并自动沉淀轨迹记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OsmoInk,
                    )
                    Text(
                        text = if (hasLocationPermission) "已获得定位权限" else "未获得定位权限，开启前需要授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = OsmoInkMuted,
                    )
                }

                Switch(
                    checked = uiState.gpsSyncEnabled,
                    onCheckedChange = onToggleGpsSync,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OsmoInk,
                        checkedTrackColor = OsmoAccent,
                        uncheckedThumbColor = OsmoInkMuted,
                        uncheckedTrackColor = OsmoSurface1,
                        uncheckedBorderColor = OsmoHairlineStrong,
                    ),
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyToken("状态", if (uiState.gpsSyncEnabled) "运行中" else "未开启", uiState.gpsSyncEnabled)
            TinyToken("权限", if (hasLocationPermission) "已授权" else "未授权", hasLocationPermission)
            TinyToken("通知", if (hasNotificationPermission) "已授权" else "未授权", hasNotificationPermission)
            TinyToken("后台", if (uiState.backgroundServiceActive) "前台服务中" else "未保持", uiState.backgroundServiceActive)
            TinyToken("全时定位", if (hasBackgroundLocationPermission) "已授权" else "建议开启", hasBackgroundLocationPermission)
            TinyToken("点数", uiState.activeTrackPoints.toString())
            TinyToken(
                "速度",
                latestLocation?.let { "%.1f 公里/时".format(Locale.US, it.speedKmh) } ?: "--",
            )
            TinyToken(
                "精度",
                latestLocation?.let { "%.1f 米".format(Locale.US, it.accuracyMeters) } ?: "--",
            )
            TinyToken("来源", providerLabel(latestLocation?.provider))
        }

        Text(
            text = "通知授权不等于允许锁屏显示。三星、OPPO、vivo 等系统里，通常还要在通知频道中开启锁屏通知、显示详情或允许展开操作。",
            style = MaterialTheme.typography.bodySmall,
            color = OsmoInkMuted,
        )

        if (!hasLocationPermission) {
            GhostButton(
                label = "授权并开启同步",
                onClick = onRequestLocationPermission,
            )
        }

        if (!hasNotificationPermission) {
            GhostButton(
                label = "授权通知，启用锁屏通知操作",
                onClick = onRequestNotificationPermission,
            )
        }

        GhostButton(
            label = "打开通知频道设置，排查锁屏显示",
            onClick = onOpenNotificationChannelSettings,
        )

        if (!hasBackgroundLocationPermission) {
            GhostButton(
                label = "打开系统设置，允许后台定位",
                onClick = onOpenAppSettings,
            )
        }
    }
}

@Composable
private fun QuickControlPanel(
    uiState: RemoteControlUiState,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onLockScreen: () -> Unit,
    onSwitchMode: (CameraMode) -> Unit,
) {
    val telemetry = uiState.telemetry

    ConsolePanel(
        eyebrow = "操作",
        title = "",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowActionButton(
                label = "拍照",
                icon = Icons.Rounded.Camera,
                accent = false,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = onCapturePhoto,
            )
            RowActionButton(
                label = if (telemetry.isRecording) "停止" else "录像",
                icon = Icons.Rounded.Videocam,
                accent = true,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = onToggleRecording,
            )
            RowActionButton(
                label = "休眠",
                icon = Icons.Rounded.PowerSettingsNew,
                accent = false,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = onLockScreen,
            )
        }

        Text(
            text = "拍摄模式",
            style = MaterialTheme.typography.labelLarge,
            color = OsmoInkMuted,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                label = "录像",
                selected = telemetry.mode == CameraMode.Video || telemetry.mode == CameraMode.LowLightVideo,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = { onSwitchMode(CameraMode.Video) },
            )
            ModeChip(
                label = "拍照",
                selected = telemetry.mode == CameraMode.Photo,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = { onSwitchMode(CameraMode.Photo) },
            )
            ModeChip(
                label = "延时",
                selected = telemetry.mode == CameraMode.Hyperlapse,
                enabled = uiState.connectionState == CameraConnectionState.Ready,
                onClick = { onSwitchMode(CameraMode.Hyperlapse) },
            )
        }
    }
}

@Composable
private fun LiveStatusPanel(uiState: RemoteControlUiState) {
    val telemetry = uiState.telemetry
    val latestLocation = uiState.latestLocation

    ConsolePanel(
        eyebrow = "状态",
        title = uiState.connectedCamera?.displayName ?: telemetry.modelLabel,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile("模式", telemetry.modeLabel)
            MetricTile("状态", telemetry.workState.label)
            MetricTile("参数", telemetry.paramLabel)
            MetricTile("电量", if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "--")
            MetricTile("已录", formatDuration(telemetry.recordTimeSeconds.toLong()))
            MetricTile("剩余", formatDuration(telemetry.remainingTimeSeconds))
            MetricTile("张数", telemetry.remainingPhotoCount.toString())
            MetricTile("轨迹", uiState.activeTrackPoints.toString())
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyToken(
                "坐标",
                latestLocation?.let {
                    "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude)
                } ?: "--",
            )
            TinyToken(
                "海拔",
                latestLocation?.let { "%.1f 米".format(Locale.US, it.altitudeMeters) } ?: "--",
            )
            TinyToken(
                "来源",
                providerLabel(latestLocation?.provider),
            )
            TinyToken("温度", tempStateLabel(telemetry.tempState), telemetry.tempState >= 2)
        }
    }
}

@Composable
private fun ConnectionHistoryPanel(
    connections: List<ConnectionHistoryEntry>,
    connectionState: CameraConnectionState,
    connectedCamera: ScannedCamera?,
    onConnect: (String) -> Unit,
    onScan: () -> Unit,
) {
    ConsolePanel(
        eyebrow = "历史",
        title = "",
        headerAction = {
            HeaderActionButton(
                label = "刷新",
                icon = Icons.Rounded.Refresh,
                onClick = onScan,
            )
        },
    ) {
        if (connections.isEmpty()) {
            Text(
                text = "还没有连接历史，先扫描并连接一台相机。",
                style = MaterialTheme.typography.bodyMedium,
                color = OsmoInkMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                connections.forEach { connection ->
                    ConnectionHistoryRow(
                        connection = connection,
                        connectionState = connectionState,
                        isConnected = connectedCamera?.address == connection.address,
                        onConnect = { onConnect(connection.address) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceScannerPanel(
    devices: List<ScannedCamera>,
    connectionState: CameraConnectionState,
    connectedCamera: ScannedCamera?,
    onConnect: (String) -> Unit,
) {
    ConsolePanel(
        eyebrow = "设备",
        title = "附近相机",
    ) {
        if (devices.isEmpty()) {
            TinyToken("设备", "0")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        connectionState = connectionState,
                        isConnected = connectedCamera?.address == device.address,
                        onConnect = { onConnect(device.address) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTrackPanel() {
    ConsolePanel(
        eyebrow = "记录",
        title = "暂无轨迹",
    ) {
        Text(
            text = "当前筛选下没有轨迹。你可以调整时间范围，或者先开始一次带 GPS 同步的录像。",
            style = MaterialTheme.typography.bodyMedium,
            color = OsmoInkMuted,
        )
    }
}

@Composable
private fun ConsolePanel(
    eyebrow: String,
    title: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = OsmoSurface1,
        border = BorderStroke(1.dp, OsmoHairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                        color = OsmoInkSubtle,
                    )
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = OsmoInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                headerAction?.let { currentHeaderAction ->
                    Box(
                        modifier = Modifier.padding(start = 12.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        currentHeaderAction()
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun HeaderActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = OsmoCanvas,
            contentColor = OsmoInk,
        ),
        border = BorderStroke(1.dp, OsmoHairlineStrong),
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null)
        Text(text = label, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OsmoSurface2,
        border = BorderStroke(1.dp, OsmoHairline),
    ) {
        Column(
            modifier = Modifier
                .width(112.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelMedium,
                color = OsmoInkSubtle,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                color = OsmoInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: ScannedCamera,
    connectionState: CameraConnectionState,
    isConnected: Boolean,
    onConnect: () -> Unit,
) {
    val deviceState = deviceRealtimeState(
        connectionState = connectionState,
        isConnected = isConnected,
    )
    val actionState = connectionActionState(
        connectionState = connectionState,
        isConnected = isConnected,
        idleLabel = "连接",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OsmoSurface2,
        border = BorderStroke(1.dp, OsmoHairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = OsmoInk,
                )
                Text(
                    text = "${device.address} · 信号 ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OsmoInkMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TinyToken("状态", deviceState.label, deviceState.highlighted)
                    TinyToken("强度", device.signalLabel, device.rssi >= -70)
                    TinyToken("刷新", relativeSeenLabel(device.lastSeenAtMs))
                }
            }

            GhostButton(
                label = actionState.label,
                enabled = actionState.enabled,
                onClick = onConnect,
            )
        }
    }
}

@Composable
private fun ConnectionHistoryRow(
    connection: ConnectionHistoryEntry,
    connectionState: CameraConnectionState,
    isConnected: Boolean,
    onConnect: () -> Unit,
) {
    val deviceState = deviceRealtimeState(
        connectionState = connectionState,
        isConnected = isConnected,
    )
    val actionState = connectionActionState(
        connectionState = connectionState,
        isConnected = isConnected,
        idleLabel = "重连",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OsmoSurface2,
        border = BorderStroke(1.dp, OsmoHairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = connection.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = OsmoInk,
                )
                Text(
                    text = "${connection.address} · ${MainViewModel.formatSessionTime(connection.connectedAtMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OsmoInkMuted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TinyToken("状态", deviceState.label, deviceState.highlighted)
                    TinyToken("上次", relativeHistoryLabel(connection.connectedAtMs))
                }
            }

            GhostButton(
                label = actionState.label,
                enabled = actionState.enabled,
                onClick = onConnect,
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: GpsSessionSummary,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OsmoSurface2,
        border = BorderStroke(1.dp, OsmoHairline),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = session.cameraName,
                        style = MaterialTheme.typography.titleMedium,
                        color = OsmoInk,
                    )
                    Text(
                        text = "${MainViewModel.formatSessionTime(session.startedAtMs)} · ${MainViewModel.formatSessionModeLabel(session.recordModeLabel)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OsmoInkMuted,
                    )
                }
                Text(
                    text = session.distanceLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = OsmoAccent,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinyToken("时长", formatDuration(session.durationSeconds))
                TinyToken("均速", "%.1f 公里/时".format(Locale.US, session.averageSpeedKmh))
                TinyToken("峰值", "%.1f 公里/时".format(Locale.US, session.maxSpeedKmh))
                TinyToken("点数", session.sampleCount.toString())
            }
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (accent) {
        ButtonDefaults.buttonColors(
            containerColor = OsmoAccent,
            contentColor = OsmoInk,
            disabledContainerColor = OsmoSurface2,
            disabledContentColor = OsmoInkSubtle,
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            containerColor = OsmoSurface1,
            contentColor = OsmoInk,
            disabledContainerColor = OsmoSurface1,
            disabledContentColor = OsmoInkSubtle,
        )
    }

    if (accent) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null)
            Text(
                text = label,
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            border = BorderStroke(1.dp, OsmoHairlineStrong),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null)
            Text(
                text = label,
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppearancePanel(
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    ConsolePanel(
        eyebrow = "外观",
        title = "界面主题",
    ) {
        Text(
            text = "支持跟随系统，也可以固定为亮色或暗色。",
            style = MaterialTheme.typography.bodyMedium,
            color = OsmoInkMuted,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ModeChip(
                    label = themeModeLabel(mode),
                    selected = themeMode == mode,
                    enabled = true,
                    onClick = { onSetThemeMode(mode) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.RowActionButton(
    label: String,
    icon: ImageVector,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ActionButton(
        modifier = Modifier.weight(1f),
        label = label,
        icon = icon,
        accent = accent,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) OsmoSurface2 else OsmoCanvas,
        border = BorderStroke(1.dp, if (selected) OsmoAccent else OsmoHairlineStrong),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) OsmoInk else OsmoInkSubtle,
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ModeChip(
        label = label,
        selected = selected,
        enabled = true,
        onClick = onClick,
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OsmoAccent,
            contentColor = OsmoInk,
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun GhostButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OsmoCanvas,
        border = BorderStroke(1.dp, OsmoHairlineStrong),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) OsmoInk else OsmoInkSubtle,
            )
        }
    }
}

@Composable
private fun PermissionBadge(label: String, granted: Boolean) {
    TinyToken(label, if (granted) "已开" else "未开", granted)
}

@Composable
private fun StateBadge(state: CameraConnectionState) {
    val color = when (state) {
        CameraConnectionState.Ready -> OsmoSuccess
        CameraConnectionState.Scanning,
        CameraConnectionState.GattConnecting,
        CameraConnectionState.Handshaking,
        -> OsmoAccent

        CameraConnectionState.Error -> Color(0xFFD26A5E)
        else -> OsmoInkSubtle
    }

    Surface(
        shape = CircleShape,
        color = OsmoSurface2,
        border = BorderStroke(1.dp, OsmoHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(color = color, shape = CircleShape)
                    .border(1.dp, OsmoHairlineStrong, CircleShape)
                    .width(10.dp)
                    .padding(5.dp),
            )
            Text(
                text = connectionStateLabel(state),
                style = MaterialTheme.typography.labelLarge,
                color = OsmoInk,
            )
        }
    }
}

@Composable
private fun TinyToken(
    label: String,
    value: String,
    highlighted: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) OsmoSurface2 else OsmoCanvas,
        border = BorderStroke(1.dp, if (highlighted) OsmoAccent else OsmoHairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelMedium,
                color = OsmoInkSubtle,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = OsmoInk,
            )
        }
    }
}

private fun connectionStateLabel(state: CameraConnectionState): String = when (state) {
    CameraConnectionState.Idle -> "待机"
    CameraConnectionState.BluetoothUnavailable -> "无蓝牙"
    CameraConnectionState.BluetoothDisabled -> "蓝牙关闭"
    CameraConnectionState.Scanning -> "扫描中"
    CameraConnectionState.GattConnecting -> "连接中"
    CameraConnectionState.GattConnected -> "链路已就绪"
    CameraConnectionState.Handshaking -> "握手中"
    CameraConnectionState.Ready -> "已连接"
    CameraConnectionState.Disconnecting -> "断开中"
    CameraConnectionState.Error -> "异常"
}

private fun tempStateLabel(tempState: Int): String = when (tempState) {
    1 -> "偏高"
    2 -> "过热"
    3 -> "高温保护"
    else -> "正常"
}

private fun providerLabel(provider: String?): String = when (provider?.lowercase(Locale.ROOT)) {
    null,
    "",
    -> "--"
    "gps" -> "卫星"
    "network" -> "网络"
    "fused" -> "融合"
    "passive" -> "被动"
    else -> provider
}

private fun notificationImportanceLabel(importance: Int?): String = when (importance) {
    null -> "--"
    0 -> "已禁用"
    1 -> "最低"
    2 -> "低"
    3 -> "默认"
    4 -> "高"
    5 -> "最高"
    else -> importance.toString()
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "跟随系统"
    ThemeMode.Light -> "亮色"
    ThemeMode.Dark -> "暗色"
}

private fun formatDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val minutes = safe / 60
    val seconds = safe % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private data class DeviceRealtimeState(
    val label: String,
    val highlighted: Boolean,
)

private fun deviceRealtimeState(
    connectionState: CameraConnectionState,
    isConnected: Boolean,
): DeviceRealtimeState {
    if (isConnected) {
        return when (connectionState) {
            CameraConnectionState.Ready -> DeviceRealtimeState("已连接", true)
            CameraConnectionState.Handshaking -> DeviceRealtimeState("握手中", true)
            CameraConnectionState.GattConnecting,
            CameraConnectionState.GattConnected,
            -> DeviceRealtimeState("连接中", true)

            CameraConnectionState.Disconnecting -> DeviceRealtimeState("断开中", false)
            CameraConnectionState.Error -> DeviceRealtimeState("异常", false)
            else -> DeviceRealtimeState("可连接", false)
        }
    }

    return when (connectionState) {
        CameraConnectionState.Scanning -> DeviceRealtimeState("扫描中", false)
        else -> DeviceRealtimeState("可连接", false)
    }
}

private data class ConnectionActionState(
    val label: String,
    val enabled: Boolean,
)

private fun connectionActionState(
    connectionState: CameraConnectionState,
    isConnected: Boolean,
    idleLabel: String,
): ConnectionActionState {
    if (!isConnected) {
        return ConnectionActionState(label = idleLabel, enabled = true)
    }

    return when (connectionState) {
        CameraConnectionState.GattConnecting,
        CameraConnectionState.GattConnected,
        CameraConnectionState.Handshaking,
        -> ConnectionActionState(label = "连接中", enabled = false)

        CameraConnectionState.Ready -> ConnectionActionState(label = "已连接", enabled = false)
        CameraConnectionState.Disconnecting -> ConnectionActionState(label = "断开中", enabled = false)
        else -> ConnectionActionState(label = idleLabel, enabled = true)
    }
}

private fun relativeSeenLabel(lastSeenAtMs: Long): String {
    val elapsedMs = (System.currentTimeMillis() - lastSeenAtMs).coerceAtLeast(0L)
    return when {
        elapsedMs < 1_500L -> "刚刚"
        elapsedMs < 60_000L -> "${elapsedMs / 1000}s"
        else -> "${elapsedMs / 60_000}m"
    }
}

private fun relativeHistoryLabel(timestampMs: Long): String {
    val elapsedMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    return when {
        elapsedMs < 60_000L -> "刚刚"
        elapsedMs < 3_600_000L -> "${elapsedMs / 60_000} 分钟前"
        elapsedMs < 86_400_000L -> "${elapsedMs / 3_600_000} 小时前"
        else -> "${elapsedMs / 86_400_000} 天前"
    }
}
