package com.unianx.osmo.remotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
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
        runCatching {
            ensureNotificationChannel(this)
        }.onFailure { throwable ->
            reportForegroundServiceFailure("create notification channel", throwable)
        }
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
            else -> AppLogger.w(TAG, "unknown action=${intent.action}")
        }

        if (controller.shouldKeepForegroundServiceRunning()) {
            promoteToForeground(controller.uiState.value)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(state: RemoteControlUiState) {
        val notification = runCatching {
            buildNotification(state)
        }.getOrElse { throwable ->
            reportForegroundServiceFailure("build foreground notification", throwable)
            stopSelf()
            return
        }

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                resolveForegroundTypes(state),
            )
        }.onFailure { throwable ->
            reportForegroundServiceFailure("promote to foreground", throwable)
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }

    private fun buildNotification(state: RemoteControlUiState): Notification {
        val connectedCamera = state.connectedCamera?.displayName ?: "Osmo 控制台"
        val recordingLabel = if (state.telemetry.isRecording) "停止录像" else "开始录像"
        val summary = buildSummary(state)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(connectedCamera)
            .setContentText(summary)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                notificationAction(
                    icon = android.R.drawable.presence_video_online,
                    title = recordingLabel,
                    action = ACTION_TOGGLE_RECORDING,
                    requestCode = REQUEST_TOGGLE_RECORDING,
                ),
            )
            .addAction(
                notificationAction(
                    icon = android.R.drawable.ic_menu_camera,
                    title = "拍照",
                    action = ACTION_CAPTURE_PHOTO,
                    requestCode = REQUEST_CAPTURE_PHOTO,
                ),
            )
            .addAction(
                notificationAction(
                    icon = android.R.drawable.ic_lock_power_off,
                    title = "休眠相机",
                    action = ACTION_LOCK_SCREEN,
                    requestCode = REQUEST_LOCK_SCREEN,
                ),
            )
            .build()
    }

    private fun buildSummary(state: RemoteControlUiState): String {
        return buildString {
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
    }

    private fun resolveForegroundTypes(state: RemoteControlUiState): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (state.gpsSyncEnabled && applicationContext.hasPermissions(gpsPermissions())) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun notificationAction(
        icon: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): Notification.Action {
        return Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            title,
            servicePendingIntent(action, requestCode),
        ).build()
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

    private fun openAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_APP
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reportForegroundServiceFailure(operation: String, throwable: Throwable) {
        AppLogger.w(TAG, "$operation failed", throwable)
        controller.reportForegroundServiceFailure(FOREGROUND_SERVICE_FAILURE_MESSAGE)
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
        const val CHANNEL_ID = "remote_control_foreground_v3"
        private const val TAG = "RemoteControlForeground"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_OPEN_APP = "com.unianx.osmo.remotecontrol.action.OPEN_APP"
        private const val ACTION_CAPTURE_PHOTO = "com.unianx.osmo.remotecontrol.action.CAPTURE_PHOTO"
        private const val ACTION_TOGGLE_RECORDING = "com.unianx.osmo.remotecontrol.action.TOGGLE_RECORDING"
        private const val ACTION_LOCK_SCREEN = "com.unianx.osmo.remotecontrol.action.LOCK_SCREEN"
        private const val FOREGROUND_SERVICE_FAILURE_MESSAGE = "后台保持通知启动失败，请检查通知权限、通知频道或系统后台限制"
        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_CAPTURE_PHOTO = 2
        private const val REQUEST_TOGGLE_RECORDING = 3
        private const val REQUEST_LOCK_SCREEN = 4

        fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Osmo 后台连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持相机后台连接，并提供通知快捷操作"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, RemoteControlForegroundService::class.java)
            runCatching {
                ensureNotificationChannel(context)
                context.startForegroundService(intent)
            }.onFailure { throwable ->
                AppLogger.w(TAG, "startForegroundService failed", throwable)
                (context.applicationContext as? RemoteControlApp)
                    ?.remoteControlController
                    ?.reportForegroundServiceFailure(FOREGROUND_SERVICE_FAILURE_MESSAGE)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, RemoteControlForegroundService::class.java))
            }.onFailure { throwable ->
                AppLogger.w(TAG, "stopService failed", throwable)
            }
        }
    }
}
