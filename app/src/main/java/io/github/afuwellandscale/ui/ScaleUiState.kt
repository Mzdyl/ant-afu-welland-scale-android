package io.github.afuwellandscale.ui

import io.github.afuwellandscale.model.Measurement
import io.github.afuwellandscale.model.ScaleDevice
import io.github.afuwellandscale.model.UserProfile

enum class HealthState {
    Unavailable,
    InstallRequired,
    PermissionRequired,
    Ready,
    Syncing,
    Synced,
    Error,
}

data class ScaleUiState(
    val profile: UserProfile = UserProfile(),
    val savedDevice: ScaleDevice? = null,
    val latestMeasurement: Measurement? = null,
    val currentMeasurement: Measurement? = null,
    val history: List<Measurement> = emptyList(),
    val status: String = "准备就绪",
    val healthMessage: String = "",
    val notice: String = "",
    val foregroundVisit: Int = 0,
    val isMeasuring: Boolean = false,
    val healthState: HealthState = HealthState.PermissionRequired,
    val logSizeBytes: Long = 0L,
)

data class ScaleActions(
    val onStartMeasurement: (Boolean) -> Unit,
    val onStopMeasurement: () -> Unit,
    val onSyncHealth: () -> Unit,
    val onProfileChange: (UserProfile) -> Unit,
    val onForgetDevice: () -> Unit,
    val onDeleteMeasurement: (Long) -> Unit,
    val onClearHistory: () -> Unit,
    val onCopyLogs: () -> Unit,
    val onClearLogs: () -> Unit,
)
