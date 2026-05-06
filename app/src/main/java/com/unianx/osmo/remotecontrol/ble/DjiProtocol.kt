package com.unianx.osmo.remotecontrol.ble

import com.unianx.osmo.remotecontrol.data.ControllerIdentity
import com.unianx.osmo.remotecontrol.data.GpsSample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

object DjiProtocol {
    const val ServiceUuid16 = 0xFFF0
    const val NotifyUuid16 = 0xFFF4
    const val WriteUuid16 = 0xFFF5

    const val CmdNoResponse = 0x00
    const val CmdResponseOrNot = 0x01
    const val CmdWaitResult = 0x02
    const val AckNoResponse = 0x20

    const val CmdSetConnection = 0x00
    const val CmdIdConnection = 0x19
    const val CmdIdKeyReport = 0x11
    const val CmdIdGpsPush = 0x17
    const val CmdIdPowerMode = 0x1A
    const val CmdIdVersionQuery = 0x00

    const val CmdSetCamera = 0x1D
    const val CmdIdRecordControl = 0x03
    const val CmdIdModeSwitch = 0x04
    const val CmdIdStatusSubscription = 0x05
    const val CmdIdStatusPush = 0x02
    const val CmdIdNewStatusPush = 0x06

    const val KeyRecord = 0x01
    const val KeyQuickSwitch = 0x02
    const val KeySnapshot = 0x03

    private const val Sof = 0xAA
    private const val HeaderWithoutData = 14
    private const val TailLength = 4
    internal const val MinFrameLength = HeaderWithoutData + TailLength
    private const val MaxFrameLength = 0x03FF

    fun buildFrame(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        seq: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val totalLength = HeaderWithoutData + payload.size + TailLength
        val frame = ByteArray(totalLength)
        var offset = 0

        frame[offset++] = Sof.toByte()
        val versionAndLength = totalLength and 0x03FF
        frame[offset++] = (versionAndLength and 0xFF).toByte()
        frame[offset++] = ((versionAndLength shr 8) and 0xFF).toByte()
        frame[offset++] = cmdType.toByte()
        frame[offset++] = 0x00
        frame[offset++] = 0x00
        frame[offset++] = 0x00
        frame[offset++] = 0x00
        frame[offset++] = (seq and 0xFF).toByte()
        frame[offset++] = ((seq shr 8) and 0xFF).toByte()

        val crc16 = crc16(frame, offset)
        frame[offset++] = (crc16 and 0xFF).toByte()
        frame[offset++] = ((crc16 shr 8) and 0xFF).toByte()

        frame[offset++] = cmdSet.toByte()
        frame[offset++] = cmdId.toByte()

        payload.copyInto(frame, destinationOffset = offset)
        offset += payload.size

        val crc32 = crc32(frame, offset)
        frame[offset++] = (crc32 and 0xFF).toByte()
        frame[offset++] = ((crc32 shr 8) and 0xFF).toByte()
        frame[offset++] = ((crc32 shr 16) and 0xFF).toByte()
        frame[offset] = ((crc32 shr 24) and 0xFF).toByte()

        return frame
    }

    fun parseFrame(bytes: ByteArray): DjiProtocolFrame {
        require(bytes.size >= MinFrameLength) { "数据帧过短" }
        require(isStartOfFrame(bytes[0])) { "数据帧起始标记无效" }

        val versionAndLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val version = versionAndLength ushr 10
        val expectedLength = versionAndLength and 0x03FF
        require(expectedLength == bytes.size) { "数据帧长度不匹配" }

        val crc16Received = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[10].toInt() and 0xFF)
        require(crc16Received == crc16(bytes, 10)) { "CRC16 校验失败" }

        val crc32Received = ((bytes[bytes.lastIndex].toInt() and 0xFF) shl 24) or
            ((bytes[bytes.lastIndex - 1].toInt() and 0xFF) shl 16) or
            ((bytes[bytes.lastIndex - 2].toInt() and 0xFF) shl 8) or
            (bytes[bytes.lastIndex - 3].toInt() and 0xFF)
        require(crc32Received.toLong() and 0xFFFFFFFFL == crc32(bytes, bytes.size - 4)) { "CRC32 校验失败" }

