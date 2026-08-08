package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import aapremotecontrol.composeapp.generated.resources.Res
import aapremotecontrol.composeapp.generated.resources.agent_clear_chat_history
import aapremotecontrol.composeapp.generated.resources.agent_persona_description
import aapremotecontrol.composeapp.generated.resources.agent_section_llm_provider
import aapremotecontrol.composeapp.generated.resources.agent_section_persona
import aapremotecontrol.composeapp.generated.resources.btn_cancel
import aapremotecontrol.composeapp.generated.resources.btn_clear
import aapremotecontrol.composeapp.generated.resources.clear_chat_message
import aapremotecontrol.composeapp.generated.resources.clear_chat_title
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.presentation.ModelFetchState
import io.github.leogallego.ansiblejane.presentation.settings.DevicePerformanceUi
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelDownloadUiState
import io.github.leogallego.ansiblejane.presentation.settings.LocalModelUi
import org.jetbrains.compose.resources.stringResource

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
    onImportLocalModel: (modelId: String, absolutePath: String) -> Unit = { _, _ -> },
    onImportLocalModelPreparing: (modelId: String) -> Unit = {},
    onImportLocalModelPickFailed: (modelId: String) -> Unit = {},
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
                    },
                    onImportFromPath = onImportLocalModel,
                    onImportPreparing = onImportLocalModelPreparing,
                    onImportPickFailed = onImportLocalModelPickFailed,
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

