package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aapremotecontrol.composeapp.generated.resources.Res
import aapremotecontrol.composeapp.generated.resources.agent_local_activate
import aapremotecontrol.composeapp.generated.resources.agent_local_active
import aapremotecontrol.composeapp.generated.resources.agent_local_avx_unsupported
import aapremotecontrol.composeapp.generated.resources.agent_local_cancel
import aapremotecontrol.composeapp.generated.resources.agent_local_delete
import aapremotecontrol.composeapp.generated.resources.agent_local_download
import aapremotecontrol.composeapp.generated.resources.agent_local_download_progress
import aapremotecontrol.composeapp.generated.resources.agent_local_not_downloaded
import aapremotecontrol.composeapp.generated.resources.agent_local_performance_good
import aapremotecontrol.composeapp.generated.resources.agent_local_performance_ok
import aapremotecontrol.composeapp.generated.resources.agent_local_performance_poor
import aapremotecontrol.composeapp.generated.resources.agent_local_ready
import aapremotecontrol.composeapp.generated.resources.agent_local_recommended
import aapremotecontrol.composeapp.generated.resources.agent_local_size_gb
import aapremotecontrol.composeapp.generated.resources.agent_local_title
import aapremotecontrol.composeapp.generated.resources.agent_not_configured
import aapremotecontrol.composeapp.generated.resources.cd_collapse
import aapremotecontrol.composeapp.generated.resources.cd_expand
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.presentation.settings.DevicePerformanceUi
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelDownloadUiState
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelUi
import io.github.leogallego.ansiblejane.ui.theme.AnsibleJaneTheme
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LocalProviderCard(
    config: LlmProviderConfig.OnDevice?,
    isActive: Boolean,
    isConfigured: Boolean,
    isExpanded: Boolean,
    catalog: List<LocalModelUi>,
    downloadState: LocalModelDownloadUiState,
    readyIds: Set<String>,
    hasAvx2Support: Boolean,
    onPerformance: (String) -> DevicePerformanceUi,
    onToggleExpand: () -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val border = if (isActive) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else null

    val dotColor = when {
        isActive -> AnsibleJaneTheme.statusColors.successful
        isConfigured -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val subtitle = when {
        !hasAvx2Support -> stringResource(Res.string.agent_local_avx_unsupported)
        config != null && config.modelId in readyIds ->
            catalog.find { it.id == config.modelId }?.displayName ?: config.modelId
        else -> stringResource(Res.string.agent_not_configured)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_provider_${KnownProvider.LOCAL.name}"),
        border = border
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.agent_local_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) {
                        stringResource(Res.string.cd_collapse)
                    } else {
                        stringResource(Res.string.cd_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider()

                    if (!hasAvx2Support) {
                        Text(
                            text = stringResource(Res.string.agent_local_avx_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("text_local_avx_unsupported")
                        )
                    }

                    catalog.forEach { model ->
                        LocalModelRow(
                            model = model,
                            isReady = model.id in readyIds,
                            isSelected = isActive && config?.modelId == model.id,
                            downloadState = downloadState,
                            performance = onPerformance(model.id),
                            actionsEnabled = hasAvx2Support,
                            onDownload = { onDownload(model.id) },
                            onCancelDownload = onCancelDownload,
                            onDelete = { onDelete(model.id) },
                            onSelect = { onSelect(model.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalModelRow(
    model: LocalModelUi,
    isReady: Boolean,
    isSelected: Boolean,
    downloadState: LocalModelDownloadUiState,
    performance: DevicePerformanceUi,
    actionsEnabled: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val downloading = downloadState as? LocalModelDownloadUiState.Downloading
    val isDownloadingThis = downloading?.modelId == model.id
    val error = downloadState as? LocalModelDownloadUiState.Error
    val errorForThis = error?.takeIf { it.modelId == model.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("row_local_model_${model.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (model.isRecommended) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = stringResource(Res.string.agent_local_recommended),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(
                        Res.string.agent_local_size_gb,
                        model.sizeBytes / (1024.0 * 1024.0 * 1024.0)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when (performance) {
                        DevicePerformanceUi.GOOD ->
                            stringResource(Res.string.agent_local_performance_good)
                        DevicePerformanceUi.OK ->
                            stringResource(Res.string.agent_local_performance_ok)
                        DevicePerformanceUi.POOR ->
                            stringResource(Res.string.agent_local_performance_poor)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (performance) {
                        DevicePerformanceUi.GOOD -> AnsibleJaneTheme.statusColors.successful
                        DevicePerformanceUi.OK -> MaterialTheme.colorScheme.tertiary
                        DevicePerformanceUi.POOR -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag("text_local_performance_${model.id}")
                )
                Text(
                    text = if (isReady) {
                        stringResource(Res.string.agent_local_ready)
                    } else {
                        stringResource(Res.string.agent_local_not_downloaded)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isDownloadingThis && downloading != null) {
            val progress = if (downloading.totalBytes > 0) {
                (downloading.bytesReceived.toFloat() / downloading.totalBytes.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            val percent = (progress * 100).toInt()
            Text(
                text = stringResource(Res.string.agent_local_download_progress, percent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("progress_local_download_${model.id}")
            )
        }

        if (errorForThis != null) {
            Text(
                text = stringResource(errorForThis.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("text_local_error_${model.id}")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isDownloadingThis -> {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        enabled = actionsEnabled,
                        modifier = Modifier.testTag("button_local_cancel")
                    ) {
                        Text(stringResource(Res.string.agent_local_cancel))
                    }
                }
                isReady -> {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = actionsEnabled,
                        modifier = Modifier.testTag("button_local_delete_${model.id}")
                    ) {
                        Text(stringResource(Res.string.agent_local_delete))
                    }
                    if (isSelected) {
                        Text(
                            text = stringResource(Res.string.agent_local_active),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("text_local_active_${model.id}")
                        )
                    } else {
                        Button(
                            onClick = onSelect,
                            enabled = actionsEnabled,
                            modifier = Modifier.testTag("button_local_activate_${model.id}")
                        ) {
                            Text(stringResource(Res.string.agent_local_activate))
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onDownload,
                        enabled = actionsEnabled &&
                            downloadState !is LocalModelDownloadUiState.Downloading,
                        modifier = Modifier.testTag("button_local_download_${model.id}")
                    ) {
                        Text(stringResource(Res.string.agent_local_download))
                    }
                }
            }
        }
    }
}