        val cmdType = bytes[3].toInt() and 0xFF
        val seq = ((bytes[9].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        val cmdSet = bytes[12].toInt() and 0xFF
        val cmdId = bytes[13].toInt() and 0xFF
        val payload = bytes.copyOfRange(14, bytes.size - 4)

        return DjiProtocolFrame(
            version = version,
            frameLength = expectedLength,
            cmdType = cmdType,
            seq = seq,
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = payload,
        )
    }

    internal fun isStartOfFrame(value: Byte): Boolean = value.toInt() and 0xFF == Sof

    internal fun peekFrameLength(bytes: ByteArray, offset: Int = 0): Int? {
        if (offset < 0 || bytes.size - offset < 3) return null
        if (!isStartOfFrame(bytes[offset])) return null

        val versionAndLength = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        val expectedLength = versionAndLength and 0x03FF
        return expectedLength.takeIf { it in MinFrameLength..MaxFrameLength }
    }

    fun createConnectionRequestPayload(
        identity: ControllerIdentity,
        verifyMode: Int,
        verifyData: Int,
    ): ByteArray {
        return ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(identity.deviceId)
            put(identity.pseudoMac.size.toByte())
            val paddedMac = ByteArray(16)
            identity.pseudoMac.copyInto(paddedMac, endIndex = identity.pseudoMac.size)
            put(paddedMac)
            putInt(0)
            put(0x00)
            put(verifyMode.toByte())
            putShort(verifyData.toShort())
            putInt(0)
        }.array()
    }

