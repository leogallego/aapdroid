package io.github.leogallego.ansiblejane.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.ModelFetcher
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadErrorKind
import io.github.leogallego.ansiblejane.assistant.local.resolveOnDeviceContextTokens
import io.github.leogallego.ansiblejane.assistant.presentation.ModelFetchState
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.data.IToolManifestRepository
import io.github.leogallego.ansiblejane.data.IUserPreferencesRepository
import io.github.leogallego.ansiblejane.data.IAuthRepository
import io.github.leogallego.ansiblejane.data.IMcpConnectionRepository
import io.github.leogallego.ansiblejane.model.PollInterval
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.ui.components.DateFormatter
import io.github.leogallego.ansiblejane.ui.components.TimeFormat
import io.ktor.http.parseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.datetime.TimeZone

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val tokenManager: ITokenManager,
    private val authRepository: IAuthRepository,
    private val userPreferences: IUserPreferencesRepository,
    private val assistantRepository: IAssistantRepository,
    private val mcpConnectionRepository: IMcpConnectionRepository,
    private val manifestRepository: IToolManifestRepository,
    private val toolRouter: ToolRouter,
    private val localModelRepository: ILocalModelRepository,
    private val json: Json,
    private val modelFetcher: ModelFetcher
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val hasAvx2Support: Boolean = localModelRepository.hasAvx2Support()

    init {
        viewModelScope.launch {
            toolRouter.initialize()

            val configs = assistantRepository.loadAllLlmConfigs()
            val activeConfig = assistantRepository.loadLlmConfig()
            val initialActiveKey = assistantRepository.activeProviderKeyFlow.first()
            // Snapshot before combine: a separate collector can emit while still Loading, and
            // distinctUntilChanged would not replay — Ready would then miss persisted tokens.
            val initialLocalContextTokens = assistantRepository.modelContextTokensFlow.first()

            // Unfiltered on purpose: Settings enable/disable UI lists every registered
            // tool. Chat routing applies auditor filtering via getRoutableTools / getToolsForQuery.
            val initialLocalTools = toolRouter.getAllRegisteredTools()
                .filter { (_, source) -> source == ToolSource.LOCAL }
                .map { (tool, _) ->
                    LocalToolUiState(
                        name = tool.spec.name,
                        description = tool.spec.description,
                        category = ToolRouter.getCategoryForTool(tool.spec.name) ?: "OTHER",
                        isEnabled = toolRouter.isToolEnabled(tool.spec.name, ToolSource.LOCAL)
                    )
                }

            combine(
                tokenManager.instances,
                tokenManager.activeInstance,
                userPreferences.timezoneId,
                userPreferences.timeFormat,
                mcpConnectionRepository.connections
            ) { instances, active, timezone, timeFormat, connections ->
                val newZone = timezone?.let {
                    try { TimeZone.of(it) } catch (_: Exception) { null }
                }
                if (DateFormatter.zoneOverride != newZone) DateFormatter.zoneOverride = newZone
                if (DateFormatter.timeFormat != timeFormat) DateFormatter.timeFormat = timeFormat

                val current = _uiState.value
                val preservedTab = (current as? SettingsUiState.Ready)?.currentTab ?: SettingsTab.General
                val preservedFetchedModels = (current as? SettingsUiState.Ready)?.fetchedModels ?: emptyList()
                val preservedModelFetchState = (current as? SettingsUiState.Ready)?.modelFetchState ?: ModelFetchState.Idle
                val preservedSavedConfigs = (current as? SettingsUiState.Ready)?.savedConfigs ?: configs
                val preservedActiveConfig = (current as? SettingsUiState.Ready)?.activeConfig ?: activeConfig
                val preservedActiveKey = (current as? SettingsUiState.Ready)?.activeProviderKey ?: initialActiveKey
                val preservedDetails = (current as? SettingsUiState.Ready)?.selectedInstanceForDetails
                val preservedThemeMode = (current as? SettingsUiState.Ready)?.themeMode
                    ?: io.github.leogallego.ansiblejane.ui.components.ThemeMode.SYSTEM
                val preservedExpandedMcp = (current as? SettingsUiState.Ready)?.expandedMcpServers ?: emptySet()
                val preservedExpandedCats = (current as? SettingsUiState.Ready)?.expandedCategories ?: emptySet()
                val preservedLocalTools = (current as? SettingsUiState.Ready)?.localTools ?: initialLocalTools
                val preservedLocalDownload = (current as? SettingsUiState.Ready)?.localDownloadState
                    ?: localModelRepository.downloadState.value.toUi()
                val preservedLocalReadyIds = (current as? SettingsUiState.Ready)?.localReadyIds
                    ?: computeLocalReadyIds()
                // Avoid filesystem Downloads scan on the combine collector thread; refreshed
                // asynchronously via refreshLocalReadyIds() (Dispatchers.Default).
                val preservedLocalCatalog = (current as? SettingsUiState.Ready)?.localModelCatalog
                    ?: localModelRepository.catalog().map { it.toUi() }
                val preservedLocalContextTokens =
                    (current as? SettingsUiState.Ready)?.localModelContextTokens
                        ?: initialLocalContextTokens
                val preservedAvx2 = (current as? SettingsUiState.Ready)?.hasAvx2Support
                    ?: hasAvx2Support

                val allMcpTools = mcpConnectionRepository.mcpTools.value
                val mcpServerTools = allMcpTools
                    .groupBy { it.serverLabel }
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                    .mapValues { (_, tools) ->
                        tools.map { tool ->
                            val schema = tool.spec.parametersSchema.takeIf { it.isNotEmpty() }
                            McpToolUiState(
                                name = tool.spec.name,
                                description = tool.spec.description,
                                isEnabled = toolRouter.isToolEnabled(tool.spec.name, ToolSource.MCP, tool.serverLabel),
                                isAutoDisabled = toolRouter.isAutoDisabled(tool.spec.name, ToolSource.MCP, tool.serverLabel),
                                inputSchema = schema?.toString()
                            )
                        }
                    }

                SettingsUiState.Ready(
                    currentTab = preservedTab,
                    instances = instances,
                    selectedInstance = active,
                    selectedInstanceForDetails = preservedDetails,
                    timezoneId = timezone,
                    timeFormat = timeFormat,
                    themeMode = preservedThemeMode,
                    savedConfigs = preservedSavedConfigs,
                    activeConfig = preservedActiveConfig,
                    activeProviderKey = preservedActiveKey,
                    fetchedModels = preservedFetchedModels,
                    modelFetchState = preservedModelFetchState,
                    localModelCatalog = preservedLocalCatalog,
                    localDownloadState = preservedLocalDownload,
                    localReadyIds = preservedLocalReadyIds,
                    localModelContextTokens = preservedLocalContextTokens,
                    hasAvx2Support = preservedAvx2,
                    mcpEnabled = active?.mcpEnabled ?: false,
                    mcpServers = active?.mcpServerUrls ?: emptyList(),
                    connections = connections,
                    localTools = preservedLocalTools,
                    mcpServerTools = mcpServerTools,
                    expandedMcpServers = preservedExpandedMcp,
                    expandedCategories = preservedExpandedCats
                )
            }.collect { state ->
                _uiState.update { state }
            }
        }

        viewModelScope.launch {
            combine(
                userPreferences.themeMode,
                userPreferences.approvalPollInterval,
                tokenManager.activeInstance.flatMapLatest { instance ->
                    if (instance != null) userPreferences.approvalPollingEnabled(instance.id)
                    else flowOf(true)
                }
            ) { mode, interval, pollingEnabled ->
                Triple(mode, interval, pollingEnabled)
            }.collect { (mode, interval, pollingEnabled) ->
                updateReady {
                    copy(themeMode = mode, pollInterval = interval, approvalPollingEnabled = pollingEnabled)
                }
            }
        }

        viewModelScope.launch {
            combine(
                assistantRepository.activeProviderKeyFlow,
                assistantRepository.savedConfigsFlow,
                assistantRepository.activeConfigFlow
            ) { key, configs, config ->
                Triple(key, configs, config)
            }.collect { (key, configs, config) ->
                updateReady {
                    copy(activeProviderKey = key, savedConfigs = configs, activeConfig = config)
                }
            }
        }

        viewModelScope.launch {
            localModelRepository.downloadState
                .map { it.toUi() }
                .collect { downloadUi ->
                    updateReady { copy(localDownloadState = downloadUi) }
                }
        }

        viewModelScope.launch {
            assistantRepository.modelContextTokensFlow.collect { tokens ->
                updateReady { copy(localModelContextTokens = tokens) }
            }
        }

        // Downloads discovery may touch the filesystem (#479) — keep off the main thread.
        viewModelScope.launch {
            refreshLocalReadyIds()
        }
    }

    // --- Tab ---

    fun selectTab(tab: SettingsTab) {
        updateReady { copy(currentTab = tab) }
    }

    // --- Instances ---

    fun switchInstance(instanceId: String) {
        viewModelScope.launch {
            tokenManager.setActiveInstance(instanceId)
        }
    }

    fun removeInstance(instanceId: String) {
        viewModelScope.launch {
            authRepository.logoutInstance(instanceId)
        }
    }

    fun showInstanceDetails(instanceId: String) {
        updateReady {
            val instance = instances.find { it.id == instanceId }
            copy(selectedInstanceForDetails = instance)
        }
    }

    fun dismissDetails() {
        updateReady { copy(selectedInstanceForDetails = null, instanceEditError = null) }
    }

    fun saveInstanceEdits(
        instanceId: String,
        token: String?,
        alias: String?,
        trustSelfSigned: Boolean
    ) {
        viewModelScope.launch {
            updateReady { copy(instanceEditSaving = true, instanceEditError = null) }
            val result = authRepository.saveInstanceEdits(
                instanceId = instanceId,
                token = token,
                alias = alias,
                trustSelfSigned = trustSelfSigned
            )
            result.fold(
                onSuccess = { updated ->
                    updateReady {
                        copy(
                            selectedInstanceForDetails = updated,
                            instanceEditSaving = false
                        )
                    }
                },
                onFailure = { e ->
                    val updated = tokenManager.instances.value.find { it.id == instanceId }
                    updateReady {
                        copy(
                            selectedInstanceForDetails = updated,
                            instanceEditSaving = false,
                            instanceEditError = e.message ?: "Failed to save changes"
                        )
                    }
                }
            )
        }
    }

    fun refreshInstanceInfo(instanceId: String) {
        viewModelScope.launch {
            updateReady { copy(discoveryRefreshing = true, discoveryError = null) }
            val result = authRepository.refreshInstanceInfo(instanceId)
            result.fold(
                onSuccess = {
                    val updated = tokenManager.instances.value.find { it.id == instanceId }
                    updateReady { copy(selectedInstanceForDetails = updated) }
                },
                onFailure = { e ->
                    updateReady { copy(discoveryError = e.message ?: "Discovery failed") }
                }
            )
            updateReady { copy(discoveryRefreshing = false) }
        }
    }


    // --- General (Display) ---

    fun setTimezone(zoneId: String?) {
        viewModelScope.launch {
            userPreferences.setTimezoneId(zoneId)
        }
    }

    fun setTimeFormat(format: TimeFormat) {
        viewModelScope.launch {
            userPreferences.setTimeFormat(format)
        }
    }

    fun setThemeMode(mode: io.github.leogallego.ansiblejane.ui.components.ThemeMode) {
        viewModelScope.launch {
            userPreferences.setThemeMode(mode)
        }
    }

    fun setPollInterval(interval: PollInterval) {
        viewModelScope.launch {
            userPreferences.setApprovalPollInterval(interval)
        }
    }

    fun setApprovalPollingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val instanceId = tokenManager.activeInstance.value?.id ?: return@launch
            userPreferences.setApprovalPollingEnabled(instanceId, enabled)
        }
    }

    // --- Agent (LLM Config) ---

    fun saveProviderConfig(providerKey: String, config: LlmProviderConfig) {
        viewModelScope.launch {
            val current = assistantRepository.loadAllLlmConfigs().toMutableMap()
            current[providerKey] = config
            assistantRepository.saveAllLlmConfigs(current)
            val activeKey = assistantRepository.activeProviderKeyFlow.first()
            if (activeKey == null) {
                assistantRepository.switchActiveProvider(providerKey)
            }
        }
    }

    fun switchActiveProvider(providerKey: String) {
        viewModelScope.launch {
            assistantRepository.switchActiveProvider(providerKey)
        }
    }

    fun fetchAvailableModels(baseUrl: String, apiKey: String?) {
        viewModelScope.launch {
            updateReady { copy(modelFetchState = ModelFetchState.Loading) }
            val trustSelfSigned = tokenManager.activeInstance.value?.trustSelfSigned == true
            when (val result = modelFetcher.fetchModels(baseUrl, apiKey, trustSelfSigned)) {
                is ModelFetcher.Result.Success -> {
                    updateReady {
                        copy(
                            fetchedModels = result.models,
                            modelFetchState = ModelFetchState.Success(result.models.size)
                        )
                    }
                }
                is ModelFetcher.Result.Error -> {
                    updateReady { copy(modelFetchState = ModelFetchState.Error(result.message)) }
                }
            }
        }
    }

    fun clearFetchedModels() {
        updateReady { copy(fetchedModels = emptyList(), modelFetchState = ModelFetchState.Idle) }
    }

    fun downloadLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelRepository.download(modelId)
            refreshLocalReadyIds()
        }
    }

    fun cancelLocalModelDownload() {
        localModelRepository.cancelDownload()
    }

    fun importLocalModel(modelId: String, absolutePath: String) {
        viewModelScope.launch {
            localModelRepository.importFromPath(modelId, absolutePath)
            refreshLocalReadyIds()
        }
    }

    fun markLocalModelImportPreparing(modelId: String) {
        localModelRepository.markImportPreparing(modelId)
    }

    fun reportLocalModelImportPickFailed(modelId: String) {
        localModelRepository.notifyTransferError(modelId, LocalModelDownloadErrorKind.IMPORT)
    }

    fun deleteLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelRepository.delete(modelId)
            refreshLocalReadyIds()
        }
    }

    fun selectLocalModel(modelId: String) {
        viewModelScope.launch {
            val stored = assistantRepository.getModelContextTokens(modelId)
            val contextTokens = resolveOnDeviceContextTokens(modelId, stored ?: 0)
            val config = LlmProviderConfig.OnDevice(
                modelId = modelId,
                contextTokens = contextTokens,
            )
            val current = assistantRepository.loadAllLlmConfigs().toMutableMap()
            current[KnownProvider.LOCAL.name] = config
            assistantRepository.saveAllLlmConfigs(current)
            assistantRepository.switchActiveProvider(KnownProvider.LOCAL.name)
        }
    }

    fun setLocalModelContextTokens(modelId: String, contextTokens: Int) {
        viewModelScope.launch {
            val clamped = resolveOnDeviceContextTokens(modelId, contextTokens)
            assistantRepository.setModelContextTokens(modelId, clamped)
            val current = assistantRepository.loadAllLlmConfigs().toMutableMap()
            val existing = current[KnownProvider.LOCAL.name] as? LlmProviderConfig.OnDevice
            if (existing != null && existing.modelId == modelId) {
                current[KnownProvider.LOCAL.name] = existing.copy(contextTokens = clamped)
                assistantRepository.saveAllLlmConfigs(current)
            }
        }
    }

    fun localModelPerformance(modelId: String, contextTokens: Int? = null): DevicePerformanceUi {
        val resolved = contextTokens
            ?: (uiState.value as? SettingsUiState.Ready)?.localModelContextTokens?.get(modelId)
            ?: 0
        val tokens = resolveOnDeviceContextTokens(modelId, resolved)
        return localModelRepository.devicePerformance(modelId, tokens).toUi()
    }

    private fun computeLocalReadyIds(): Set<String> =
        localModelRepository.catalog()
            .map { it.id }
            .filter { localModelRepository.isReady(it) }
            .toSet()

    private fun buildLocalModelCatalog(readyIds: Set<String>): List<LocalModelUi> =
        localModelRepository.catalog().map { model ->
            val existing = if (model.id in readyIds) {
                null
            } else {
                localModelRepository.findExistingImportCandidate(model.id)
            }
            model.toUi(existingImportPath = existing)
        }

    private suspend fun refreshLocalReadyIds() {
        val (readyIds, catalogUi) = withContext(Dispatchers.Default) {
            val ready = computeLocalReadyIds()
            ready to buildLocalModelCatalog(ready)
        }
        updateReady {
            copy(
                localReadyIds = readyIds,
                localModelCatalog = catalogUi,
            )
        }
    }

    // --- Tools (MCP) ---

    fun toggleMcpEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val instance = tokenManager.activeInstance.value ?: return@launch
            if (!enabled) {
                tokenManager.updateMcpConfig(instance.id, false, instance.mcpServerUrls)
                return@launch
            }
            val base = "${instance.baseUrl.trimEnd('/')}:8448"
            val manualServers = instance.mcpServerUrls
                ?.filter { !it.isAutoDetected }
                ?: emptyList()
            val autoDetected = listOf(
                McpServerConfig(
                    url = "$base/job_management/mcp", label = "Jobs",
                    isAutoDetected = true, readOnly = true, toolset = "job_management"
                ),
                McpServerConfig(
                    url = "$base/inventory_management/mcp", label = "Inventory",
                    isAutoDetected = true, readOnly = true, toolset = "inventory_management"
                ),
                McpServerConfig(
                    url = "$base/system_monitoring/mcp", label = "Monitoring",
                    isAutoDetected = true, readOnly = true, toolset = "system_monitoring"
                ),
                McpServerConfig(
                    url = "$base/user_management/mcp", label = "Users",
                    isAutoDetected = true, readOnly = true, toolset = "user_management"
                ),
                McpServerConfig(
                    url = "$base/security_compliance/mcp", label = "Security",
                    isAutoDetected = true, readOnly = true, toolset = "security_compliance"
                ),
                McpServerConfig(
                    url = "$base/platform_configuration/mcp", label = "Configuration",
                    isAutoDetected = true, readOnly = true, toolset = "platform_configuration"
                ),
            )
            val manualUrls = manualServers.map { it.url }.toSet()
            tokenManager.updateMcpConfig(
                instance.id, true,
                manualServers + autoDetected.filter { it.url !in manualUrls }
            )
        }
    }

    fun addMcpServer(
        url: String,
        label: String,
        toolset: String? = null,
        headers: Map<String, String> = emptyMap(),
        useInstanceAuth: Boolean = true
    ) {
        viewModelScope.launch {
            val instance = tokenManager.activeInstance.value ?: return@launch
            val sanitizedUrl = url.trim().trimEnd('/')
            val parsed = parseUrl(sanitizedUrl) ?: return@launch
            if (parsed.protocol.name !in listOf("https", "wss")) return@launch
            val current = instance.mcpServerUrls?.toMutableList() ?: mutableListOf()
            current.add(
                McpServerConfig(
                    url = sanitizedUrl,
                    label = label,
                    toolset = toolset,
                    headers = headers,
                    useInstanceAuth = useInstanceAuth
                )
            )
            tokenManager.updateMcpConfig(instance.id, true, current)
        }
    }

    fun toggleUseInstanceAuth(url: String, useInstanceAuth: Boolean) {
        updateMcpServer(url) { it.copy(useInstanceAuth = useInstanceAuth) }
    }

    fun removeMcpServer(url: String) {
        viewModelScope.launch {
            val instance = tokenManager.activeInstance.value ?: return@launch
            val updated = instance.mcpServerUrls?.filter { it.url != url }
            val enabled = !updated.isNullOrEmpty()
            tokenManager.updateMcpConfig(instance.id, enabled, updated)
        }
    }

    fun toggleServerReadOnly(url: String, readOnly: Boolean) {
        updateMcpServer(url) { it.copy(readOnly = readOnly) }
    }

    fun toggleServerEnabled(url: String, enabled: Boolean) {
        updateMcpServer(url) { it.copy(enabled = enabled) }
    }

    private fun updateMcpServer(url: String, transform: (McpServerConfig) -> McpServerConfig) {
        viewModelScope.launch {
            val instance = tokenManager.activeInstance.value ?: return@launch
            val updated = instance.mcpServerUrls?.map {
                if (it.url == url) transform(it) else it
            }
            tokenManager.updateMcpConfig(instance.id, instance.mcpEnabled, updated)
        }
    }

    fun toggleToolEnabled(toolName: String, source: ToolSource, serverLabel: String? = null, enabled: Boolean) {
        viewModelScope.launch {
            toolRouter.toggleToolEnabled(toolName, source, serverLabel, enabled)
            updateReady {
                copy(
                    localTools = localTools.map {
                        if (it.name == toolName && source == ToolSource.LOCAL) {
                            it.copy(isEnabled = enabled)
                        } else it
                    },
                    mcpServerTools = mcpServerTools.mapValues { (server, tools) ->
                        tools.map {
                            if (it.name == toolName && source == ToolSource.MCP && server == serverLabel) {
                                it.copy(isEnabled = enabled)
                            } else it
                        }
                    }
                )
            }
        }
    }

    fun toggleExpandMcpServer(label: String) {
        updateReady { copy(expandedMcpServers = expandedMcpServers.toggled(label)) }
    }

    fun toggleExpandCategory(category: String) {
        updateReady { copy(expandedCategories = expandedCategories.toggled(category)) }
    }

    private val _isRefreshing = MutableStateFlow(false)

    fun refreshMcpServer(label: String) {
        viewModelScope.launch {
            mcpConnectionRepository.reconnectServer(label)
        }
    }

    fun refreshAllTools() {
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val instance = tokenManager.activeInstance.value ?: return@launch
                updateReady { copy(isRefreshingTools = true) }
                mcpConnectionRepository.connectAllWithCache(instance, forceRefresh = true)
                mcpConnectionRepository.buildManifest(instance)?.let {
                    manifestRepository.saveManifest(instance.id, it)
                }
            } finally {
                updateReady { copy(isRefreshingTools = false) }
                _isRefreshing.value = false
            }
        }
    }

    // --- Chat History ---

    fun clearHistory() {
        assistantRepository.clearHistory()
    }

    // --- Private helpers ---


    private inline fun updateReady(crossinline transform: SettingsUiState.Ready.() -> SettingsUiState.Ready) {
        _uiState.update { current ->
            if (current is SettingsUiState.Ready) current.transform()
            else current
        }
    }
}

private fun <T> Set<T>.toggled(item: T): Set<T> =
    if (item in this) this - item else this + item
