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
import io.github.afuwellandscale.ui.MeasurementSession
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
    private var measurementSession = MeasurementSession()
    private var externalRequestInFlight = false
    private var resumeMeasurementAfterRecreation = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        externalRequestInFlight = false
        if (result.isNotEmpty() && hasBluetoothPermissions()) {
            startMeasurement(pendingScanFirst)
        } else {
            onError("蓝牙权限未授权")
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        externalRequestInFlight = false
        val enabled = getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        if (enabled) startMeasurement(pendingScanFirst) else onError("蓝牙未开启")
    }

    private val healthPermissionLauncher = registerForActivityResult(
        HealthConnectWriter.requestPermissionContract(),
    ) {
        externalRequestInFlight = false
        lifecycleScope.launch {
            if (healthWriter.hasPermissions()) {
                uiState = uiState.copy(healthState = HealthState.Ready, healthMessage = "Health Connect 已连接")
                hasAutoSyncedThisSession = true
                syncAllMeasurements()
            } else {
                uiState = uiState.copy(healthState = HealthState.PermissionRequired, healthMessage = "Health Connect 未授权")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        measurementSession = MeasurementSession(savedInstanceState?.getBoolean("auto_attempted") ?: false)
        externalRequestInFlight = savedInstanceState?.getBoolean("external_request") ?: false
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
        if (!externalRequestInFlight && measurementSession.shouldStart()) {
            uiState = uiState.copy(foregroundVisit = uiState.foregroundVisit + 1)
            startMeasurement(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            "auto_attempted",
            measurementSession.attempted && !uiState.isMeasuring && !resumeMeasurementAfterRecreation,
        )
        outState.putBoolean("external_request", externalRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        resumeMeasurementAfterRecreation = isChangingConfigurations && uiState.isMeasuring
        if (uiState.isMeasuring) stopMeasurement()
        measurementSession.leave(externalRequestInFlight, isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        if (::bleClient.isInitialized) bleClient.stop()
        super.onDestroy()
    }

    private fun startMeasurement(scanFirst: Boolean) {
        if (uiState.isMeasuring) return
        pendingScanFirst = scanFirst
        if (!hasBluetoothPermissions()) {
            uiState = uiState.copy(status = "请允许蓝牙和定位权限，以发现附近的体重秤")
            externalRequestInFlight = true
            bluetoothPermissionLauncher.launch(requiredBluetoothPermissions())
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) {
            onError("当前设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            uiState = uiState.copy(status = "请开启蓝牙，随后会自动连接体重秤")
            externalRequestInFlight = true
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
        runCatching {
            bleClient.start(
                profile = uiState.profile,
                savedDevice = profileStore.loadSavedDevice(),
                scanFirst = scanFirst,
            )
        }.onFailure { onError("无法开始测量，请检查蓝牙后重试", it) }
    }

    private fun stopMeasurement() {
        bleClient.stop()
        uiState = uiState.copy(isMeasuring = false, currentMeasurement = null, status = "测量已暂停，准备好后可重新开始")
        appLogStore.append("INFO", "measurement_cancelled")
    }

    private fun updateProfile(profile: UserProfile) {
        if (uiState.isMeasuring) return
        profileStore.saveUserProfile(profile)
        uiState = uiState.copy(profile = profile, notice = "个人资料已保存，下次测量生效")
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
                    externalRequestInFlight = true
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
                healthMessage = "当前系统不可用 Health Connect",
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
            uiState = uiState.copy(healthState = HealthState.Ready, healthMessage = "Health Connect 已连接，暂无记录需要同步")
            return
        }
        uiState = uiState.copy(healthState = HealthState.Syncing, healthMessage = "正在同步 ${history.size} 条记录")
        runCatching {
            healthWriter.writeAll(history)
        }.onSuccess {
            uiState = uiState.copy(
                healthState = HealthState.Synced,
                healthMessage = "已同步 ${history.size} 条记录到 Health Connect",
            )
            appLogStore.append("INFO", "health_sync_completed count=${history.size}")
        }.onFailure {
            uiState = uiState.copy(
                healthState = HealthState.Error,
                healthMessage = "同步失败: ${it.message ?: it.javaClass.simpleName}",
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
        if (!latestCompleteMeasurement(measurement)) return
        lifecycleScope.launch {
            if (healthWriter.availability() == HealthConnectClient.SDK_AVAILABLE && healthWriter.hasPermissions()) {
                runCatching { healthWriter.write(measurement) }
                    .onSuccess {
                        uiState = uiState.copy(
                            healthState = HealthState.Synced,
                            healthMessage = "最新测量已同步到 Health Connect",
                        )
                        appLogStore.append("INFO", "health_auto_sync_completed time=${measurement.timeMillis}")
                    }
                    .onFailure {
                        uiState = uiState.copy(
                            healthState = HealthState.Error,
                            healthMessage = "测量已保存，自动同步失败",
                        )
                        appLogStore.append("ERROR", "health_auto_sync_failed", it.message)
                    }
            } else if (healthWriter.availability() == HealthConnectClient.SDK_AVAILABLE) {
                uiState = uiState.copy(
                    healthState = HealthState.PermissionRequired,
                    healthMessage = "授权后可自动同步测量记录",
                )
            }
            uiState = uiState.copy(logSizeBytes = appLogStore.sizeBytes())
        }
    }

    private fun latestCompleteMeasurement(measurement: Measurement): Boolean {
        runCatching { measurementStore.save(measurement) }
            .onFailure {
                onError("保存测量结果失败", it)
                return false
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
        return true
    }

    override fun onError(message: String, throwable: Throwable?) {
        bleClient.stop()
        appLogStore.append("ERROR", message, throwable?.message)
        uiState = uiState.copy(
            status = message,
            isMeasuring = false,
            currentMeasurement = null,
            logSizeBytes = appLogStore.sizeBytes(),
        )
    }

    private fun copyLogsToClipboard() {
        val text = appLogStore.readAll()
        if (text.isBlank()) {
            uiState = uiState.copy(notice = "没有可复制的日志")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("afu-scale-log", text))
        uiState = uiState.copy(notice = "日志已复制到剪贴板")
    }

    private fun clearLogs() {
        appLogStore.clear()
        uiState = uiState.copy(notice = "日志已清空", logSizeBytes = 0L)
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