    fun createConnectionResponsePayload(
        controllerDeviceId: Int,
        cameraReserved: Int = 0,
    ): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(controllerDeviceId)
            put(0x00)
            put(cameraReserved.toByte())
            put(0x00)
            put(0x00)
            put(0x00)
        }.array()
    }

    fun createModeSwitchPayload(cameraDeviceIdRaw: Int, mode: CameraMode): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cameraDeviceIdRaw)
            put(mode.code.toByte())
            put(byteArrayOf(0x01, 0x47, 0x39, 0x36))
        }.array()
    }

    fun createRecordControlPayload(cameraDeviceIdRaw: Int, start: Boolean): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cameraDeviceIdRaw)
            put(if (start) 0x00 else 0x01)
            putInt(0)
        }.array()
    }

    fun createKeyReportPayload(
        keyCode: Int,
        mode: Int = 0x01,
        keyValue: Int = 0x00,
    ): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(keyCode.toByte())
            put(mode.toByte())
            putShort(keyValue.toShort())
        }.array()
    }

    fun createStatusSubscriptionPayload(pushMode: Int = 0x03, pushFrequency: Int = 20): ByteArray {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(pushMode.toByte())
            put(pushFrequency.toByte())
            putInt(0)
        }.array()
    }

    fun createPowerModePayload(powerMode: Int): ByteArray {
        return byteArrayOf(powerMode.toByte())
    }

    fun createGpsPayload(sample: GpsSample): ByteArray {
        val instant = Instant.ofEpochMilli(sample.timestampMs)
        val utcPlus8 = instant.atZone(ZoneOffset.ofHours(8))
        val bearingRad = Math.toRadians(sample.bearingDegrees.toDouble())
        val speedCentimetersPerSecond = sample.speedMetersPerSecond * 100f
        val speedNorth = if (sample.speedMetersPerSecond > 0f) {
            (speedCentimetersPerSecond * kotlin.math.cos(bearingRad)).toFloat()
        } else {
            0f
        }
        val speedEast = if (sample.speedMetersPerSecond > 0f) {
            (speedCentimetersPerSecond * kotlin.math.sin(bearingRad)).toFloat()
        } else {
            0f
        }

        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(utcPlus8.year * 10000 + utcPlus8.monthValue * 100 + utcPlus8.dayOfMonth)
            putInt(utcPlus8.hour * 10000 + utcPlus8.minute * 100 + utcPlus8.second)
            putInt((sample.longitude * 1e7).roundToInt())
            putInt((sample.latitude * 1e7).roundToInt())
            putInt((sample.altitudeMeters * 1000.0).roundToInt())
            putFloat(speedNorth)
            putFloat(speedEast)
            putFloat(0f)
            putInt((sample.verticalAccuracyMeters * 1000f).roundToInt().coerceAtLeast(0))
            putInt((sample.horizontalAccuracyMeters * 1000f).roundToInt().coerceAtLeast(0))
            putInt((sample.speedAccuracyMetersPerSecond * 100f).roundToInt().coerceAtLeast(0))
            putInt(sample.satelliteCount.coerceAtLeast(0))
        }.array()
    }

    fun parseConnectionRequestPayload(payload: ByteArray): ConnectionRequestPayload {
        require(payload.size >= 33) { "连接请求载荷过短" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return ConnectionRequestPayload(
            deviceIdRaw = buffer.int,
            verifyMode = buffer.apply { position(26) }.get().toInt() and 0xFF,
            verifyData = buffer.shortAt(27),
        )
    }

    fun parseConnectionResponsePayload(payload: ByteArray): ConnectionResponsePayload {
        require(payload.size >= 9) { "连接响应载荷过短" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return ConnectionResponsePayload(
            deviceIdRaw = buffer.int,
            retCode = buffer.get().toInt() and 0xFF,
        )
    }

    fun parseCameraStatusPayload(payload: ByteArray): CameraStatusPayload {
        require(payload.size >= 38) { "相机状态载荷过短" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val cameraMode = buffer.get().toInt() and 0xFF
        val cameraStatus = buffer.get().toInt() and 0xFF
        val resolution = buffer.get().toInt() and 0xFF
        val fpsIndex = buffer.get().toInt() and 0xFF
        buffer.get() // EIS mode
        val recordTime = buffer.short.toInt() and 0xFFFF
        buffer.get() // FOV type
        buffer.get() // Photo ratio
        buffer.short // real time countdown
        val timelapseInterval = buffer.short.toInt() and 0xFFFF
        buffer.short // timelapse duration
        buffer.int // remain capacity
        val remainPhotoNum = buffer.int.toLong() and 0xFFFFFFFFL
        val remainTime = buffer.int.toLong() and 0xFFFFFFFFL
        val userMode = buffer.get().toInt() and 0xFF
        val powerMode = buffer.get().toInt() and 0xFF
        buffer.get() // next mode flag
        val tempState = buffer.get().toInt() and 0xFF
        buffer.int // photo countdown ms
        buffer.short // loop recording seconds
        val batteryPercent = buffer.get().toInt() and 0xFF

        return CameraStatusPayload(
            cameraMode = cameraMode,
            cameraStatus = cameraStatus,
            videoResolution = resolution,
            fpsIndex = fpsIndex,
            recordTimeSeconds = recordTime,
            timelapseIntervalTenthSeconds = timelapseInterval,
            remainingPhotoCount = remainPhotoNum,
            remainingTimeSeconds = remainTime,
            userMode = userMode,
            powerMode = powerMode,
            tempState = tempState,
            batteryPercent = batteryPercent,
        )
    }

    fun parseNewCameraStatusPayload(payload: ByteArray): NewCameraStatusPayload {
        require(payload.size >= 46) { "新相机状态载荷过短" }
        val modeNameLength = payload[1].toInt() and 0xFF
        val modeName = payload.copyOfRange(2, 2 + modeNameLength.coerceAtMost(20))
            .decodeToString()
            .trim('\u0000', ' ')
        val paramLength = payload[24].toInt() and 0xFF
        val modeParam = payload.copyOfRange(25, 25 + paramLength.coerceAtMost(20))
            .decodeToString()
            .trim('\u0000', ' ')

        return NewCameraStatusPayload(
            modeName = modeName,
            modeParam = modeParam,
        )
    }

    fun parseReturnCode(payload: ByteArray): Int {
        require(payload.isNotEmpty()) { "确认载荷为空" }
        return payload[0].toInt() and 0xFF
    }

    fun resolutionLabel(code: Int): String = when (code) {
        10 -> "1080p"
        16 -> "4K 16:9"
        45 -> "2.7K 16:9"
        66 -> "1080p 9:16"
        67 -> "2.7K 9:16"
        95 -> "2.7K 4:3"
        103 -> "4K 4:3"
        109 -> "4K 9:16"
        4 -> "照片大"
        3 -> "照片中"
        2 -> "照片标准"
        else -> "代码 $code"
    }

    fun fpsLabel(code: Int, cameraMode: CameraMode): String = when {
        cameraMode == CameraMode.Photo && code > 1 -> "$code 张连拍"
        cameraMode == CameraMode.Photo -> "单拍"
        code == 1 -> "24 帧/秒"
        code == 2 -> "25 帧/秒"
        code == 3 -> "30 帧/秒"
        code == 4 -> "48 帧/秒"
        code == 5 -> "50 帧/秒"
        code == 6 -> "60 帧/秒"
        code == 7 -> "120 帧/秒"
        code == 8 -> "240 帧/秒"
        code == 10 -> "100 帧/秒"
        code == 19 -> "200 帧/秒"
        else -> "代码 $code"
    }

    private fun ByteBuffer.shortAt(position: Int): Int {
        val old = this.position()
        this.position(position)
        val value = this.short.toInt() and 0xFFFF
        this.position(old)
        return value
    }

    private fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0x3AA3
        repeat(length) { index ->
            crc = crc xor (data[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun crc32(data: ByteArray, length: Int): Long {
        var crc = 0x00003AA3L
        repeat(length) { index ->
            crc = crc xor (data[index].toLong() and 0xFFL)
            repeat(8) {
                crc = if (crc and 0x01L != 0L) {
                    (crc ushr 1) xor 0xEDB88320L
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }
}

internal class DjiFrameStreamDecoder(
    private val onFrameError: (String) -> Unit = {},
) {
    private var buffer = ByteArray(0)

    @Synchronized
    fun append(chunk: ByteArray): List<DjiProtocolFrame> {
        if (chunk.isEmpty()) return emptyList()

        buffer = if (buffer.isEmpty()) {
            chunk.copyOf()
        } else {
            buffer + chunk
        }

        val frames = mutableListOf<DjiProtocolFrame>()
        while (buffer.isNotEmpty()) {
            val frameStartIndex = buffer.indexOfFirst(DjiProtocol::isStartOfFrame)
            if (frameStartIndex < 0) {
                buffer = ByteArray(0)
                break
            }

            if (frameStartIndex > 0) {
                buffer = buffer.copyOfRange(frameStartIndex, buffer.size)
            }

            if (buffer.size < 3) break

            val expectedLength = DjiProtocol.peekFrameLength(buffer)
            if (expectedLength == null) {
                onFrameError("协议帧头无效")
                buffer = buffer.dropPrefix(1)
                continue
            }

            if (buffer.size < expectedLength) break

            val candidate = buffer.copyOfRange(0, expectedLength)
            runCatching { DjiProtocol.parseFrame(candidate) }
                .onSuccess { frame ->
                    frames += frame
                    buffer = buffer.dropPrefix(expectedLength)
                }
                .onFailure { error ->
                    onFrameError(error.message ?: "协议数据帧已忽略")
                    buffer = buffer.dropPrefix(1)
                }
        }

        return frames
    }

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
    }

    private fun ByteArray.dropPrefix(length: Int): ByteArray {
        if (length <= 0) return this
        if (length >= size) return ByteArray(0)
        return copyOfRange(length, size)
    }
}
