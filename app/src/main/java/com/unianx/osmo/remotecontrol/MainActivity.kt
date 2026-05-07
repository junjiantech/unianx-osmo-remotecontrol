package com.unianx.osmo.remotecontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unianx.osmo.remotecontrol.data.TrackBrowserUiState
import com.unianx.osmo.remotecontrol.logging.AppLogger
import com.unianx.osmo.remotecontrol.ui.RemoteControlScreen
import com.unianx.osmo.remotecontrol.ui.theme.OsmoAccent
import com.unianx.osmo.remotecontrol.ui.theme.OsmoCanvas
import com.unianx.osmo.remotecontrol.ui.theme.OsmoInk
import com.unianx.osmo.remotecontrol.ui.theme.OsmoInkMuted
import com.unianx.osmo.remotecontrol.ui.theme.OsmoRemoteControlTheme
import com.unianx.osmo.remotecontrol.ui.theme.OsmoSurface1
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("MainActivity", "onCreate")
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val trackBrowserUiState by viewModel.trackBrowserUiState.collectAsStateWithLifecycle()
            val darkTheme = uiState.themeMode.resolveDark(isSystemInDarkTheme())
            OsmoRemoteControlTheme(darkTheme = darkTheme) {
                val canvasColor = OsmoCanvas
                val useDarkSystemBarIcons = canvasColor.luminance() > 0.5f

                LaunchedEffect(uiState.themeMode) {
                    AppCompatDelegate.setDefaultNightMode(uiState.themeMode.toNightMode())
                }
                LaunchedEffect(canvasColor, useDarkSystemBarIcons) {
                    window.navigationBarColor = canvasColor.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = useDarkSystemBarIcons
                        isAppearanceLightNavigationBars = useDarkSystemBarIcons
                    }
                }
                RemoteControlRoute(
                    viewModel = viewModel,
                    uiState = uiState,
                    trackBrowserUiState = trackBrowserUiState,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNotificationSettings()
        viewModel.refreshTrackSummaries()
    }
}

private sealed interface PermissionAction {
    data object Scan : PermissionAction
    data class Connect(val address: String) : PermissionAction
    data object EnableGps : PermissionAction
    data object EnableNotifications : PermissionAction
}

