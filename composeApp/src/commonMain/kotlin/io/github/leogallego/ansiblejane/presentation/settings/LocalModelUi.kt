package io.github.leogallego.ansiblejane.presentation.settings

import aapremotecontrol.composeapp.generated.resources.Res
import aapremotecontrol.composeapp.generated.resources.agent_local_disk_insufficient
import aapremotecontrol.composeapp.generated.resources.agent_local_download_failed
import aapremotecontrol.composeapp.generated.resources.agent_local_download_network
import aapremotecontrol.composeapp.generated.resources.agent_local_download_timeout
import aapremotecontrol.composeapp.generated.resources.agent_local_hash_mismatch
import aapremotecontrol.composeapp.generated.resources.agent_local_import_failed
import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.LocalModel
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadErrorKind
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import org.jetbrains.compose.resources.StringResource
import kotlin.math.round

/** Presentation-layer catalog row for on-device models (UI must not import repository types). */
data class LocalModelUi(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val isRecommended: Boolean,
)

enum class DevicePerformanceUi {
    GOOD,
    OK,
    POOR,
}

sealed interface LocalModelDownloadUiState {
    data object Idle : LocalModelDownloadUiState

    data class Downloading(
        val modelId: String,
        val bytesReceived: Long,
        val totalBytes: Long,
        val isImport: Boolean = false,
    ) : LocalModelDownloadUiState

    data class Succeeded(val modelId: String) : LocalModelDownloadUiState

    data class Error(
        val modelId: String,
        val message: StringResource,
    ) : LocalModelDownloadUiState
}

fun LocalModel.toUi(): LocalModelUi = LocalModelUi(
    id = id,
    displayName = displayName,
    fileName = fileName,
    sizeBytes = sizeBytes,
    isRecommended = isRecommended,
)

fun DevicePerformance.toUi(): DevicePerformanceUi = when (this) {
    DevicePerformance.GOOD -> DevicePerformanceUi.GOOD
    DevicePerformance.OK -> DevicePerformanceUi.OK
    DevicePerformance.POOR -> DevicePerformanceUi.POOR
}

fun LocalModelDownloadState.toUi(): LocalModelDownloadUiState = when (this) {
    LocalModelDownloadState.Idle -> LocalModelDownloadUiState.Idle
    is LocalModelDownloadState.Downloading -> LocalModelDownloadUiState.Downloading(
        modelId = modelId,
        bytesReceived = bytesReceived,
        totalBytes = totalBytes,
        isImport = isImport,
    )
    is LocalModelDownloadState.Succeeded -> LocalModelDownloadUiState.Succeeded(modelId)
    is LocalModelDownloadState.Error -> LocalModelDownloadUiState.Error(
        modelId = modelId,
        message = kind.toUiMessage(),
    )
}

fun LocalModelDownloadErrorKind.toUiMessage(): StringResource = when (this) {
    LocalModelDownloadErrorKind.DISK -> Res.string.agent_local_disk_insufficient
    LocalModelDownloadErrorKind.HASH -> Res.string.agent_local_hash_mismatch
    LocalModelDownloadErrorKind.TIMEOUT -> Res.string.agent_local_download_timeout
    LocalModelDownloadErrorKind.NETWORK -> Res.string.agent_local_download_network
    LocalModelDownloadErrorKind.IMPORT -> Res.string.agent_local_import_failed
    LocalModelDownloadErrorKind.OTHER -> Res.string.agent_local_download_failed
}

/** CMP-safe size label (composeResources does not support `%1$.1f`). */
fun formatLocalModelSizeGb(sizeBytes: Long): String {
    val gb = sizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val tenths = round(gb * 10.0).toInt().coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}"
}
