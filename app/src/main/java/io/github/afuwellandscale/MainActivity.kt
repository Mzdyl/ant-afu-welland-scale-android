package io.github.afuwellandscale

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import io.github.afuwellandscale.ble.BleScaleClient
import io.github.afuwellandscale.health.HealthConnectWriter
import io.github.afuwellandscale.model.Measurement
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile
import io.github.afuwellandscale.storage.AppLogStore
import io.github.afuwellandscale.storage.MeasurementStore
import io.github.afuwellandscale.storage.ProfileStore
import io.github.afuwellandscale.ui.AfuScaleApp
import io.github.afuwellandscale.ui.HealthState
import io.github.afuwellandscale.ui.ScaleActions
import io.github.afuwellandscale.ui.ScaleUiState
import io.github.afuwellandscale.ui.theme.AfuScaleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), BleScaleClient.Listener {
    private lateinit var appLogStore: AppLogStore
    private lateinit var profileStore: ProfileStore
    private lateinit var measurementStore: MeasurementStore
    private lateinit var healthWriter: HealthConnectWriter
    private lateinit var bleClient: BleScaleClient

    private var uiState by mutableStateOf(ScaleUiState())
    private var pendingScanFirst = false
    private var hasAutoSyncedThisSession = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            startMeasurement(pendingScanFirst)
        } else {
            onError("蓝牙权限未授权")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val enabled = getSystemService(BluetoothManager::class.java).adapter.isEnabled
        if (enabled) startMeasurement(pendingScanFirst) else onError("蓝牙未开启")
    }

    private val healthPermissionLauncher = registerForActivityResult(
        HealthConnectWriter.requestPermissionContract(),
    ) {
        lifecycleScope.launch {
            if (healthWriter.hasPermissions()) {
                uiState = uiState.copy(healthState = HealthState.Ready, status = "Health Connect 已连接")
                hasAutoSyncedThisSession = true
                syncAllMeasurements()
            } else {
                uiState = uiState.copy(healthState = HealthState.PermissionRequired, status = "Health Connect 未授权")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appLogStore = AppLogStore(this)
        profileStore = ProfileStore(this)
        measurementStore = MeasurementStore(this)
        healthWriter = HealthConnectWriter(this)
        bleClient = BleScaleClient(this, this)

        val history = measurementStore.all()
        uiState = ScaleUiState(
            profile = profileStore.loadUserProfile(),
            savedDevice = profileStore.loadSavedDevice(),
            latestMeasurement = history.firstOrNull(),
            history = history,
            logSizeBytes = appLogStore.sizeBytes(),
        )

        setContent {
            AfuScaleTheme {
                AfuScaleApp(
                    state = uiState,
                    actions = ScaleActions(
                        onStartMeasurement = ::startMeasurement,
                        onStopMeasurement = ::stopMeasurement,
                        onSyncHealth = ::requestOrSyncHealth,
                        onProfileChange = ::updateProfile,
                        onForgetDevice = ::forgetDevice,
                        onDeleteMeasurement = ::deleteMeasurement,
                        onClearHistory = ::clearHistory,
                        onCopyLogs = ::copyLogsToClipboard,
                        onClearLogs = ::clearLogs,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::healthWriter.isInitialized) refreshHealthState()
    }

    override fun onDestroy() {
        if (::bleClient.isInitialized) bleClient.stop()
        super.onDestroy()
    }

    private fun startMeasurement(scanFirst: Boolean) {
        pendingScanFirst = scanFirst
        if (uiState.isMeasuring) return
        if (!hasBluetoothPermissions()) {
            bluetoothPermissionLauncher.launch(requiredBluetoothPermissions())
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        appLogStore.append(
            "INFO",
            "start_measurement version=${BuildConfig.VERSION_NAME} scanFirst=$scanFirst",
        )
        uiState = uiState.copy(
            isMeasuring = true,
            currentMeasurement = null,
            status = if (scanFirst) "正在重新扫描体脂秤" else "正在准备测量",
            logSizeBytes = appLogStore.sizeBytes(),
        )
        bleClient.start(
            profile = uiState.profile,
            savedDevice = profileStore.loadSavedDevice(),
            scanFirst = scanFirst,
        )
    }

    private fun stopMeasurement() {
        bleClient.stop()
        uiState = uiState.copy(isMeasuring = false, status = "测量已结束")
        appLogStore.append("INFO", "measurement_cancelled")
    }

    private fun updateProfile(profile: UserProfile) {
        profileStore.saveUserProfile(profile)
        uiState = uiState.copy(profile = profile)
    }

    private fun forgetDevice() {
        bleClient.stop()
        profileStore.clearDevice()
        uiState = uiState.copy(
            savedDevice = null,
            isMeasuring = false,
            status = "已忘记设备",
        )
        appLogStore.append("INFO", "saved_device_cleared")
    }

    private fun deleteMeasurement(timeMillis: Long) {
        measurementStore.delete(timeMillis)
        reloadHistory(status = "记录已删除")
    }

    private fun clearHistory() {
        measurementStore.clear()
        uiState = uiState.copy(
            history = emptyList(),
            latestMeasurement = null,
            currentMeasurement = null,
            status = "本地记录已清空",
        )
        appLogStore.append("INFO", "measurement_history_cleared")
    }

    private fun reloadHistory(status: String = uiState.status) {
        val history = measurementStore.all()
        uiState = uiState.copy(
            history = history,
            latestMeasurement = history.firstOrNull(),
            currentMeasurement = uiState.currentMeasurement?.takeIf { current ->
                history.any { it.timeMillis == current.timeMillis }
            },
            status = status,
        )
    }

    private fun requestOrSyncHealth() {
        appLogStore.append("INFO", "request_or_sync_health records=${uiState.history.size}")
        when (healthWriter.availability()) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                if (healthWriter.hasPermissions()) {
                    syncAllMeasurements()
                } else {
                    uiState = uiState.copy(healthState = HealthState.PermissionRequired)
                    healthPermissionLauncher.launch(healthWriter.permissions)
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                uiState = uiState.copy(healthState = HealthState.InstallRequired)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "market://details?id=com.google.android.apps.healthdata".toUri(),
                    ),
                )
            }
            else -> uiState = uiState.copy(
                healthState = HealthState.Unavailable,
                status = "当前系统不可用 Health Connect",
            )
        }
    }

    private suspend fun syncAllMeasurements() {
        val history = measurementStore.all()
        if (!healthWriter.hasPermissions()) {
            uiState = uiState.copy(healthState = HealthState.PermissionRequired)
            return
        }
        if (history.isEmpty()) {
            uiState = uiState.copy(healthState = HealthState.Ready, status = "Health Connect 已连接，暂无记录需要同步")
            return
        }
        uiState = uiState.copy(healthState = HealthState.Syncing, status = "正在同步 ${history.size} 条记录")
        runCatching {
            healthWriter.writeAll(history)
        }.onSuccess {
            uiState = uiState.copy(
                healthState = HealthState.Synced,
                status = "已同步 ${history.size} 条记录到 Health Connect",
            )
            appLogStore.append("INFO", "health_sync_completed count=${history.size}")
        }.onFailure {
            uiState = uiState.copy(
                healthState = HealthState.Error,
                status = "同步失败: ${it.message ?: it.javaClass.simpleName}",
            )
            appLogStore.append("ERROR", "health_sync_failed", it.message)
        }
        uiState = uiState.copy(logSizeBytes = appLogStore.sizeBytes())
    }

    private fun refreshHealthState() {
        when (healthWriter.availability()) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                if (healthWriter.hasPermissions()) {
                    if (!hasAutoSyncedThisSession && measurementStore.all().isNotEmpty()) {
                        hasAutoSyncedThisSession = true
                        syncAllMeasurements()
                    } else if (uiState.healthState != HealthState.Syncing) {
                        uiState = uiState.copy(healthState = HealthState.Ready)
                    }
                } else if (uiState.healthState != HealthState.Syncing) {
                    uiState = uiState.copy(healthState = HealthState.PermissionRequired)
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                uiState = uiState.copy(healthState = HealthState.InstallRequired)
            }
            else -> uiState = uiState.copy(healthState = HealthState.Unavailable)
        }
    }

    override fun onStatus(message: String) {
        appLogStore.append("INFO", message)
        uiState = uiState.copy(
            status = message,
            isMeasuring = if (message == "已断开") false else uiState.isMeasuring,
            logSizeBytes = appLogStore.sizeBytes(),
        )
    }

    override fun onLog(message: String) {
        appLogStore.append("DEBUG", message)
        uiState = uiState.copy(logSizeBytes = appLogStore.sizeBytes())
    }

    override fun onDeviceFound(device: ScaleDevice) {
        profileStore.saveDevice(device)
        appLogStore.append(
            "INFO",
            "device_found address=${device.address} name=${device.name} mac=${device.actualMac ?: ""}",
        )
        uiState = uiState.copy(savedDevice = device, logSizeBytes = appLogStore.sizeBytes())
    }

    override fun onMeasurement(measurement: Measurement) {
        uiState = uiState.copy(currentMeasurement = measurement)
    }

    override fun onCompleted(measurement: Measurement) {
        latestCompleteMeasurement(measurement)
        lifecycleScope.launch {
            if (healthWriter.availability() == HealthConnectClient.SDK_AVAILABLE && healthWriter.hasPermissions()) {
                runCatching { healthWriter.write(measurement) }
                    .onSuccess {
                        uiState = uiState.copy(
                            healthState = HealthState.Synced,
                            status = "测量完成，已同步到 Health Connect",
                        )
                        appLogStore.append("INFO", "health_auto_sync_completed time=${measurement.timeMillis}")
                    }
                    .onFailure {
                        uiState = uiState.copy(
                            healthState = HealthState.Error,
                            status = "测量已保存，自动同步失败",
                        )
                        appLogStore.append("ERROR", "health_auto_sync_failed", it.message)
                    }
            } else if (healthWriter.availability() == HealthConnectClient.SDK_AVAILABLE) {
                uiState = uiState.copy(
                    healthState = HealthState.PermissionRequired,
                    status = "测量完成，已保存到本机",
                )
            } else {
                uiState = uiState.copy(status = "测量完成，已保存到本机")
            }
            uiState = uiState.copy(logSizeBytes = appLogStore.sizeBytes())
        }
    }

    private fun latestCompleteMeasurement(measurement: Measurement) {
        runCatching { measurementStore.save(measurement) }
            .onFailure {
                onError("保存测量结果失败", it)
                return
            }
        appLogStore.append(
            "INFO",
            "measurement_completed weight=${measurement.weightKg} bmi=${measurement.bmi} " +
                "bodyFat=${measurement.composition?.bodyFatPercent ?: "-"}",
        )
        val history = measurementStore.all()
        uiState = uiState.copy(
            latestMeasurement = measurement,
            currentMeasurement = measurement,
            history = history,
            isMeasuring = false,
            status = "测量完成，已保存到本机",
            logSizeBytes = appLogStore.sizeBytes(),
        )
    }

    override fun onError(message: String, throwable: Throwable?) {
        appLogStore.append("ERROR", message, throwable?.message)
        uiState = uiState.copy(
            status = message,
            isMeasuring = false,
            logSizeBytes = appLogStore.sizeBytes(),
        )
    }

    private fun copyLogsToClipboard() {
        val text = appLogStore.readAll()
        if (text.isBlank()) {
            uiState = uiState.copy(status = "没有可复制的日志")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("afu-scale-log", text))
        uiState = uiState.copy(status = "日志已复制到剪贴板")
    }

    private fun clearLogs() {
        appLogStore.clear()
        uiState = uiState.copy(status = "日志已清空", logSizeBytes = 0L)
    }

    private fun hasBluetoothPermissions(): Boolean = requiredBluetoothPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
