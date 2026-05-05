package com.unianx.osmo.remotecontrol.ble

import java.util.Locale

enum class CameraConnectionState {
    Idle,
    BluetoothUnavailable,
    BluetoothDisabled,
    Scanning,
    GattConnecting,
    GattConnected,
    Handshaking,
    Ready,
    Disconnecting,
    Error,
}

data class ScannedCamera(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeenAtMs: Long,
) {
    val displayName: String
        get() = name.ifBlank { "DJI 相机 ${address.takeLast(5)}" }

    val signalLabel: String
        get() = when {
            rssi >= -55 -> "很近"
            rssi >= -70 -> "稳定"
            rssi >= -82 -> "较弱"
            else -> "边缘"
        }
}

enum class CameraMode(val code: Int, val label: String) {
    SlowMotion(0x00, "慢动作"),
    Video(0x01, "录像"),
    Timelapse(0x02, "延时摄影"),
    Photo(0x05, "拍照"),
    Hyperlapse(0x0A, "移动延时"),
    Livestream(0x1A, "直播"),
    UvcLivestream(0x23, "UVC 直播"),
    LowLightVideo(0x28, "夜景录像"),
    SubjectTracking(0x34, "跟拍"),
    PanoramaVideo360(0x38, "360 全景"),
    Hyperlapse360(0x3A, "360 移动延时"),
    Selfie360(0x3C, "360 自拍"),
    PanoramaPhoto360(0x3F, "360 拍照"),
    BoostVideo360(0x41, "增稳"),
    Vortex360(0x43, "旋转"),
    SuperNight360(0x44, "360 夜景"),
    SingleLensNight360(0x4A, "单镜头夜景"),
    Unknown(-1, "未知"),
    ;

    companion object {
        fun fromCode(code: Int): CameraMode = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

enum class CameraWorkState(val code: Int, val label: String) {
    ScreenOff(0x00, "屏幕关闭"),
    Live(0x01, "实时预览"),
    Playback(0x02, "回放"),
    Busy(0x03, "拍摄中 / 录制中"),
    PreRecording(0x05, "预录制"),
    Unknown(-1, "未知"),
    ;

    companion object {
        fun fromCode(code: Int): CameraWorkState = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

data class CameraTelemetry(
    val cameraDeviceIdRaw: Int? = null,
    val modelLabel: String = "未连接",
    val mode: CameraMode = CameraMode.Unknown,
    val workState: CameraWorkState = CameraWorkState.Unknown,
    val resolutionLabel: String = "--",
    val fpsLabel: String = "--",
    val recordTimeSeconds: Int = 0,
    val remainingTimeSeconds: Long = 0L,
    val remainingPhotoCount: Long = 0L,
    val batteryPercent: Int = -1,
    val timelapseIntervalTenthSeconds: Int = 0,
    val userMode: Int = 0,
    val powerMode: Int = 0,
    val tempState: Int = 0,
    val newModeName: String? = null,
    val newModeParam: String? = null,
) {
    val isRecording: Boolean
        get() = workState == CameraWorkState.Busy || workState == CameraWorkState.PreRecording

    val modeLabel: String
        get() = newModeName?.takeIf { it.isNotBlank() }?.let(::localizedModeLabel) ?: mode.label

    val paramLabel: String
        get() = newModeParam?.takeIf { it.isNotBlank() }?.let(::localizedModeParamLabel)
            ?: "$resolutionLabel · $fpsLabel"
}

data class DjiProtocolFrame(
    val version: Int,
    val frameLength: Int,
    val cmdType: Int,
    val seq: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
) {
    val isResponse: Boolean
        get() = cmdType and 0x20 != 0
}

data class ConnectionRequestPayload(
    val deviceIdRaw: Int,
    val verifyMode: Int,
    val verifyData: Int,
)

data class ConnectionResponsePayload(
    val deviceIdRaw: Int,
    val retCode: Int,
)

data class CameraStatusPayload(
    val cameraMode: Int,
    val cameraStatus: Int,
    val videoResolution: Int,
    val fpsIndex: Int,
    val recordTimeSeconds: Int,
    val timelapseIntervalTenthSeconds: Int,
    val remainingPhotoCount: Long,
    val remainingTimeSeconds: Long,
    val userMode: Int,
    val powerMode: Int,
    val tempState: Int,
    val batteryPercent: Int,
)

data class NewCameraStatusPayload(
    val modeName: String,
    val modeParam: String,
)

internal fun modelLabelForDeviceId(rawDeviceId: Int?): String {
    if (rawDeviceId == null) return "未连接"

    val normalized = normalizeDeviceModelId(rawDeviceId)
    return when (normalized) {
        0xFF33 -> "Osmo Action 4"
        0xFF44 -> "Osmo Action 5 Pro"
        0xFF55 -> "Osmo Action 6"
        0xFF66 -> "Osmo 360"
        else -> "DJI 相机 ${normalized.toString(16).uppercase(Locale.US)}"
    }
}

internal fun localizedModeLabel(raw: String): String {
    val normalized = raw.trim()
    return when (normalized.lowercase(Locale.ROOT)) {
        "slow motion",
        "slowmotion",
        -> "慢动作"
        "video" -> "录像"
        "timelapse" -> "延时摄影"
        "photo" -> "拍照"
        "hyperlapse" -> "移动延时"
        "live" -> "直播"
        "uvc live" -> "UVC 直播"
        "low-light",
        "low light",
        -> "夜景录像"
        "tracking",
        "subject tracking",
        -> "跟拍"
        "360 panorama" -> "360 全景"
        "360 hyperlapse" -> "360 移动延时"
        "360 selfie" -> "360 自拍"
        "360 photo" -> "360 拍照"
        "boost" -> "增稳"
        "vortex" -> "旋转"
        "360 night" -> "360 夜景"
        "single lens night" -> "单镜头夜景"
        "screen off" -> "屏幕关闭"
        "live view" -> "实时预览"
        "playback" -> "回放"
        "capturing / recording" -> "拍摄中 / 录制中"
        "pre-recording",
        "pre recording",
        -> "预录制"
        "unknown" -> "未知"
        else -> normalized
    }
}

internal fun localizedModeParamLabel(raw: String): String {
    val normalized = raw.trim()
    if (normalized.isBlank()) return normalized

    return when (normalized.lowercase(Locale.ROOT)) {
        "photo l" -> "照片大"
        "photo m" -> "照片中"
        "photo standard" -> "照片标准"
        "single" -> "单拍"
        else -> {
            val burstMatch = Regex("""(\d+)-shot burst""", RegexOption.IGNORE_CASE).matchEntire(normalized)
            when {
                burstMatch != null -> "${burstMatch.groupValues[1]} 张连拍"
                normalized.startsWith("Code ", ignoreCase = true) -> "代码 ${normalized.substringAfter(' ')}"
                else -> normalized
            }
        }
    }
}

internal fun normalizeDeviceModelId(rawDeviceId: Int): Int {
    val low16 = rawDeviceId and 0xFFFF
    val high16 = rawDeviceId ushr 16

    return when {
        low16 in knownDeviceIds -> low16
        high16 in knownDeviceIds -> high16
        else -> rawDeviceId and 0xFFFF
    }
}

private val knownDeviceIds = setOf(0xFF33, 0xFF44, 0xFF55, 0xFF66)
