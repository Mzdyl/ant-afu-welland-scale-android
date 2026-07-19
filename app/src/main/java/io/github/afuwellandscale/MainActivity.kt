package io.github.afuwellandscale

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import io.github.afuwellandscale.ble.BleScaleClient
import io.github.afuwellandscale.health.HealthConnectWriter
import io.github.afuwellandscale.model.Measurement
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile
import io.github.afuwellandscale.storage.AppLogStore
import io.github.afuwellandscale.storage.MeasurementStore
import io.github.afuwellandscale.storage.ProfileStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), BleScaleClient.Listener {
    private lateinit var appLogStore: AppLogStore
    private lateinit var profileStore: ProfileStore
    private lateinit var measurementStore: MeasurementStore
    private lateinit var healthWriter: HealthConnectWriter
    private lateinit var bleClient: BleScaleClient

    private lateinit var ageInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var sexGroup: RadioGroup
    private lateinit var maleRadio: RadioButton
    private lateinit var femaleRadio: RadioButton
    private lateinit var measureButton: Button
    private lateinit var syncButton: Button
    private lateinit var rescanButton: Button
    private lateinit var copyLogButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var logText: TextView

    private var latestMeasurement: Measurement? = null
    private var pendingScanFirst = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            startMeasurement(pendingScanFirst)
        } else {
            onError("蓝牙权限未授权")
        }
    }

    private val healthPermissionLauncher = registerForActivityResult(
        HealthConnectWriter.requestPermissionContract(),
    ) {
        lifecycleScope.launch {
            syncLatestMeasurement()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appLogStore = AppLogStore(this)
        profileStore = ProfileStore(this)
        measurementStore = MeasurementStore(this)
        healthWriter = HealthConnectWriter(this)
        bleClient = BleScaleClient(this, this)

        bindViews()
        loadProfile()
        loadLatestMeasurement()
        refreshLogPreview()
        measureButton.setOnClickListener { startMeasurement(scanFirst = false) }
        rescanButton.setOnClickListener { startMeasurement(scanFirst = true) }
        syncButton.setOnClickListener { requestOrSyncHealth() }
        copyLogButton.setOnClickListener { copyLogsToClipboard() }
        clearLogButton.setOnClickListener {
            appLogStore.clear()
            refreshLogPreview()
            statusText.text = "日志已清空"
        }
    }

    override fun onDestroy() {
        bleClient.stop()
        super.onDestroy()
    }

    private fun bindViews() {
        ageInput = findViewById(R.id.ageInput)
        heightInput = findViewById(R.id.heightInput)
        sexGroup = findViewById(R.id.sexGroup)
        maleRadio = findViewById(R.id.maleRadio)
        femaleRadio = findViewById(R.id.femaleRadio)
        measureButton = findViewById(R.id.measureButton)
        syncButton = findViewById(R.id.syncButton)
        rescanButton = findViewById(R.id.rescanButton)
        copyLogButton = findViewById(R.id.copyLogButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        logText = findViewById(R.id.logText)
    }

    private fun loadProfile() {
        val profile = profileStore.loadUserProfile()
        ageInput.setText(profile.age.toString())
        heightInput.setText(profile.heightCm.toString())
        if (profile.isMale) maleRadio.isChecked = true else femaleRadio.isChecked = true
    }

    private fun currentProfile(): UserProfile {
        val profile = UserProfile(
            age = ageInput.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: 23,
            sex = if (femaleRadio.isChecked) "female" else "male",
            heightCm = heightInput.text.toString().toIntOrNull()?.coerceIn(80, 240) ?: 170,
            unit = "kg",
        )
        profileStore.saveUserProfile(profile)
        return profile
    }

    private fun loadLatestMeasurement() {
        latestMeasurement = measurementStore.latest()
        latestMeasurement?.let {
            resultText.text = it.summary()
            statusText.text = "已载入最近一次测量结果"
        }
    }

    private fun startMeasurement(scanFirst: Boolean) {
        pendingScanFirst = scanFirst
        if (!hasBluetoothPermissions()) {
            bluetoothPermissionLauncher.launch(requiredBluetoothPermissions())
            return
        }
        appLogStore.append(
            "INFO",
            "start_measurement version=${BuildConfig.VERSION_NAME} scanFirst=$scanFirst",
        )
        resultText.text = "等待测量结果..."
        val savedDevice = profileStore.loadSavedDevice()
        bleClient.start(
            profile = currentProfile(),
            savedDevice = savedDevice,
            scanFirst = scanFirst,
        )
    }

    private fun requestOrSyncHealth() {
        appLogStore.append("INFO", "request_or_sync_health")
        when (healthWriter.availability()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                lifecycleScope.launch {
                    if (healthWriter.hasPermissions()) {
                        syncLatestMeasurement()
                    } else {
                        healthPermissionLauncher.launch(healthWriter.permissions)
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                statusText.text = "需要安装或更新 Health Connect"
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                    ),
                )
            }
            else -> statusText.text = "当前系统不可用 Health Connect"
        }
    }

    private suspend fun syncLatestMeasurement() {
        val measurement = latestMeasurement ?: measurementStore.latest()?.also {
            latestMeasurement = it
        }
        if (measurement == null) {
            statusText.text = "没有可同步的测量结果"
            appLogStore.append("WARN", "health_sync_skipped no_completed_measurement")
            return
        }
        runCatching {
            healthWriter.write(measurement)
        }.onSuccess {
            statusText.text = "已同步到 Health Connect"
            appLogStore.append("INFO", "health_sync_completed time=${measurement.timeMillis}")
            refreshLogPreview()
        }.onFailure {
            statusText.text = "同步失败: ${it.message ?: it.javaClass.simpleName}"
            appLogStore.append("ERROR", "health_sync_failed", it.message)
            refreshLogPreview()
        }
    }

    override fun onStatus(message: String) {
        statusText.text = message
        appLogStore.append("INFO", message)
        refreshLogPreview()
    }

    override fun onLog(message: String) {
        appLogStore.append("DEBUG", message)
        refreshLogPreview()
    }

    override fun onDeviceFound(device: ScaleDevice) {
        profileStore.saveDevice(device)
        statusText.text = "已保存设备: ${device.name} ${device.actualMac ?: device.address}"
        appLogStore.append("INFO", "device_found address=${device.address} name=${device.name} mac=${device.actualMac ?: ""}")
        refreshLogPreview()
    }

    override fun onMeasurement(measurement: Measurement) {
        resultText.text = measurement.summary()
    }

    override fun onCompleted(measurement: Measurement) {
        latestMeasurement = measurement
        measurementStore.save(measurement)
        resultText.text = measurement.summary()
        appLogStore.append(
            "INFO",
            "measurement_completed weight=${measurement.weightKg} bmi=${measurement.bmi} " +
                "bodyFat=${measurement.composition?.bodyFatPercent ?: "-"}",
        )
        refreshLogPreview()
        lifecycleScope.launch {
            if (healthWriter.hasPermissions()) {
                syncLatestMeasurement()
            } else {
                statusText.text = "测量完成，已保存。授权后可同步到 Health Connect。"
            }
        }
    }

    override fun onError(message: String, throwable: Throwable?) {
        statusText.text = message
        appLogStore.append("ERROR", message, throwable?.message)
        refreshLogPreview()
    }

    private fun refreshLogPreview() {
        val lines = appLogStore.latestLines(8)
        logText.text = if (lines.isEmpty()) {
            "暂无本地记录"
        } else {
            lines.joinToString("\n")
        }
    }

    private fun copyLogsToClipboard() {
        val text = appLogStore.readAll()
        if (text.isBlank()) {
            statusText.text = "没有可复制的日志"
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("app-log", text))
        statusText.text = "日志已复制到剪贴板"
    }

    private fun hasBluetoothPermissions(): Boolean {
        return requiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> {
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
}
