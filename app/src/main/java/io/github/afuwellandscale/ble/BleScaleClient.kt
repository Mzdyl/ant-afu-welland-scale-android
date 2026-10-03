package io.github.afuwellandscale.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.afuwellandscale.model.BodyCompositionEstimator
import io.github.afuwellandscale.model.Measurement
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile
import io.github.afuwellandscale.util.hex
import java.util.UUID

class BleScaleClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onLog(message: String)
        fun onDeviceFound(device: ScaleDevice)
        fun onMeasurement(measurement: Measurement)
        fun onCompleted(measurement: Measurement)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val protocol = Scale27Protocol()
    private var gatt: BluetoothGatt? = null
    private var activeDevice: ScaleDevice? = null
    private var profile: UserProfile = UserProfile()
    private var latestStableWeightKg: Double? = null
    private var completed = false
    private var scanning = false
    private var running = false
    private var canRescan = false
    private val connectionTimeout = Runnable {
        if (running && canRescan) {
            canRescan = false
            val previous = gatt
            gatt = null
            try {
                previous?.disconnect()
                previous?.close()
            } catch (_: SecurityException) {
                error("蓝牙权限已撤销，请重新授权")
                return@Runnable
            }
            scan()
        }
    }
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            log(scanSummary("scan_timeout"))
            error("未找到体重秤，请轻踩唤醒后重试")
        }
    }
    private var scanResultCount = 0
    private var namedScaleResultCount = 0
    private val scanRejectCounts = linkedMapOf<String, Int>()

    fun start(profile: UserProfile, savedDevice: ScaleDevice?, scanFirst: Boolean) {
        stop()
        running = true
        this.profile = profile
        completed = false
        latestStableWeightKg = null
        if (!hasBluetoothPermission()) {
            error("缺少蓝牙权限")
            return
        }
        if (adapter?.isEnabled != true) {
            error("蓝牙未开启")
            return
        }
        mainHandler.postDelayed({
            if (running) error("测量超时，请唤醒体重秤后重试")
        }, 60_000L)
        if (savedDevice != null && !scanFirst) {
            canRescan = true
            mainHandler.postDelayed(connectionTimeout, 10_000L)
            status("正在寻找已保存的体重秤")
            connect(savedDevice)
        } else {
            scan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        canRescan = false
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        val previous = gatt
        gatt = null
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        status("正在寻找体重秤，请轻踩唤醒")
        scanResultCount = 0
        namedScaleResultCount = 0
        scanRejectCounts.clear()
        val bleScanner = scanner
        if (bleScanner == null) {
            error("当前设备不支持 BLE 扫描")
            return
        }
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner.startScan(null, settings, scanCallback)
        } catch (exception: RuntimeException) {
            scanning = false
            error("无法扫描，请检查蓝牙后重试", exception)
            return
        }
        mainHandler.postDelayed(scanTimeout, 20_000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning || !running) return
            scanResultCount += 1
            val name = result.scanRecord?.deviceName ?: result.device.name
            val outcome = ScaleAdvertisementParser.parseDetailed(
                name = name,
                address = result.device.address,
                rssi = result.rssi,
                record = result.scanRecord,
            )
            if (name?.startsWith("AFU-WL", ignoreCase = true) == true) {
                namedScaleResultCount += 1
            }
            val device = outcome.device
            if (device == null) {
                val reason = outcome.reason.substringBefore(':')
                scanRejectCounts[reason] = (scanRejectCounts[reason] ?: 0) + 1
                return
            }
            log(
                "scale_found name='${device.name}' address=${device.address} rssi=${device.rssi} " +
                    "mac=${device.actualMac ?: "-"} data=${device.manufacturerDataHex}",
            )
            stopScan()
            mainHandler.post { listener.onDeviceFound(device) }
            connect(device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) onScanResult(0, result)
        }

        override fun onScanFailed(errorCode: Int) {
            if (scanning && running) error("扫描失败，请稍后重试 ($errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: ScaleDevice) {
        activeDevice = device
        status("正在连接体重秤，请保持设备唤醒")
        log("connect_start name='${device.name}' address=${device.address} mac=${device.actualMac ?: "-"}")
        val remote = adapter?.getRemoteDevice(device.address) ?: return
        gatt = remote.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun onGattEvent(connection: BluetoothGatt, action: () -> Unit) {
        mainHandler.post {
            if (running && gatt === connection) {
                runCatching(action).onFailure { error("蓝牙连接中断，请重试", it) }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, gattStatus: Int, newState: Int) = onGattEvent(gatt) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (canRescan) {
                    mainHandler.removeCallbacks(connectionTimeout)
                    connectionTimeout.run()
                } else {
                    error("连接已断开，请唤醒体重秤后重试")
                }
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                canRescan = false
                mainHandler.removeCallbacks(connectionTimeout)
                status("已连接，正在准备测量")
                if (!gatt.discoverServices()) error("无法读取体重秤服务，请重试")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, gattStatus: Int) = onGattEvent(gatt) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                error("无法读取体重秤服务，请重试")
                return@onGattEvent
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                error("设备不支持测量服务")
                return@onGattEvent
            }
            log("services_ready count=${gatt.services.size}")
            enableNotify(gatt, service)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value?.copyOf() ?: return
            onGattEvent(gatt) { handleNotification(data) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            onGattEvent(gatt) { handleNotification(value) }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) = onGattEvent(gatt) {
            if (descriptor.uuid == CCC_UUID) {
                if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                    status("正在准备个人测量资料")
                    writeUserInfo(gatt)
                } else {
                    error("无法接收体重秤数据，请重试")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, gattStatus: Int) = onGattEvent(gatt) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS) error("无法设置测量资料，请重试")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, service: BluetoothGattService) {
        val characteristic = service.getCharacteristic(NOTIFY_UUID)
        if (characteristic == null) {
            error("未找到通知特征 FFB2")
            return
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            error("无法接收体重秤数据，请重试")
            return
        }
        val descriptor = characteristic.getDescriptor(CCC_UUID)
        if (descriptor == null) {
            error("未找到通知描述符")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) error("无法接收体重秤数据，请重试")
    }

    @SuppressLint("MissingPermission")
    private fun writeUserInfo(gatt: BluetoothGatt) {
        val device = activeDevice ?: return
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(WRITE_UUID)
        if (characteristic == null) {
            error("未找到写入特征 FFB1")
            return
        }
        val payload = protocol.encodeUserInfo(
            deviceType = device.protocolDeviceType,
            protocolVer = device.protocolVer,
            profile = profile,
        )
        val properties = characteristic.properties
        val writeType = when {
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            else -> {
                error("FFB1 不支持写入: properties=0x${properties.toString(16)}")
                return
            }
        }
        val mode = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            "without_response"
        } else {
            "with_response"
        }
        log("user_info_write mode=$mode properties=0x${properties.toString(16)} data=${payload.hex()}")

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (started) {
            status("请赤脚上秤，保持站立直到测量完成")
        } else {
            error("用户资料写入未启动")
        }
    }

    private fun handleNotification(data: ByteArray) {
        val events = protocol.decode(data)
        for (event in events) {
            when (event) {
                is ScalePacketEvent.Weight -> handleWeight(event)
                is ScalePacketEvent.Adc -> handleAdc(event)
            }
        }
    }

    private fun handleWeight(event: ScalePacketEvent.Weight) {
        if (event.stable) latestStableWeightKg = event.weightKg
        val bmi = bmi(event.weightKg)
        val measurement = Measurement(
            timeMillis = System.currentTimeMillis(),
            weightKg = event.weightKg,
            bmi = bmi,
            stable = event.stable,
            algType = event.algType,
        )
        mainHandler.post { listener.onMeasurement(measurement) }
    }

    private fun handleAdc(event: ScalePacketEvent.Adc) {
        if (completed) return
        val weight = event.weightKg ?: latestStableWeightKg ?: return
        val composition = BodyCompositionEstimator.estimate(
            weightKg = weight,
            heightCm = profile.heightCm,
            age = profile.age,
            sex = profile.sex,
            impedances = event.impedances,
        )
        val measurement = Measurement(
            timeMillis = System.currentTimeMillis(),
            weightKg = weight,
            bmi = bmi(weight),
            stable = true,
            rawAdc = event.adcs,
            impedances = event.impedances,
            algType = event.algType,
            composition = composition,
        )
        completed = true
        stop()
        log(
            "measurement_data weight=${measurement.weightKg} adc=${event.adcs} " +
                "impedances=${event.impedances} algType=${event.algType}",
        )
        mainHandler.post {
            listener.onMeasurement(measurement)
            listener.onCompleted(measurement)
        }
    }

    private fun bmi(weightKg: Double): Double {
        val height = profile.heightCm / 100.0
        return kotlin.math.round((weightKg / (height * height)) * 100.0) / 100.0
    }

    private fun status(message: String) {
        mainHandler.post { listener.onStatus(message) }
    }

    private fun log(message: String) {
        mainHandler.post { listener.onLog(message) }
    }

    private fun error(message: String, throwable: Throwable? = null) {
        mainHandler.post { listener.onError(message, throwable) }
    }

    private fun scanSummary(prefix: String): String {
        val rejects = scanRejectCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "$prefix results=$scanResultCount afuNames=$namedScaleResultCount rejects={$rejects}"
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb")
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
