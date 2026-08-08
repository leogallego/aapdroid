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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.leogallego.ansiblejane.ui.theme.AnsibleJaneTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import io.github.leogallego.ansiblejane.assistant.presentation.ModelFetchState
import io.github.leogallego.ansiblejane.presentation.settings.DevicePerformanceUi
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelDownloadUiState
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelUi

@Composable
fun AgentTab(
    activeProviderKey: String?,
    activeConfig: LlmProviderConfig?,
    savedConfigs: Map<String, LlmProviderConfig>,
    fetchedModels: List<String>,
    modelFetchState: ModelFetchState,
    onFetchModels: (url: String, apiKey: String?) -> Unit,
    onClearFetchedModels: () -> Unit,
    onSaveProviderConfig: (providerKey: String, LlmProviderConfig) -> Unit,
    onSwitchActiveProvider: (String) -> Unit,
    localModelCatalog: List<LocalModelUi> = emptyList(),
    localDownloadState: LocalModelDownloadUiState = LocalModelDownloadUiState.Idle,
    localReadyIds: Set<String> = emptySet(),
    hasAvx2Support: Boolean = true,
    onLocalModelPerformance: (String) -> DevicePerformanceUi = { DevicePerformanceUi.POOR },
    onDownloadLocalModel: (String) -> Unit = {},
    onCancelLocalModelDownload: () -> Unit = {},
    onDeleteLocalModel: (String) -> Unit = {},
    onSelectLocalModel: (String) -> Unit = {},
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedProvider by remember { mutableStateOf<KnownProvider?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(Res.string.agent_section_llm_provider),
            style = MaterialTheme.typography.titleMedium
        )

        val sortedProviders = remember(activeProviderKey, savedConfigs) {
            KnownProvider.entries.sortedWith(compareByDescending<KnownProvider> {
                it.name == activeProviderKey
            }.thenByDescending { provider ->
                when (val cfg = savedConfigs[provider.name]) {
                    is LlmProviderConfig.OpenAiCompatible -> cfg.model.isNotBlank()
                    is LlmProviderConfig.OnDevice -> cfg.modelId.isNotBlank()
                    null -> false
                }
            })
        }

        sortedProviders.forEach { provider ->
            val isActive = activeProviderKey == provider.name
            val isExpanded = expandedProvider == provider

            if (provider == KnownProvider.LOCAL) {
                val onDeviceConfig = savedConfigs[provider.name] as? LlmProviderConfig.OnDevice
                val isConfigured = onDeviceConfig != null &&
                    onDeviceConfig.modelId.isNotBlank() &&
                    onDeviceConfig.modelId in localReadyIds
                LocalProviderCard(
                    config = onDeviceConfig,
                    isActive = isActive,
                    isConfigured = isConfigured,
                    isExpanded = isExpanded,
                    catalog = localModelCatalog,
                    downloadState = localDownloadState,
                    readyIds = localReadyIds,
                    hasAvx2Support = hasAvx2Support,
                    onPerformance = onLocalModelPerformance,
                    onToggleExpand = {
                        expandedProvider = if (isExpanded) null else provider
                    },
                    onDownload = onDownloadLocalModel,
                    onCancelDownload = onCancelLocalModelDownload,
                    onDelete = onDeleteLocalModel,
                    onSelect = { modelId ->
                        expandedProvider = null
                        onSelectLocalModel(modelId)
                    }
                )
            } else {
                val providerConfig = savedConfigs[provider.name] as? LlmProviderConfig.OpenAiCompatible
                val isConfigured = providerConfig != null && providerConfig.model.isNotBlank()

                ProviderCard(
                    provider = provider,
                    config = providerConfig,
                    isActive = isActive,
                    isConfigured = isConfigured,
                    isExpanded = isExpanded,
                    fetchedModels = if (isExpanded) fetchedModels else emptyList(),
                    modelFetchState = if (isExpanded) modelFetchState else ModelFetchState.Idle,
                    onToggleExpand = {
                        if (isExpanded) {
                            expandedProvider = null
                        } else {
                            expandedProvider = provider
                            onClearFetchedModels()
                        }
                    },
                    onFetchModels = onFetchModels,
                    onSave = { config ->
                        onSaveProviderConfig(provider.name, config)
                    },
                    onSetActive = {
                        expandedProvider = null
                        onSwitchActiveProvider(provider.name)
                    }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.agent_section_persona),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.agent_persona_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        OutlinedButton(
            onClick = { showClearHistoryConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("button_clear_history")
        ) {
            Text(stringResource(Res.string.agent_clear_chat_history))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(Res.string.clear_chat_title)) },
            text = {
                Text(stringResource(Res.string.clear_chat_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryConfirm = false
                        onClearHistory()
                    }
                ) { Text(stringResource(Res.string.btn_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text(stringResource(Res.string.btn_cancel)) }
            }
        )
    }
}

@Composable
private fun LocalProviderCard(
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
private fun LocalModelRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: KnownProvider,
    config: LlmProviderConfig.OpenAiCompatible?,
    isActive: Boolean,
    isConfigured: Boolean,
    isExpanded: Boolean,
    fetchedModels: List<String>,
    modelFetchState: ModelFetchState,
    onToggleExpand: () -> Unit,
    onFetchModels: (String, String?) -> Unit,
    onSave: (LlmProviderConfig) -> Unit,
    onSetActive: () -> Unit
) {
    val border = if (isActive) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else null

    val dotColor = when {
        isActive -> AnsibleJaneTheme.statusColors.successful
        isConfigured -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_provider_${provider.name}"),
        border = border
    ) {
        Column {
            // Collapsed header — always visible
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
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (isConfigured && config?.tokenSavingMode != null &&
                            config.tokenSavingMode != TokenSavingMode.STANDARD
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.padding(top = 1.dp)
                            ) {
                                Text(
                                    text = config.tokenSavingMode.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (isConfigured) config?.model ?: "" else stringResource(Res.string.agent_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ProviderConfigFields(
                    provider = provider,
                    config = config,
                    isActive = isActive,
                    isConfigured = isConfigured,
                    fetchedModels = fetchedModels,
                    modelFetchState = modelFetchState,
                    onFetchModels = onFetchModels,
                    onSave = onSave,
                    onSetActive = onSetActive
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderConfigFields(
    provider: KnownProvider,
    config: LlmProviderConfig.OpenAiCompatible?,
    isActive: Boolean,
    isConfigured: Boolean,
    fetchedModels: List<String>,
    modelFetchState: ModelFetchState,
    onFetchModels: (String, String?) -> Unit,
    onSave: (LlmProviderConfig) -> Unit,
    onSetActive: () -> Unit
) {
    var url by remember(provider) { mutableStateOf(config?.url ?: provider.baseUrl) }
    var model by remember(provider) { mutableStateOf(config?.model ?: "") }
    var apiKey by remember(provider) { mutableStateOf(config?.apiKey ?: "") }
    var tokenMode by remember(provider) { mutableStateOf(config?.tokenSavingMode ?: TokenSavingMode.STANDARD) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HorizontalDivider()

        // URL field
        if (provider.urlEditable) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(Res.string.agent_label_api_url)) },
                placeholder = { Text(provider.baseUrl.ifEmpty { stringResource(Res.string.agent_placeholder_custom_url) }) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = if (provider == KnownProvider.OLLAMA) {
                    { Text(stringResource(Res.string.agent_ollama_hint)) }
                } else null
            )
        }

        // Model selector
        if (provider != KnownProvider.CUSTOM) {
            val allModels = remember(provider, fetchedModels) {
                (provider.defaultModels + fetchedModels).distinct()
            }
            val filteredModels = remember(allModels, model) {
                if (model.isBlank()) allModels
                else allModels.filter { it.contains(model, ignoreCase = true) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            modelExpanded = true
                        },
                        label = { Text(stringResource(Res.string.agent_label_model)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("field_model_${provider.name}")
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        singleLine = true
                    )
                    if (filteredModels.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            filteredModels.take(20).forEach { modelId ->
                                DropdownMenuItem(
                                    text = {
                                        Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    onClick = {
                                        model = modelId
                                        modelExpanded = false
                                    }
                                )
                            }
                            if (filteredModels.size > 20) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(Res.string.agent_models_more, filteredModels.size - 20),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {},
                                    enabled = false
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = {
                        val effectiveUrl = if (provider.urlEditable) url else provider.baseUrl
                        onFetchModels(effectiveUrl, apiKey.ifBlank { null })
                    },
                    enabled = modelFetchState !is ModelFetchState.Loading
                ) {
                    if (modelFetchState is ModelFetchState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.cd_fetch_models))
                    }
                }
            }
            when (val state = modelFetchState) {
                is ModelFetchState.Success -> Text(
                    stringResource(Res.string.agent_models_available, state.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is ModelFetchState.Error -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {}
            }
        } else {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // API Key
        if (provider.requiresApiKey) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (provider == KnownProvider.CUSTOM) stringResource(Res.string.agent_label_api_key_optional) else stringResource(Res.string.agent_label_api_key)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("field_api_key_${provider.name}"),
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            if (apiKeyVisible) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (apiKeyVisible) stringResource(Res.string.cd_hide_api_key) else stringResource(Res.string.cd_show_api_key)
                        )
                    }
                }
            )
        }

        // Token saving mode — segmented buttons
        Text(
            text = stringResource(Res.string.agent_label_token_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TokenSavingMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = tokenMode == mode,
                    onClick = { tokenMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TokenSavingMode.entries.size
                    )
                ) {
                    Text(mode.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Save + Active toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val effectiveUrl = if (provider.urlEditable) url else provider.baseUrl
                    val newConfig = LlmProviderConfig.OpenAiCompatible(
                        url = effectiveUrl.trimEnd('/'),
                        model = model,
                        apiKey = apiKey.ifBlank { null },
                        tokenSavingMode = tokenMode
                    )
                    onSave(newConfig)
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("button_save_${provider.name}"),
                enabled = (url.isNotBlank() || !provider.urlEditable) && model.isNotBlank()
            ) {
                Text(stringResource(Res.string.btn_save))
            }
            if (isConfigured) {
                Switch(
                    checked = isActive,
                    onCheckedChange = { if (!isActive) onSetActive() },
                    modifier = Modifier.testTag("switch_active_${provider.name}")
                )
            }
        }
    }
}
