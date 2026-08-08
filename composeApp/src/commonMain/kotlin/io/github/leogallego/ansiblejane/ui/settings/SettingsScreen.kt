package io.github.leogallego.ansiblejane.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.LocalModel
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.presentation.settings.SettingsTab
import io.github.leogallego.ansiblejane.presentation.settings.SettingsUiState
import io.github.leogallego.ansiblejane.presentation.settings.SettingsViewModel
import io.github.leogallego.ansiblejane.ui.components.DetailScaffold
import org.jetbrains.compose.resources.stringResource
import aapremotecontrol.composeapp.generated.resources.*
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddInstance: () -> Unit,
    initialTab: String? = null,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localDownloadState by viewModel.localDownloadState.collectAsStateWithLifecycle()
    val localReadyIds by viewModel.localReadyIds.collectAsStateWithLifecycle()

    LaunchedEffect(initialTab) {
        initialTab?.let { tabName ->
            SettingsTab.entries.find { it.name == tabName }?.let { tab ->
                viewModel.selectTab(tab)
            }
        }
    }

    DetailScaffold(title = stringResource(Res.string.settings_title), onNavigateBack = onNavigateBack) {
        when (val state = uiState) {
            is SettingsUiState.Loading -> {
            }
            is SettingsUiState.Ready -> {
                SettingsContent(
                    state = state,
                    onSelectTab = { viewModel.selectTab(it) },
                    onSwitchInstance = { viewModel.switchInstance(it) },
                    onRemoveInstance = { viewModel.removeInstance(it) },
                    onShowDetails = { viewModel.showInstanceDetails(it) },
                    onDismissDetails = { viewModel.dismissDetails() },
                    onRefreshInstanceInfo = { viewModel.refreshInstanceInfo(it) },
                    onSaveInstanceEdits = { id, token, alias, trust ->
                        viewModel.saveInstanceEdits(id, token, alias, trust)
                    },
                    onAddInstance = onAddInstance,
                    onTimezoneSelected = { viewModel.setTimezone(it) },
                    onTimeFormatSelected = { viewModel.setTimeFormat(it) },
                    onThemeModeSelected = { viewModel.setThemeMode(it) },
                    onPollIntervalSelected = { viewModel.setPollInterval(it) },
                    onApprovalPollingToggled = { viewModel.setApprovalPollingEnabled(it) },
                    onClearHistory = { viewModel.clearHistory() },
                    onLogout = onLogout,
                    onSaveProviderConfig = { key, config -> viewModel.saveProviderConfig(key, config) },
                    onSwitchActiveProvider = { viewModel.switchActiveProvider(it) },
                    onFetchModels = { url, key -> viewModel.fetchAvailableModels(url, key) },
                    onClearFetchedModels = { viewModel.clearFetchedModels() },
                    localModelCatalog = viewModel.localModelCatalog,
                    localDownloadState = localDownloadState,
                    localReadyIds = localReadyIds,
                    hasAvx2Support = viewModel.hasAvx2Support,
                    onLocalModelPerformance = { viewModel.localModelPerformance(it) },
                    onDownloadLocalModel = { viewModel.downloadLocalModel(it) },
                    onCancelLocalModelDownload = { viewModel.cancelLocalModelDownload() },
                    onDeleteLocalModel = { viewModel.deleteLocalModel(it) },
                    onSelectLocalModel = { viewModel.selectLocalModel(it) },
                    onToggleMcp = { viewModel.toggleMcpEnabled(it) },
                    onAddMcpServer = { url, label, toolset, headers, useInstanceAuth ->
                        viewModel.addMcpServer(url, label, toolset, headers, useInstanceAuth)
                    },
                    onRemoveMcpServer = { viewModel.removeMcpServer(it) },
                    onToggleReadOnly = { url, readOnly -> viewModel.toggleServerReadOnly(url, readOnly) },
                    onToggleUseInstanceAuth = { url, useInstanceAuth ->
                        viewModel.toggleUseInstanceAuth(url, useInstanceAuth)
                    },
                    onToggleServerEnabled = { url, enabled -> viewModel.toggleServerEnabled(url, enabled) },
                    onToggleToolEnabled = { name, source, serverLabel, enabled -> viewModel.toggleToolEnabled(name, source, serverLabel, enabled) },
                    onToggleExpandMcpServer = { viewModel.toggleExpandMcpServer(it) },
                    onToggleExpandCategory = { viewModel.toggleExpandCategory(it) },
                    onRefreshMcpServer = { viewModel.refreshMcpServer(it) },
                    onRefreshAllTools = { viewModel.refreshAllTools() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState.Ready,
    onSelectTab: (SettingsTab) -> Unit,
    onSwitchInstance: (String) -> Unit,
    onRemoveInstance: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onDismissDetails: () -> Unit,
    onRefreshInstanceInfo: (String) -> Unit,
    onSaveInstanceEdits: (String, String?, String?, Boolean) -> Unit,
    onAddInstance: () -> Unit,
    onTimezoneSelected: (String?) -> Unit,
    onTimeFormatSelected: (io.github.leogallego.ansiblejane.ui.components.TimeFormat) -> Unit,
    onThemeModeSelected: (io.github.leogallego.ansiblejane.ui.components.ThemeMode) -> Unit,
    onPollIntervalSelected: (io.github.leogallego.ansiblejane.model.PollInterval) -> Unit,
    onApprovalPollingToggled: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onLogout: () -> Unit,
    onSaveProviderConfig: (String, io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig) -> Unit,
    onSwitchActiveProvider: (String) -> Unit,
    onFetchModels: (String, String?) -> Unit,
    onClearFetchedModels: () -> Unit,
    localModelCatalog: List<LocalModel>,
    localDownloadState: LocalModelDownloadState,
    localReadyIds: Set<String>,
    hasAvx2Support: Boolean,
    onLocalModelPerformance: (String) -> DevicePerformance,
    onDownloadLocalModel: (String) -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    onDeleteLocalModel: (String) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onToggleMcp: (Boolean) -> Unit,
    onAddMcpServer: (String, String, String?, Map<String, String>, Boolean) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleReadOnly: (String, Boolean) -> Unit,
    onToggleUseInstanceAuth: (String, Boolean) -> Unit,
    onToggleServerEnabled: (String, Boolean) -> Unit,
    onToggleToolEnabled: (String, ToolSource, String?, Boolean) -> Unit,
    onToggleExpandMcpServer: (String) -> Unit,
    onToggleExpandCategory: (String) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    onRefreshAllTools: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        SettingsTabSelector(
            currentTab = state.currentTab,
            onSelectTab = onSelectTab
        )

        when (state.currentTab) {
            SettingsTab.General -> GeneralTab(
                timezoneId = state.timezoneId,
                timeFormat = state.timeFormat,
                themeMode = state.themeMode,
                pollInterval = state.pollInterval,
                approvalPollingEnabled = state.approvalPollingEnabled,
                onTimezoneSelected = onTimezoneSelected,
                onTimeFormatSelected = onTimeFormatSelected,
                onThemeModeSelected = onThemeModeSelected,
                onPollIntervalSelected = onPollIntervalSelected,
                onApprovalPollingToggled = onApprovalPollingToggled,
                modifier = Modifier.weight(1f)
            )
            SettingsTab.Instances -> InstancesTab(
                instances = state.instances,
                selectedInstance = state.selectedInstance,
                selectedInstanceForDetails = state.selectedInstanceForDetails,
                discoveryRefreshing = state.discoveryRefreshing,
                discoveryError = state.discoveryError,
                instanceEditSaving = state.instanceEditSaving,
                instanceEditError = state.instanceEditError,
                onSwitchInstance = onSwitchInstance,
                onRemoveInstance = onRemoveInstance,
                onShowDetails = onShowDetails,
                onDismissDetails = onDismissDetails,
                onRefreshInstanceInfo = onRefreshInstanceInfo,
                onSaveInstanceEdits = onSaveInstanceEdits,
                onAddInstance = onAddInstance,
                onLogout = onLogout,
                modifier = Modifier.weight(1f)
            )
            SettingsTab.AiProvider -> AgentTab(
                activeProviderKey = state.activeProviderKey,
                activeConfig = state.activeConfig,
                savedConfigs = state.savedConfigs,
                fetchedModels = state.fetchedModels,
                modelFetchState = state.modelFetchState,
                onFetchModels = onFetchModels,
                onClearFetchedModels = onClearFetchedModels,
                onSaveProviderConfig = onSaveProviderConfig,
                onSwitchActiveProvider = onSwitchActiveProvider,
                localModelCatalog = localModelCatalog,
                localDownloadState = localDownloadState,
                localReadyIds = localReadyIds,
                hasAvx2Support = hasAvx2Support,
                onLocalModelPerformance = onLocalModelPerformance,
                onDownloadLocalModel = onDownloadLocalModel,
                onCancelLocalModelDownload = onCancelLocalModelDownload,
                onDeleteLocalModel = onDeleteLocalModel,
                onSelectLocalModel = onSelectLocalModel,
                onClearHistory = onClearHistory,
                modifier = Modifier.weight(1f)
            )
            SettingsTab.McpServers -> ToolsTab(
                mcpEnabled = state.mcpEnabled,
                mcpServers = state.mcpServers,
                connections = state.connections,
                mcpServerTools = state.mcpServerTools,
                expandedMcpServers = state.expandedMcpServers,
                onToggleMcp = onToggleMcp,
                onAddMcpServer = onAddMcpServer,
                onRemoveMcpServer = onRemoveMcpServer,
                onToggleReadOnly = onToggleReadOnly,
                onToggleUseInstanceAuth = onToggleUseInstanceAuth,
                onToggleServerEnabled = onToggleServerEnabled,
                onToggleToolEnabled = onToggleToolEnabled,
                onToggleExpandMcpServer = onToggleExpandMcpServer,
                onRefreshMcpServer = onRefreshMcpServer,
                isRefreshingTools = state.isRefreshingTools,
                onRefreshAllTools = onRefreshAllTools,
                modifier = Modifier.weight(1f)
            )
            SettingsTab.LocalTools -> LocalToolsTab(
                tools = state.localTools,
                expandedCategories = state.expandedCategories,
                onToggleCategory = onToggleExpandCategory,
                onToggleTool = { name, enabled ->
                    onToggleToolEnabled(name, ToolSource.LOCAL, null, enabled)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingsTabSelector(
    currentTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            SettingsTab.entries.forEach { tab ->
                val isSelected = currentTab == tab
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelectTab(tab) }
                        .testTag("tab_${tab.name.lowercase()}"),
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    } else {
                        Color.Transparent
                    }
                ) {
                    Text(
                        text = tab.label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