@Composable
private fun RemoteControlRoute(
    viewModel: MainViewModel,
    uiState: RemoteControlUiState,
    trackBrowserUiState: TrackBrowserUiState,
) {
    val context = LocalContext.current
    val bluetoothPermissions = remember { bluetoothPermissions() }
    val gpsPermissions = remember { gpsPermissions() }
    val notificationPermissions = remember { notificationPermissions() }
    val backgroundLocationPermission = remember { backgroundLocationPermission() }
    var pendingAction by remember { mutableStateOf<PermissionAction?>(null) }
    var showLocationPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var locationPermissionPrompted by rememberSaveable { mutableStateOf(false) }
    var gpsSyncInitialized by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingAction
        pendingAction = null
        val bluetoothGranted = context.hasPermissions(bluetoothPermissions)
        val locationGranted = context.hasPermissions(gpsPermissions)
        val notificationsGranted = context.hasPermissions(notificationPermissions)
        AppLogger.i(
            "MainActivity",
            "permission result action=$action bluetoothGranted=$bluetoothGranted locationGranted=$locationGranted notificationsGranted=$notificationsGranted",
        )

        when (action) {
            PermissionAction.Scan -> if (bluetoothGranted) viewModel.startScan()
            is PermissionAction.Connect -> if (bluetoothGranted) viewModel.connect(action.address)
            PermissionAction.EnableGps -> if (locationGranted) viewModel.setGpsSyncEnabled(true)
            PermissionAction.EnableNotifications -> Unit
            null -> Unit
        }
    }

    val hasBluetoothPermission = context.hasPermissions(bluetoothPermissions)
    val hasLocationPermission = context.hasPermissions(gpsPermissions)
    val hasNotificationPermission = context.hasPermissions(notificationPermissions)
    val hasBackgroundLocationPermission = context.hasPermission(backgroundLocationPermission)

    LaunchedEffect(hasLocationPermission) {
        if (!gpsSyncInitialized && hasLocationPermission) {
            gpsSyncInitialized = true
            if (!uiState.gpsSyncEnabled) {
                AppLogger.i("MainActivity", "enable gps sync by default on app entry")
                viewModel.setGpsSyncEnabled(true)
            }
        }

        if (!locationPermissionPrompted && !hasLocationPermission) {
            locationPermissionPrompted = true
            showLocationPermissionDialog = true
        }
    }

    LaunchedEffect(trackBrowserUiState.pendingShareRequest) {
        val request = trackBrowserUiState.pendingShareRequest ?: return@LaunchedEffect
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(request.absolutePath),
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = request.format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "导出轨迹").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { throwable ->
            AppLogger.w("MainActivity", "share track export failed path=${request.absolutePath}", throwable)
        }
        viewModel.onTrackShareRequestHandled()
    }

    if (showLocationPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showLocationPermissionDialog = false },
            title = {
                Text(
                    text = "需要定位权限",
                    color = OsmoInk,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = "App 需要读取手机定位，用于将轨迹同步到 Osmo，并在录像时自动记录轨迹。授权后会默认开启 GPS 轨迹同步。",
                    color = OsmoInkMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationPermissionDialog = false
                        AppLogger.i("MainActivity", "request location permissions from entry dialog")
                        pendingAction = PermissionAction.EnableGps
                        permissionLauncher.launch(gpsPermissions)
                    },
                ) {
                    Text(text = "继续授权", color = OsmoAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationPermissionDialog = false }) {
                    Text(text = "暂不", color = OsmoInkMuted)
                }
            },
            containerColor = OsmoSurface1,
            tonalElevation = 0.dp,
        )
    }

    RemoteControlScreen(
        uiState = uiState,
        trackBrowserUiState = trackBrowserUiState,
        hasBluetoothPermission = hasBluetoothPermission,
        hasLocationPermission = hasLocationPermission,
        hasNotificationPermission = hasNotificationPermission,
        hasBackgroundLocationPermission = hasBackgroundLocationPermission,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onCapturePhoto = viewModel::capturePhoto,
        onToggleRecording = viewModel::toggleRecording,
        onLockScreen = viewModel::lockScreen,
        onSwitchMode = viewModel::switchMode,
        onToggleGpsSync = viewModel::setGpsSyncEnabled,
        onSetThemeMode = viewModel::setThemeMode,
        onSelectTrackFilter = viewModel::selectTrackFilter,
        onUpdateCustomTrackFilter = viewModel::updateCustomTrackFilter,
        onApplyCustomTrackFilter = viewModel::applyCustomTrackFilter,
        onOpenTrackSession = viewModel::selectTrackSession,
        onCloseTrackSession = viewModel::clearSelectedTrackSession,
        onExportTrackSession = viewModel::exportTrackSession,
        onDismissTrackBrowserMessage = viewModel::dismissTrackBrowserMessage,
        onRequestBluetoothPermissionForScan = {
            AppLogger.i("MainActivity", "request bluetooth permissions")
            pendingAction = PermissionAction.Scan
            permissionLauncher.launch(bluetoothPermissions)
        },
        onRequestBluetoothPermissionForConnect = { address ->
            AppLogger.i("MainActivity", "request bluetooth permissions for connect address=$address")
            pendingAction = PermissionAction.Connect(address)
            permissionLauncher.launch(bluetoothPermissions)
        },
        onRequestLocationPermission = {
            AppLogger.i("MainActivity", "request location permissions")
            pendingAction = PermissionAction.EnableGps
            permissionLauncher.launch(gpsPermissions)
        },
        onRequestNotificationPermission = {
            AppLogger.i("MainActivity", "request notification permissions")
            pendingAction = PermissionAction.EnableNotifications
            permissionLauncher.launch(notificationPermissions)
        },
        onOpenNotificationChannelSettings = {
            AppLogger.i("MainActivity", "open notification channel settings")
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, RemoteControlForegroundService.CHANNEL_ID)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        },
        onOpenAppSettings = {
            AppLogger.i("MainActivity", "open app settings")
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        },
        onDismissMessage = viewModel::dismissMessage,
    )
}
