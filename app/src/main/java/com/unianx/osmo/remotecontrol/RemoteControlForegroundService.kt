package com.unianx.osmo.remotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.unianx.osmo.remotecontrol.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RemoteControlForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: RemoteControlController
        get() = (application as RemoteControlApp).remoteControlController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scope.launch {
            controller.uiState.collectLatest { state ->
                if (!controller.shouldKeepForegroundServiceRunning()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                promoteToForeground(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_PHOTO -> controller.capturePhoto()
            ACTION_TOGGLE_RECORDING -> controller.toggleRecording()
            ACTION_LOCK_SCREEN -> controller.lockScreen()
            ACTION_OPEN_APP, null -> Unit
            else -> AppLogger.w("RemoteControlForeground", "unknown action=${intent.action}")
        }

        if (controller.shouldKeepForegroundServiceRunning()) {
            promoteToForeground(controller.uiState.value)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(state: RemoteControlUiState) {
        val notification = buildNotification(state)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            resolveForegroundTypes(state),
        )
    }

    private fun buildNotification(state: RemoteControlUiState): Notification {
        val connectedCamera = state.connectedCamera?.displayName ?: "Osmo 控制台"
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_APP
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val recordingLabel = if (state.telemetry.isRecording) "停止录像" else "开始录像"
        val summary = buildString {
            append(state.telemetry.workState.label)
            append(" · ")
            append(state.telemetry.modeLabel)
            if (state.telemetry.recordTimeSeconds > 0) {
                append(" · 已录 ")
                append(formatDuration(state.telemetry.recordTimeSeconds.toLong()))
            }
            if (state.gpsSyncEnabled) {
                append(" · 轨迹 ")
                append(state.activeTrackPoints)
                append(" 点")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(connectedCamera)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                "拍照",
                servicePendingIntent(ACTION_CAPTURE_PHOTO, REQUEST_CAPTURE_PHOTO),
            )
            .addAction(
                0,
                recordingLabel,
                servicePendingIntent(ACTION_TOGGLE_RECORDING, REQUEST_TOGGLE_RECORDING),
            )
            .addAction(
                0,
                "锁屏",
                servicePendingIntent(ACTION_LOCK_SCREEN, REQUEST_LOCK_SCREEN),
            )
            .build()
    }

    private fun resolveForegroundTypes(state: RemoteControlUiState): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (state.gpsSyncEnabled && applicationContext.hasPermissions(gpsPermissions())) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RemoteControlForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Osmo 后台控制",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持后台轨迹同步，并在锁屏上提供相机操作控件"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatDuration(durationSeconds: Long): String {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val CHANNEL_ID = "remote_control_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_OPEN_APP = "com.unianx.osmo.remotecontrol.action.OPEN_APP"
        private const val ACTION_CAPTURE_PHOTO = "com.unianx.osmo.remotecontrol.action.CAPTURE_PHOTO"
        private const val ACTION_TOGGLE_RECORDING = "com.unianx.osmo.remotecontrol.action.TOGGLE_RECORDING"
        private const val ACTION_LOCK_SCREEN = "com.unianx.osmo.remotecontrol.action.LOCK_SCREEN"
        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_CAPTURE_PHOTO = 2
        private const val REQUEST_TOGGLE_RECORDING = 3
        private const val REQUEST_LOCK_SCREEN = 4

        fun start(context: Context) {
            val intent = Intent(context, RemoteControlForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlForegroundService::class.java))
        }
    }
}
