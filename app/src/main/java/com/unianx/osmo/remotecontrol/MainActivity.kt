package com.unianx.osmo.remotecontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unianx.osmo.remotecontrol.ui.RemoteControlScreen
import com.unianx.osmo.remotecontrol.ui.theme.OsmoRemoteControlTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsmoRemoteControlTheme {
                RemoteControlRoute(viewModel = viewModel)
            }
        }
    }
}

private sealed interface PermissionAction {
    data object Scan : PermissionAction
    data class Connect(val address: String) : PermissionAction
    data object EnableGps : PermissionAction
}

@Composable
private fun RemoteControlRoute(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bluetoothPermissions = remember { bluetoothPermissions() }
    val gpsPermissions = remember { gpsPermissions() }
    var pendingAction by remember { mutableStateOf<PermissionAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingAction
        pendingAction = null
        val bluetoothGranted = context.hasPermissions(bluetoothPermissions)
        val locationGranted = context.hasPermissions(gpsPermissions)

        when (action) {
            PermissionAction.Scan -> if (bluetoothGranted) viewModel.startScan()
            is PermissionAction.Connect -> if (bluetoothGranted) viewModel.connect(action.address)
            PermissionAction.EnableGps -> if (locationGranted) viewModel.setGpsSyncEnabled(true)
            null -> Unit
        }
    }

    val hasBluetoothPermission = context.hasPermissions(bluetoothPermissions)
    val hasLocationPermission = context.hasPermissions(gpsPermissions)

    RemoteControlScreen(
        uiState = uiState,
        hasBluetoothPermission = hasBluetoothPermission,
        hasLocationPermission = hasLocationPermission,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onCapturePhoto = viewModel::capturePhoto,
        onToggleRecording = viewModel::toggleRecording,
        onSwitchMode = viewModel::switchMode,
        onToggleGpsSync = viewModel::setGpsSyncEnabled,
        onRequestBluetoothPermission = {
            pendingAction = PermissionAction.Scan
            permissionLauncher.launch(bluetoothPermissions)
        },
        onRequestLocationPermission = {
            pendingAction = PermissionAction.EnableGps
            permissionLauncher.launch(gpsPermissions)
        },
        onDismissMessage = viewModel::dismissMessage,
    )
}

private fun bluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun gpsPermissions(): Array<String> {
    return arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}

private fun Context.hasPermissions(permissions: Array<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
