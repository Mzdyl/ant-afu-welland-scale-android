package io.github.afuwellandscale.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.afuwellandscale.BuildConfig
import io.github.afuwellandscale.model.BodyComposition
import io.github.afuwellandscale.model.Measurement
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile
import io.github.afuwellandscale.ui.theme.AfuScaleTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    Measure("测量", Icons.Rounded.MonitorWeight),
    History("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AfuScaleApp(
    state: ScaleUiState,
    actions: ScaleActions,
) {
    var destinationName by rememberSaveable { mutableStateOf(Destination.Measure.name) }
    val destination = Destination.valueOf(destinationName)
    var selectedMeasurement by remember { mutableStateOf<Measurement?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("阿福沃莱", style = MaterialTheme.typography.titleLarge)
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            ShortNavigationBar {
                Destination.entries.forEach { item ->
                    ShortNavigationBarItem(
                        selected = destination == item,
                        onClick = { destinationName = item.name },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(initialScale = 0.98f))
                    .togetherWith(fadeOut() + scaleOut(targetScale = 0.98f))
                    .using(SizeTransform(clip = false))
            },
            label = "destination",
        ) { target ->
            when (target) {
                Destination.Measure -> MeasureScreen(state, actions)
                Destination.History -> HistoryScreen(
                    state = state,
                    onSelect = { selectedMeasurement = it },
                    onClear = actions.onClearHistory,
                )
                Destination.Settings -> SettingsScreen(
                    state = state,
                    actions = actions.copy(
                        onStartMeasurement = { scanFirst ->
                            destinationName = Destination.Measure.name
                            actions.onStartMeasurement(scanFirst)
                        },
                    ),
                )
            }
        }
    }

    selectedMeasurement?.let { measurement ->
        MeasurementDialog(
            measurement = measurement,
            onDismiss = { selectedMeasurement = null },
            onDelete = {
                actions.onDeleteMeasurement(measurement.timeMillis)
                selectedMeasurement = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MeasureScreen(state: ScaleUiState, actions: ScaleActions) {
    val measurement = state.currentMeasurement ?: state.latestMeasurement
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DeviceStatus(state) }
        item { MeasurementHero(state, measurement) }
        measurement?.composition?.let { composition ->
            item { CompositionMetrics(composition) }
        }
        item {
            Button(
                onClick = {
                    if (state.isMeasuring) actions.onStopMeasurement() else actions.onStartMeasurement(false)
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(
                    imageVector = if (state.isMeasuring) Icons.Rounded.Stop else Icons.Rounded.MonitorWeight,
                    contentDescription = null,
                )
                Spacer(Modifier.width(10.dp))
                Text(if (state.isMeasuring) "结束测量" else "开始测量")
            }
        }
        item {
            Text(
                "体成分为本地 BIA 公式估算，适合观察长期趋势，不用于医疗诊断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DeviceStatus(state: ScaleUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (state.savedDevice != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = if (state.savedDevice != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.savedDevice?.name ?: "等待发现体脂秤",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    state.savedDevice?.actualMac ?: "首次测量时自动扫描",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MeasurementHero(state: ScaleUiState, measurement: Measurement?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = state.isMeasuring) {
                    LoadingIndicator(
                        modifier = Modifier.size(38.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = measurement?.weightKg,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.88f))
                        .togetherWith(fadeOut() + scaleOut(targetScale = 1.08f))
                },
                label = "weight",
            ) { weight ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = weight?.let { "%.2f".format(it) } ?: "--",
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("kg", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Text(
                measurement?.let { "BMI %.2f".format(it.bmi) } ?: "尚无测量结果",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun CompositionMetrics(composition: BodyComposition) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("身体组成", style = MaterialTheme.typography.titleLarge)
        MetricRow(
            left = Metric("体脂", "%.1f %%".format(composition.bodyFatPercent), "%.1f kg".format(composition.fatMassKg)),
            right = Metric("肌肉", "%.1f %%".format(composition.musclePercent), "%.1f kg".format(composition.muscleMassKg)),
        )
        MetricRow(
            left = Metric("骨骼肌", "%.1f %%".format(composition.skeletalMusclePercent), "%.1f kg".format(composition.skeletalMuscleMassKg)),
            right = Metric("体水分", "%.1f %%".format(composition.waterPercent), "%.1f kg".format(composition.waterMassKg)),
        )
        MetricRow(
            left = Metric("蛋白质", "%.1f %%".format(composition.proteinPercent), "%.1f kg".format(composition.proteinMassKg)),
            right = Metric("骨量", "%.1f %%".format(composition.bonePercent), "%.1f kg".format(composition.boneMassKg)),
        )
        MetricRow(
            left = Metric("皮下脂肪", "%.1f %%".format(composition.subcutaneousFatPercent), "%.1f kg".format(composition.subcutaneousFatMassKg)),
            right = Metric("去脂体重", "%.1f kg".format(composition.fatFreeMassKg), composition.resistanceOhm?.let { "阻抗 %.0f Ω".format(it) }),
        )
    }
}

private data class Metric(val label: String, val value: String, val detail: String? = null)

@Composable
private fun MetricRow(left: Metric, right: Metric) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricTile(left, Modifier.weight(1f))
        MetricTile(right, Modifier.weight(1f))
    }
}

@Composable
private fun MetricTile(metric: Metric, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(metric.value, style = MaterialTheme.typography.titleLarge)
            metric.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                metric.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    state: ScaleUiState,
    onSelect: (Measurement) -> Unit,
    onClear: () -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.history.size} 次测量", style = MaterialTheme.typography.headlineMedium)
                    val change = historyChange(state.history)
                    Text(
                        change ?: "完成测量后记录会保存在本机",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.history.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("清空")
                    }
                }
            }
        }
        if (state.history.isEmpty()) {
            item { EmptyHistory() }
        } else {
            items(state.history, key = { it.timeMillis }) { measurement ->
                HistoryItem(measurement, onClick = { onSelect(measurement) })
            }
        }
    }
    if (showClearDialog) {
        ConfirmDialog(
            title = "清空全部记录？",
            message = "本机保存的测量记录将被删除，已经同步到 Health Connect 的数据不受影响。",
            confirmLabel = "清空",
            onConfirm = {
                onClear()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("还没有测量记录", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun HistoryItem(measurement: Measurement, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatDate(measurement.timeMillis), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.2f".format(measurement.weightKg), style = MaterialTheme.typography.headlineMedium)
                    Text(" kg", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                measurement.composition?.let {
                    Text("体脂 %.1f%%".format(it.bodyFatPercent), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "BMI %.2f".format(measurement.bmi),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScreen(state: ScaleUiState, actions: ScaleActions) {
    var ageText by rememberSaveable { mutableStateOf(state.profile.age.toString()) }
    var heightText by rememberSaveable { mutableStateOf(state.profile.heightCm.toString()) }
    var showForgetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            SettingsSection(Icons.Rounded.Info, "个人资料") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ageText,
                        onValueChange = { value ->
                            ageText = value.filter(Char::isDigit).take(3)
                            ageText.toIntOrNull()?.takeIf { it in 5..120 }?.let {
                                actions.onProfileChange(state.profile.copy(age = it))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("年龄") },
                        suffix = { Text("岁") },
                        singleLine = true,
                        isError = ageText.toIntOrNull()?.let { it !in 5..120 } ?: true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { value ->
                            heightText = value.filter(Char::isDigit).take(3)
                            heightText.toIntOrNull()?.takeIf { it in 80..240 }?.let {
                                actions.onProfileChange(state.profile.copy(heightCm = it))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("身高") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        isError = heightText.toIntOrNull()?.let { it !in 80..240 } ?: true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("male" to "男", "female" to "女").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = state.profile.sex == value,
                            onClick = { actions.onProfileChange(state.profile.copy(sex = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(Icons.Rounded.Bluetooth, "体脂秤") {
                ListItem(
                    supportingContent = {
                        Text(state.savedDevice?.actualMac ?: "测量时自动发现并保存")
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.MonitorWeight, contentDescription = null)
                    },
                ) { Text(state.savedDevice?.name ?: "未保存设备") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { actions.onStartMeasurement(true) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("重新扫描")
                    }
                    OutlinedButton(
                        onClick = { showForgetDialog = true },
                        enabled = state.savedDevice != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("忘记设备")
                    }
                }
            }
        }
        item {
            SettingsSection(Icons.Rounded.HealthAndSafety, "Health Connect") {
                ListItem(
                    supportingContent = { Text(healthStateDescription(state)) },
                    leadingContent = {
                        Icon(Icons.Rounded.HealthAndSafety, contentDescription = null)
                    },
                ) { Text(healthStateTitle(state.healthState)) }
                if (state.healthState in setOf(
                        HealthState.PermissionRequired,
                        HealthState.InstallRequired,
                        HealthState.Error,
                    )
                ) {
                    FilledTonalButton(
                        onClick = actions.onSyncHealth,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.HealthAndSafety, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (state.healthState) {
                                HealthState.InstallRequired -> "安装 Health Connect"
                                HealthState.Error -> "重试连接"
                                else -> "连接 Health Connect"
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(Icons.Rounded.Settings, "诊断") {
                Text(
                    "本地日志 ${formatBytes(state.logSizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = actions.onCopyLogs, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("复制日志")
                    }
                    OutlinedButton(onClick = actions.onClearLogs, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("清空日志")
                    }
                }
            }
        }
        item {
            SettingsSection(Icons.Rounded.Info, "关于") {
                Text("阿福沃莱体重秤", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "仅适用于 AFU-WL-TZ-A1。体重和阻抗来自设备，其他身体组成指标由本地 BIA 公式估算。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showForgetDialog) {
        ConfirmDialog(
            title = "忘记这台体脂秤？",
            message = "下次测量时会重新扫描并自动保存设备。",
            confirmLabel = "忘记",
            onConfirm = {
                actions.onForgetDevice()
                showForgetDialog = false
            },
            onDismiss = { showForgetDialog = false },
        )
    }
}

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun MeasurementDialog(
    measurement: Measurement,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.MonitorWeight, contentDescription = null) },
        title = { Text(formatDate(measurement.timeMillis)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.2f kg · BMI %.2f".format(measurement.weightKg, measurement.bmi), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Text(measurement.summary(), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("删除")
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatDate(timeMillis: Long): String {
    return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timeMillis))
}

private fun historyChange(history: List<Measurement>): String? {
    if (history.size < 2) return null
    val change = history[0].weightKg - history[1].weightKg
    return when {
        change > 0.005 -> "较上次 +%.2f kg".format(change)
        change < -0.005 -> "较上次 %.2f kg".format(change)
        else -> "与上次持平"
    }
}

private fun healthStateTitle(state: HealthState): String = when (state) {
    HealthState.Unavailable -> "当前系统不可用"
    HealthState.InstallRequired -> "需要安装或更新"
    HealthState.PermissionRequired -> "等待授权"
    HealthState.Ready -> "已连接"
    HealthState.Syncing -> "正在同步"
    HealthState.Synced -> "同步完成"
    HealthState.Error -> "同步遇到问题"
}

private fun healthStateDescription(state: ScaleUiState): String = when (state.healthState) {
    HealthState.Ready -> "可写入体重、体脂、去脂体重、体水分和骨量"
    HealthState.Synced -> "${state.history.size} 条本地记录已提交"
    HealthState.PermissionRequired -> "授权后可自动同步每次完整测量"
    HealthState.InstallRequired -> "点击下方按钮打开应用商店"
    HealthState.Syncing -> "正在提交本地记录"
    HealthState.Error -> state.status
    HealthState.Unavailable -> "设备不支持 Health Connect"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
}

@Preview(name = "测量页", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(
    name = "测量页 - 深色",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MeasurePreview() {
    AfuScaleTheme {
        AfuScaleApp(sampleState(), previewActions())
    }
}

@Preview(name = "记录页", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HistoryPreview() {
    AfuScaleTheme {
        HistoryScreen(sampleState(), onSelect = {}, onClear = {})
    }
}

@Preview(name = "设置页", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SettingsPreview() {
    AfuScaleTheme {
        SettingsScreen(sampleState(), previewActions())
    }
}

private fun sampleState(): ScaleUiState {
    val measurement = Measurement(
        timeMillis = 1_784_456_573_294,
        weightKg = 58.7,
        bmi = 20.31,
        stable = true,
        rawAdc = listOf(506.0, 465.0),
        impedances = listOf(506.0, 465.0),
        algType = 41,
        composition = BodyComposition(
            model = "segal_3a_male_lt20",
            resistanceOhm = 506.0,
            bodyFatPercent = 11.4,
            fatMassKg = 6.7,
            fatFreeMassKg = 52.0,
            musclePercent = 84.1,
            muscleMassKg = 49.4,
            waterPercent = 62.0,
            waterMassKg = 36.4,
            proteinPercent = 21.1,
            proteinMassKg = 12.4,
            bonePercent = 4.5,
            boneMassKg = 2.6,
            skeletalMusclePercent = 44.3,
            skeletalMuscleMassKg = 26.0,
            subcutaneousFatPercent = 8.2,
            subcutaneousFatMassKg = 4.8,
        ),
    )
    return ScaleUiState(
        profile = UserProfile(age = 23, sex = "male", heightCm = 170),
        savedDevice = ScaleDevice(
            address = "D0:5C:00:1A:25:57",
            name = "AFU-WL-TZ-A1",
            rssi = -60,
            manufacturerDataHex = "AC2757251A005CD001",
            actualMac = "D0:5C:00:1A:25:57",
            deviceSubtype = 7,
            protocolVer = 1,
            protocolDeviceType = 0x27,
        ),
        latestMeasurement = measurement,
        currentMeasurement = measurement,
        history = listOf(measurement, measurement.copy(timeMillis = measurement.timeMillis - 86_400_000, weightKg = 58.4)),
        status = "测量完成，已保存到本机",
        healthState = HealthState.Ready,
        logSizeBytes = 18_240,
    )
}

private fun previewActions() = ScaleActions(
    onStartMeasurement = { _ -> },
    onStopMeasurement = {},
    onSyncHealth = {},
    onProfileChange = { _ -> },
    onForgetDevice = {},
    onDeleteMeasurement = { _ -> },
    onClearHistory = {},
    onCopyLogs = {},
    onClearLogs = {},
)
