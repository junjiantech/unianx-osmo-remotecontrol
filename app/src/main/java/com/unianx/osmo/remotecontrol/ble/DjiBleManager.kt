package com.unianx.osmo.remotecontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.unianx.osmo.remotecontrol.data.AppSettingsStore
import com.unianx.osmo.remotecontrol.data.ConnectionHistoryEntry
import com.unianx.osmo.remotecontrol.data.ControllerIdentity
import com.unianx.osmo.remotecontrol.data.ControllerIdentityStore
import com.unianx.osmo.remotecontrol.data.GpsSample
import com.unianx.osmo.remotecontrol.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class DjiBleManager(
    private val context: Context,
    identityStore: ControllerIdentityStore,
) {
    private val logTag = "DjiBleManager"
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val settingsStore = AppSettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val sequence = AtomicInteger(1)
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<DjiProtocolFrame>>()
    private val protocolEvents = MutableSharedFlow<DjiProtocolFrame>(extraBufferCapacity = 64)
    private val frameDecoder = DjiFrameStreamDecoder(::emitMessage)

    private val _controllerIdentity = MutableStateFlow(identityStore.getOrCreate())
    private val _scannedDevices = MutableStateFlow<List<ScannedCamera>>(emptyList())
    private val _connectionState = MutableStateFlow(
        when {
            adapter == null -> CameraConnectionState.BluetoothUnavailable
            !adapter.isEnabled -> CameraConnectionState.BluetoothDisabled
            else -> CameraConnectionState.Idle
        },
    )
    private val _connectedCamera = MutableStateFlow<ScannedCamera?>(null)
    private val _telemetry = MutableStateFlow(CameraTelemetry())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var scanAutoStopJob: Job? = null
    private var scanning = false
    private var manualDisconnect = false
    private var currentGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationReady: CompletableDeferred<Unit>? = null
    private var writeAck: CompletableDeferred<Int>? = null
    private var cameraDeviceIdRaw: Int? = null
    private var suppressMessagesForCurrentConnectAttempt = false
    private var sleepTransitionInFlight = false
    private var sleepTransitionResetJob: Job? = null
    private var wakeReconnectExpected = false
    private var wakeAdvertisingCallback: AdvertiseCallback? = null

    val controllerIdentity: StateFlow<ControllerIdentity> = _controllerIdentity.asStateFlow()
    val scannedDevices: StateFlow<List<ScannedCamera>> = _scannedDevices.asStateFlow()
    val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()
    val connectedCamera: StateFlow<ScannedCamera?> = _connectedCamera.asStateFlow()
    val telemetry: StateFlow<CameraTelemetry> = _telemetry.asStateFlow()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val match = matchSupportedDjiDevice(result)
            if (!match.matched) {
                AppLogger.d(
                    logTag,
                    "scan ignored address=${result.device.address.orEmpty()} rssi=${result.rssi} reason=${match.reason}",
                )
                return
            }

            val name = runCatching {
                result.device.name ?: result.scanRecord?.deviceName ?: ""
            }.getOrDefault("")
            val camera = ScannedCamera(
                name = name,
                address = result.device.address.orEmpty(),
                rssi = result.rssi,
                lastSeenAtMs = System.currentTimeMillis(),
            )

            _scannedDevices.update { devices ->
                (devices.filterNot { it.address == camera.address } + camera)
                    .sortedByDescending { it.rssi }
            }
            AppLogger.i(
                logTag,
                "scan matched address=${camera.address} name=${camera.displayName} rssi=${camera.rssi} reason=${match.reason}",
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connectionState.value = CameraConnectionState.Error
            AppLogger.e(logTag, "scan failed errorCode=$errorCode")
            emitMessage("蓝牙扫描失败：$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            AppLogger.i(logTag, "gatt state change status=$status newState=$newState address=${gatt.device.address}")
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    currentGatt = gatt
                    if (!gatt.discoverServices()) {
                        AppLogger.e(logTag, "discoverServices start failed address=${gatt.device.address}")
                        scope.launch { handleDisconnected("无法启动服务发现") }
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    scope.launch {
                        handleDisconnected(
                            message = when {
                                manualDisconnect -> null
                                sleepTransitionInFlight -> null
                                wakeReconnectExpected -> null
                                else -> "相机已断开连接"
                            },
                        )
                    }
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    scope.launch { handleDisconnected("蓝牙链路错误：$status") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            AppLogger.i(logTag, "services discovered status=$status address=${gatt.device.address}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notificationReady?.completeExceptionally(IllegalStateException("服务发现失败：$status"))
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val notify = service?.getCharacteristic(NOTIFY_UUID)
            val write = service?.getCharacteristic(WRITE_UUID)

            if (service == null || notify == null || write == null) {
                AppLogger.e(
                    logTag,
                    "required characteristics missing service=${service != null} notify=${notify != null} write=${write != null}",
                )
                notificationReady?.completeExceptionally(IllegalStateException("缺少必需的 FFF0/FFF4/FFF5 特征"))
                return
            }

            notifyCharacteristic = notify
            writeCharacteristic = write

            if (!gatt.setCharacteristicNotification(notify, true)) {
                AppLogger.e(logTag, "setCharacteristicNotification failed address=${gatt.device.address}")
                notificationReady?.completeExceptionally(IllegalStateException("注册通知失败"))
                return
            }

            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                AppLogger.e(logTag, "missing CCCD descriptor address=${gatt.device.address}")
                notificationReady?.completeExceptionally(IllegalStateException("缺少通知描述符"))
                return
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }

            if (!started) {
                AppLogger.e(logTag, "write CCCD failed to start address=${gatt.device.address}")
                notificationReady?.completeExceptionally(IllegalStateException("无法写入通知描述符"))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    AppLogger.i(logTag, "notification channel ready address=${gatt.device.address}")
                    notificationReady?.complete(Unit)
                } else {
                    AppLogger.e(logTag, "descriptor write failed status=$status address=${gatt.device.address}")
                    notificationReady?.completeExceptionally(
                        IllegalStateException("通知描述符写入失败：$status"),
                    )
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeAck?.complete(status)
            writeAck = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicChanged(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(value)
        }
    }

    fun startScan() {
        AppLogger.i(
            logTag,
            "startScan adapterPresent=${adapter != null} enabled=${adapter?.isEnabled == true} scanning=$scanning",
        )
        when {
            adapter == null -> {
                _connectionState.value = CameraConnectionState.BluetoothUnavailable
                AppLogger.w(logTag, "scan aborted: bluetooth unavailable")
                emitMessage("当前设备不支持低功耗蓝牙")
                return
            }

            !adapter.isEnabled -> {
                _connectionState.value = CameraConnectionState.BluetoothDisabled
                AppLogger.w(logTag, "scan aborted: bluetooth disabled")
                emitMessage("请先开启蓝牙再扫描")
                return
            }

            scanning -> {
                AppLogger.w(logTag, "scan ignored: already scanning")
                return
            }
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = CameraConnectionState.Error
            AppLogger.e(logTag, "scan aborted: bluetoothLeScanner unavailable")
            emitMessage("蓝牙扫描器不可用")
            return
        }

        _scannedDevices.value = emptyList()
        _connectionState.value = CameraConnectionState.Scanning
        scanning = true
        AppLogger.i(logTag, "scan started")
        scanner.startScan(scanCallback)

        scanAutoStopJob?.cancel()
        scanAutoStopJob = scope.launch {
            delay(8_000)
            AppLogger.i(logTag, "scan auto stop after timeout")
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning) return

        runCatching {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }.onFailure { throwable ->
            AppLogger.w(logTag, "stopScan failed", throwable)
        }
        scanning = false
        scanAutoStopJob?.cancel()
        AppLogger.i(logTag, "scan stopped results=${_scannedDevices.value.size}")

        if (_connectionState.value == CameraConnectionState.Scanning) {
            _connectionState.value = CameraConnectionState.Idle
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        address: String,
        notifyUserOnFailure: Boolean = true,
        readyTimeoutMs: Long = DEFAULT_CONNECT_READY_TIMEOUT_MS,
    ) {
        AppLogger.i(
            logTag,
            "connect start address=$address notifyUserOnFailure=$notifyUserOnFailure readyTimeoutMs=$readyTimeoutMs",
        )
        val localAdapter = adapter ?: error("蓝牙不可用")
        if (!localAdapter.isEnabled) error("蓝牙未开启")

        stopScan()
        disconnectInternal(
            resetState = false,
            preserveWakeReconnectExpected = wakeReconnectExpected,
        )
        _telemetry.value = CameraTelemetry()
        _connectedCamera.value = _scannedDevices.value.firstOrNull { it.address == address }
            ?: ScannedCamera(name = "", address = address, rssi = -100, lastSeenAtMs = System.currentTimeMillis())

        _connectionState.value = CameraConnectionState.GattConnecting
        manualDisconnect = false
        suppressMessagesForCurrentConnectAttempt = !notifyUserOnFailure
        notificationReady = CompletableDeferred()

        withContext(Dispatchers.Main) {
            currentGatt = localAdapter.getRemoteDevice(address)
                .connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        try {
            withTimeout(readyTimeoutMs) { notificationReady?.await() }
            AppLogger.i(logTag, "gatt connected and notification ready address=$address")
            _connectionState.value = CameraConnectionState.GattConnected

            _connectionState.value = CameraConnectionState.Handshaking
            performProtocolHandshake()
            subscribeToCameraStatus()
            _connectionState.value = CameraConnectionState.Ready
            settingsStore.recordConnectedCamera(
                name = _connectedCamera.value?.name.orEmpty(),
                address = address,
            )
            settingsStore.markWakeCapableConnection(address)
            suppressMessagesForCurrentConnectAttempt = false
            wakeReconnectExpected = false
            AppLogger.i(logTag, "connect completed address=$address")
        } catch (throwable: Throwable) {
            _connectionState.value = CameraConnectionState.Error
            AppLogger.e(logTag, "connect failed address=$address", throwable)
            disconnectInternal(
                resetState = true,
                preserveWakeReconnectExpected = wakeReconnectExpected,
            )
            if (notifyUserOnFailure) {
                emitMessage(throwable.message ?: "连接失败")
            }
            suppressMessagesForCurrentConnectAttempt = false
            throw throwable
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        AppLogger.i(logTag, "disconnect requested")
        manualDisconnect = true
        suppressMessagesForCurrentConnectAttempt = false
        stopScan()
        _connectionState.value = CameraConnectionState.Disconnecting
        withContext(Dispatchers.Main) {
            currentGatt?.disconnect()
        }
        delay(300)
        disconnectInternal(resetState = true)
    }

    suspend fun switchMode(mode: CameraMode): Boolean {
        val rawId = cameraDeviceIdRaw ?: return false
        val response = sendCommand(
            cmdSet = DjiProtocol.CmdSetCamera,
            cmdId = DjiProtocol.CmdIdModeSwitch,
            cmdType = DjiProtocol.CmdResponseOrNot,
            payload = DjiProtocol.createModeSwitchPayload(rawId, mode),
            timeoutMs = 5_000,
            responseRequired = false,
        )
        return response == null || DjiProtocol.parseReturnCode(response.payload) == 0
    }

    suspend fun capturePhoto(): Boolean {
        if (_connectionState.value != CameraConnectionState.Ready) return false

        if (_telemetry.value.mode != CameraMode.Photo) {
            if (!switchMode(CameraMode.Photo)) return false
            delay(350)
        }

        val response = sendCommand(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdKeyReport,
            cmdType = DjiProtocol.CmdResponseOrNot,
            payload = DjiProtocol.createKeyReportPayload(DjiProtocol.KeyRecord),
            timeoutMs = 5_000,
            responseRequired = false,
        )

        return response == null || DjiProtocol.parseReturnCode(response.payload) == 0
    }

    suspend fun toggleRecording(): Boolean {
        if (_connectionState.value != CameraConnectionState.Ready) return false

        val telemetry = _telemetry.value
        if (telemetry.isRecording) {
            val response = sendCommand(
                cmdSet = DjiProtocol.CmdSetCamera,
                cmdId = DjiProtocol.CmdIdRecordControl,
                cmdType = DjiProtocol.CmdResponseOrNot,
                payload = DjiProtocol.createRecordControlPayload(cameraDeviceIdRaw ?: return false, start = false),
                timeoutMs = 5_000,
                responseRequired = false,
            )
            return response == null || DjiProtocol.parseReturnCode(response.payload) == 0
        }

        if (telemetry.mode != CameraMode.Video && telemetry.mode != CameraMode.LowLightVideo) {
            if (!switchMode(CameraMode.Video)) return false
            delay(350)
        }

        val response = sendCommand(
            cmdSet = DjiProtocol.CmdSetCamera,
            cmdId = DjiProtocol.CmdIdRecordControl,
            cmdType = DjiProtocol.CmdResponseOrNot,
            payload = DjiProtocol.createRecordControlPayload(cameraDeviceIdRaw ?: return false, start = true),
            timeoutMs = 5_000,
            responseRequired = false,
        )
        return response == null || DjiProtocol.parseReturnCode(response.payload) == 0
    }

    suspend fun pushGpsSample(sample: GpsSample): Boolean {
        if (_connectionState.value != CameraConnectionState.Ready) return false

        sendCommand(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdGpsPush,
            cmdType = DjiProtocol.CmdNoResponse,
            payload = DjiProtocol.createGpsPayload(sample),
            timeoutMs = 0,
            responseRequired = false,
        )
        return true
    }

    fun isCameraSleeping(): Boolean {
        val telemetry = _telemetry.value
        return telemetry.powerMode == 3 || telemetry.workState == CameraWorkState.ScreenOff
    }

    suspend fun sleepCamera(): Boolean {
        if (_connectionState.value != CameraConnectionState.Ready) return false

        sleepTransitionInFlight = true
        sleepTransitionResetJob?.cancel()
        sleepTransitionResetJob = scope.launch {
            delay(2_000)
            sleepTransitionInFlight = false
        }
        return try {
            val response = sendCommand(
                cmdSet = DjiProtocol.CmdSetConnection,
                cmdId = DjiProtocol.CmdIdPowerMode,
                cmdType = DjiProtocol.CmdResponseOrNot,
                payload = DjiProtocol.createPowerModePayload(powerMode = 0x03),
                timeoutMs = 1_500,
                responseRequired = false,
            )

            val retCode = response?.payload?.takeIf { it.isNotEmpty() }?.let(DjiProtocol::parseReturnCode)
            if (retCode != null && retCode != 0) {
                sleepTransitionResetJob?.cancel()
                sleepTransitionInFlight = false
                return false
            }

            _telemetry.update {
                it.copy(
                    powerMode = 3,
                    workState = CameraWorkState.ScreenOff,
                )
            }
            true
        } catch (throwable: Throwable) {
            val disconnectedDuringSleep = currentGatt == null || _connectionState.value == CameraConnectionState.Idle
            if (disconnectedDuringSleep) {
                true
            } else {
                sleepTransitionResetJob?.cancel()
                sleepTransitionInFlight = false
                AppLogger.w(logTag, "sleepCamera failed", throwable)
                false
            }
        }
    }

    suspend fun wakeAndTriggerSnapshot(): Boolean {
        val connectedCamera = _connectedCamera.value ?: return false
        val wakeReady = settingsStore.loadRecentConnections()
            .firstOrNull { it.address.equals(connectedCamera.address, ignoreCase = true) }
            ?.let(::isWakeWindowValid)
            ?: false
        if (!wakeReady) {
            AppLogger.w(logTag, "wakeAndTriggerSnapshot aborted: wake window expired address=${connectedCamera.address}")
            return false
        }

        val advertised = startWakeAdvertising(connectedCamera.address)
        if (!advertised) return false

        wakeReconnectExpected = true
        return try {
            delay(WAKE_ADVERTISING_DURATION_MS + WAKE_POST_ADVERTISING_SETTLE_MS)
            if (!reconnectForWakeSnapshot(connectedCamera.address)) {
                AppLogger.w(logTag, "wakeAndTriggerSnapshot aborted: link not ready address=${connectedCamera.address}")
                false
            } else {
                delay(WAKE_POST_RECONNECT_SETTLE_MS)
                sendWakeSnapshotCommand(connectedCamera.address)
            }
        } catch (throwable: Throwable) {
            AppLogger.w(logTag, "wakeAndTriggerSnapshot failed address=${connectedCamera.address}", throwable)
            false
        } finally {
            wakeReconnectExpected = false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startWakeAdvertising(address: String): Boolean {
        val localAdapter = adapter ?: return false
        if (!localAdapter.isEnabled) return false
        val advertiser = localAdapter.bluetoothLeAdvertiser ?: return false

        val manufacturerBytes = buildWakeManufacturerData(address)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            // Android 会先写入 manufacturerId 的小端字节，所以这里用 0x4B57 生成 'W''K'。
            .addManufacturerData(WAKE_MANUFACTURER_ID, manufacturerBytes)
            .build()

        val callback = CompletableDeferred<Boolean>()
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                AppLogger.i(logTag, "wake advertising started address=$address")
                callback.complete(true)
            }

            override fun onStartFailure(errorCode: Int) {
                AppLogger.e(logTag, "wake advertising failed errorCode=$errorCode")
                callback.complete(false)
            }
        }
        wakeAdvertisingCallback = advertiseCallback

        withContext(Dispatchers.Main) {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        }
        val started = withTimeoutOrNull(2_000) { callback.await() } == true
        if (!started) {
            wakeAdvertisingCallback = null
            emitMessage("唤醒广播启动失败，当前设备可能不支持所需 BLE 广播格式")
            return false
        }

        scope.launch {
            delay(WAKE_ADVERTISING_DURATION_MS)
            runCatching {
                withContext(Dispatchers.Main) {
                    wakeAdvertisingCallback?.let { advertiser.stopAdvertising(it) }
                }
            }.onFailure { throwable ->
                AppLogger.w(logTag, "stop wake advertising failed", throwable)
            }
            wakeAdvertisingCallback = null
            AppLogger.i(logTag, "wake advertising stopped address=$address")
        }
        return true
    }

    private fun buildWakeManufacturerData(address: String): ByteArray {
        val mac = address.split(':')
            .map { it.toInt(16).toByte() }
            .toByteArray()
        require(mac.size == 6) { "invalid camera mac address" }

        return byteArrayOf(
            'P'.code.toByte(),
            mac[5],
            mac[4],
            mac[3],
            mac[2],
            mac[1],
            mac[0],
        )
    }

    private fun isWakeWindowValid(entry: ConnectionHistoryEntry): Boolean {
        return System.currentTimeMillis() - entry.lastWakeCapableAtMs <= WAKE_WINDOW_MS
    }

    private fun hasReadyWriteLink(address: String): Boolean {
        val currentAddress = _connectedCamera.value?.address
        return currentGatt != null &&
            notifyCharacteristic != null &&
            writeCharacteristic != null &&
            currentAddress.equals(address, ignoreCase = true) &&
            _connectionState.value == CameraConnectionState.Ready
    }

    private suspend fun reconnectForWakeSnapshot(address: String): Boolean {
        repeat(2) { attempt ->
            runCatching {
                connect(
                    address = address,
                    notifyUserOnFailure = false,
                    readyTimeoutMs = WAKE_RECONNECT_READY_TIMEOUT_MS,
                )
            }.onFailure { throwable ->
                AppLogger.w(
                    logTag,
                    "wake reconnect failed attempt=${attempt + 1} address=$address",
                    throwable,
                )
            }

            if (hasReadyWriteLink(address)) {
                AppLogger.i(logTag, "wake reconnect ready attempt=${attempt + 1} address=$address")
                return true
            }

            AppLogger.w(logTag, "wake reconnect not ready attempt=${attempt + 1} address=$address")
            delay(WAKE_RECONNECT_RETRY_DELAY_MS)
        }
        return false
    }

    private suspend fun sendWakeSnapshotCommand(address: String): Boolean {
        repeat(2) { attempt ->
            var commandSent = false
            val response = runCatching {
                sendCommand(
                    cmdSet = DjiProtocol.CmdSetConnection,
                    cmdId = DjiProtocol.CmdIdKeyReport,
                    cmdType = DjiProtocol.CmdResponseOrNot,
                    payload = DjiProtocol.createKeyReportPayload(DjiProtocol.KeySnapshot),
                    timeoutMs = 2_000,
                    responseRequired = false,
                )
            }.onSuccess {
                commandSent = true
            }.onFailure { throwable ->
                AppLogger.w(
                    logTag,
                    "wake snapshot command failed attempt=${attempt + 1} address=$address",
                    throwable,
                )
            }.getOrNull()

            if (commandSent) {
                val retCode = response?.payload?.takeIf { it.isNotEmpty() }?.let(DjiProtocol::parseReturnCode)
                if (retCode == null || retCode == 0) {
                    return true
                }
                AppLogger.w(
                    logTag,
                    "wake snapshot command rejected retCode=$retCode attempt=${attempt + 1} address=$address",
                )
            }

            if (attempt == 0) {
                delay(400)
                if (!reconnectForWakeSnapshot(address)) {
                    return false
                }
                delay(300)
            }
        }
        return false
    }

    private suspend fun performProtocolHandshake() {
        val identity = _controllerIdentity.value
        val verifyCode = Random.nextInt(0, 10_000)
        AppLogger.i(logTag, "handshake start controller=${identity.pseudoMacHex} verifyCode=$verifyCode")
        val cameraCommandDeferred = scope.async {
            protocolEvents.first {
                it.cmdSet == DjiProtocol.CmdSetConnection &&
                    it.cmdId == DjiProtocol.CmdIdConnection &&
                    !it.isResponse
            }
        }

        val response = sendCommand(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdConnection,
            cmdType = DjiProtocol.CmdWaitResult,
            payload = DjiProtocol.createConnectionRequestPayload(identity, verifyMode = 0, verifyData = verifyCode),
            timeoutMs = 1_000,
            responseRequired = false,
        )

        if (response != null) {
            val parsed = DjiProtocol.parseConnectionResponsePayload(response.payload)
            AppLogger.i(logTag, "handshake response retCode=${parsed.retCode} deviceId=${parsed.deviceIdRaw}")
            require(parsed.retCode == 0) { "相机拒绝握手：${parsed.retCode}" }
        }

        val commandFrame = withTimeout(if (response != null) 30_000 else 1_500) {
            cameraCommandDeferred.await()
        }
        val cameraRequest = DjiProtocol.parseConnectionRequestPayload(commandFrame.payload)
        AppLogger.i(
            logTag,
            "handshake camera request deviceId=${cameraRequest.deviceIdRaw} verifyMode=${cameraRequest.verifyMode} verifyData=${cameraRequest.verifyData}",
        )
        require(cameraRequest.verifyMode == 2) { "未识别的校验模式：${cameraRequest.verifyMode}" }
        require(cameraRequest.verifyData == 0) { "相机拒绝配对" }

        cameraDeviceIdRaw = cameraRequest.deviceIdRaw
        _telemetry.update {
            it.copy(
                cameraDeviceIdRaw = cameraRequest.deviceIdRaw,
                modelLabel = modelLabelForDeviceId(cameraRequest.deviceIdRaw),
            )
        }

        sendCommand(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdConnection,
            cmdType = DjiProtocol.AckNoResponse,
            payload = DjiProtocol.createConnectionResponsePayload(identity.deviceId),
            forcedSequence = commandFrame.seq,
            timeoutMs = 0,
            responseRequired = false,
        )
        AppLogger.i(logTag, "handshake completed camera=${modelLabelForDeviceId(cameraRequest.deviceIdRaw)}")
    }

    private suspend fun subscribeToCameraStatus() {
        AppLogger.i(logTag, "subscribe camera status")
        sendCommand(
            cmdSet = DjiProtocol.CmdSetCamera,
            cmdId = DjiProtocol.CmdIdStatusSubscription,
            cmdType = DjiProtocol.CmdNoResponse,
            payload = DjiProtocol.createStatusSubscriptionPayload(),
            timeoutMs = 0,
            responseRequired = false,
        )
    }

    private suspend fun sendCommand(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        payload: ByteArray,
        timeoutMs: Long,
        responseRequired: Boolean,
        forcedSequence: Int? = null,
    ): DjiProtocolFrame? {
        val seq = forcedSequence ?: nextSequence()
        val awaitingResponse = (cmdType and 0x1F) != 0 && cmdType and 0x20 == 0
        val deferred = if (awaitingResponse) CompletableDeferred<DjiProtocolFrame>() else null
        if (deferred != null) {
            pendingResponses[seq] = deferred
        }

        try {
            AppLogger.d(
                logTag,
                "send command seq=$seq cmdSet=${cmdSet.toString(16)} cmdId=${cmdId.toString(16)} cmdType=${cmdType.toString(16)} payload=${payload.size}B await=$awaitingResponse timeoutMs=$timeoutMs",
            )
            writeFrame(DjiProtocol.buildFrame(cmdSet, cmdId, cmdType, seq, payload))

            if (deferred == null || timeoutMs <= 0) return null

            val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (response == null && responseRequired) {
                AppLogger.w(
                    logTag,
                    "command timeout seq=$seq cmdSet=${cmdSet.toString(16)} cmdId=${cmdId.toString(16)} timeoutMs=$timeoutMs",
                )
                error("指令 ${cmdSet.toString(16)}/${cmdId.toString(16)} 超时")
            }
            return response
        } finally {
            if (deferred != null) {
                pendingResponses.remove(seq)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeFrame(frame: ByteArray) {
        val gatt = currentGatt ?: error("蓝牙链路未连接")
        val characteristic = writeCharacteristic ?: error("写入特征不可用")

        sendMutex.withLock {
            val completion = CompletableDeferred<Int>()
            writeAck = completion

            fun tryStartWrite(): Boolean {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = frame
                    gatt.writeCharacteristic(characteristic)
                }
            }

            var started = tryStartWrite()
            if (!started) {
                AppLogger.w(logTag, "writeFrame start failed once, retrying bytes=${frame.size}")
                delay(250)
                started = tryStartWrite()
            }

            if (!started) {
                writeAck = null
                AppLogger.e(logTag, "writeFrame failed to start bytes=${frame.size}")
                error("无法启动蓝牙写入")
            }

            val status = withTimeout(4_000) { completion.await() }
            AppLogger.d(logTag, "writeFrame completed status=$status bytes=${frame.size}")
            check(status == BluetoothGatt.GATT_SUCCESS) { "蓝牙写入失败：$status" }
        }
    }

    private suspend fun handleDisconnected(message: String?) {
        AppLogger.w(logTag, "handleDisconnected message=${message.orEmpty()}")
        finishPendingOperations(message ?: "连接已断开")
        disconnectInternal(resetState = true)
        if (message != null && !suppressMessagesForCurrentConnectAttempt) {
            emitMessage(message)
        }
        suppressMessagesForCurrentConnectAttempt = false
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(
        resetState: Boolean,
        preserveWakeReconnectExpected: Boolean = false,
    ) {
        runCatching {
            currentGatt?.close()
        }.onFailure { throwable ->
            AppLogger.w(logTag, "gatt close failed", throwable)
        }

        currentGatt = null
        notifyCharacteristic = null
        writeCharacteristic = null
        notificationReady = null
        writeAck = null
        cameraDeviceIdRaw = null
        sleepTransitionResetJob?.cancel()
        sleepTransitionResetJob = null
        sleepTransitionInFlight = false
        if (!preserveWakeReconnectExpected) {
            wakeReconnectExpected = false
        }
        frameDecoder.reset()
        scanning = false
        stopScan()
        AppLogger.i(logTag, "disconnectInternal resetState=$resetState")

        if (resetState) {
            _connectedCamera.value = null
            _telemetry.value = CameraTelemetry()
            _connectionState.value = if (adapter == null) {
                CameraConnectionState.BluetoothUnavailable
            } else if (!adapter.isEnabled) {
                CameraConnectionState.BluetoothDisabled
            } else {
                CameraConnectionState.Idle
            }
        }
    }

    private fun finishPendingOperations(reason: String) {
        AppLogger.w(logTag, "finishPendingOperations reason=$reason pending=${pendingResponses.size}")
        pendingResponses.values.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(IllegalStateException(reason))
            }
        }
        pendingResponses.clear()
        notificationReady?.takeIf { !it.isCompleted }?.completeExceptionally(IllegalStateException(reason))
        writeAck?.takeIf { !it.isCompleted }?.completeExceptionally(IllegalStateException(reason))
    }

    private fun handleCharacteristicChanged(rawValue: ByteArray) {
        AppLogger.d(logTag, "notification bytes=${rawValue.size}")
        frameDecoder.append(rawValue).forEach(::handleProtocolFrame)
    }

    private fun handleProtocolFrame(frame: DjiProtocolFrame) {
        AppLogger.d(
            logTag,
            "protocol frame seq=${frame.seq} cmdSet=${frame.cmdSet.toString(16)} cmdId=${frame.cmdId.toString(16)} response=${frame.isResponse} payload=${frame.payload.size}B",
        )
        if (frame.isResponse) {
            pendingResponses[frame.seq]?.complete(frame)
        }
        protocolEvents.tryEmit(frame)
        when {
            frame.cmdSet == DjiProtocol.CmdSetCamera && frame.cmdId == DjiProtocol.CmdIdStatusPush -> {
                val status = DjiProtocol.parseCameraStatusPayload(frame.payload)
                val mode = CameraMode.fromCode(status.cameraMode)
                _telemetry.update {
                    it.copy(
                        cameraDeviceIdRaw = it.cameraDeviceIdRaw ?: cameraDeviceIdRaw,
                        modelLabel = modelLabelForDeviceId(it.cameraDeviceIdRaw ?: cameraDeviceIdRaw),
                        mode = mode,
                        workState = CameraWorkState.fromCode(status.cameraStatus),
                        resolutionLabel = DjiProtocol.resolutionLabel(status.videoResolution),
                        fpsLabel = DjiProtocol.fpsLabel(status.fpsIndex, mode),
                        recordTimeSeconds = status.recordTimeSeconds,
                        remainingTimeSeconds = status.remainingTimeSeconds,
                        remainingPhotoCount = status.remainingPhotoCount,
                        batteryPercent = status.batteryPercent,
                        timelapseIntervalTenthSeconds = status.timelapseIntervalTenthSeconds,
                        userMode = status.userMode,
                        powerMode = status.powerMode,
                        tempState = status.tempState,
                    )
                }
            }

            frame.cmdSet == DjiProtocol.CmdSetCamera && frame.cmdId == DjiProtocol.CmdIdNewStatusPush -> {
                val status = DjiProtocol.parseNewCameraStatusPayload(frame.payload)
                _telemetry.update {
                    it.copy(
                        newModeName = status.modeName,
                        newModeParam = status.modeParam,
                    )
                }
            }
        }
    }

    private fun nextSequence(): Int = sequence.getAndUpdate { current ->
        if (current >= 0xFFFF) 1 else current + 1
    }

    private fun emitMessage(message: String) {
        scope.launch {
            _messages.emit(message)
        }
    }

    private fun matchSupportedDjiDevice(result: ScanResult): ScanMatch {
        val record = result.scanRecord
        val bytes = record?.bytes
        val displayName = listOfNotNull(
            runCatching { result.device.name }.getOrNull(),
            record?.deviceName,
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val serviceUuids = record?.serviceUuids.orEmpty()
        val serviceSummary = serviceUuids.joinToString(",") { parcelUuid ->
            parcelUuid.uuid.toString()
        }

        if (bytes == null) {
            return ScanMatch(
                matched = false,
                reason = "missing-scan-record name=$displayName services=[$serviceSummary]",
            )
        }

        if (hasKnownManufacturerPattern(bytes)) {
            return ScanMatch(
                matched = true,
                reason = "manufacturer-pattern name=$displayName services=[$serviceSummary]",
            )
        }

        val manufacturerSummary = manufacturerSummary(bytes)
        return ScanMatch(
            matched = false,
            reason = "manufacturer-mismatch name=$displayName services=[$serviceSummary] manufacturer=[$manufacturerSummary]",
        )
    }

    private fun hasKnownManufacturerPattern(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            val blockLength = bytes[index].toInt() and 0xFF
            if (blockLength == 0 || index + blockLength >= bytes.size + 1) break

            val type = bytes[index + 1].toInt() and 0xFF
            val payloadStart = index + 2
            val payloadLength = blockLength - 1

            if (type == 0xFF && payloadLength >= 5) {
                if ((bytes[payloadStart].toInt() and 0xFF) == 0xAA &&
                    (bytes[payloadStart + 1].toInt() and 0xFF) == 0x08 &&
                    (bytes[payloadStart + 4].toInt() and 0xFF) == 0xFA
                ) {
                    return true
                }
            }

            index += blockLength + 1
        }
        return false
    }

    private fun manufacturerSummary(bytes: ByteArray): String {
        val parts = mutableListOf<String>()
        var index = 0
        while (index < bytes.size) {
            val blockLength = bytes[index].toInt() and 0xFF
            if (blockLength == 0 || index + blockLength >= bytes.size + 1) break

            val type = bytes[index + 1].toInt() and 0xFF
            val payloadStart = index + 2
            val payloadLength = blockLength - 1
            if (type == 0xFF && payloadLength > 0) {
                val payload = bytes.copyOfRange(payloadStart, payloadStart + payloadLength)
                parts += payload.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            }

            index += blockLength + 1
        }
        return parts.joinToString(" | ")
    }

    private data class ScanMatch(
        val matched: Boolean,
        val reason: String,
    )

    private companion object {
        const val DEFAULT_CONNECT_READY_TIMEOUT_MS = 20_000L
        const val WAKE_WINDOW_MS = 30 * 60 * 1000L
        const val WAKE_ADVERTISING_DURATION_MS = 2_000L
        const val WAKE_POST_ADVERTISING_SETTLE_MS = 300L
        const val WAKE_RECONNECT_READY_TIMEOUT_MS = 8_000L
        const val WAKE_RECONNECT_RETRY_DELAY_MS = 800L
        const val WAKE_POST_RECONNECT_SETTLE_MS = 300L
        const val WAKE_MANUFACTURER_ID = 0x4B57
        val SERVICE_UUID: UUID = uuid16(DjiProtocol.ServiceUuid16)
        val NOTIFY_UUID: UUID = uuid16(DjiProtocol.NotifyUuid16)
        val WRITE_UUID: UUID = uuid16(DjiProtocol.WriteUuid16)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun uuid16(uuid16: Int): UUID {
            return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", uuid16))
        }
    }
}
