package io.github.leogallego.ansiblejane.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.assistant.data.KnownProvider
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.ModelFetcher
import io.github.leogallego.ansiblejane.assistant.local.DevicePerformance
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.local.LocalModel
import io.github.leogallego.ansiblejane.assistant.local.LocalModelDownloadState
import io.github.leogallego.ansiblejane.assistant.presentation.ModelFetchState
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.data.IToolManifestRepository
import io.github.leogallego.ansiblejane.data.IUserPreferencesRepository
import io.github.leogallego.ansiblejane.model.PollInterval
import io.github.leogallego.ansiblejane.model.McpServerConfig
import io.github.leogallego.ansiblejane.network.ApiVersion
import io.github.leogallego.ansiblejane.network.IAapApiProvider
import io.github.leogallego.ansiblejane.network.InstanceDiscovery
import io.github.leogallego.ansiblejane.network.createPlatformHttpClient
import io.github.leogallego.ansiblejane.network.mcp.McpServerManager
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.tools.ToolSource
import io.github.leogallego.ansiblejane.ui.components.DateFormatter
import io.github.leogallego.ansiblejane.ui.components.TimeFormat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.parseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.datetime.TimeZone

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val tokenManager: ITokenManager,
    private val apiProvider: IAapApiProvider,
    private val userPreferences: IUserPreferencesRepository,
    private val assistantRepository: IAssistantRepository,
    private val mcpServerManager: McpServerManager,
    private val manifestRepository: IToolManifestRepository,
    private val instanceDiscovery: InstanceDiscovery,
    private val toolRouter: ToolRouter,
    private val localModelRepository: ILocalModelRepository,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val localDownloadState: StateFlow<LocalModelDownloadState> = localModelRepository.downloadState

    private val _localReadyIds = MutableStateFlow(emptySet<String>())
    val localReadyIds: StateFlow<Set<String>> = _localReadyIds.asStateFlow()

    val localModelCatalog: List<LocalModel> = localModelRepository.catalog()
    val hasAvx2Support: Boolean = localModelRepository.hasAvx2Support()

    init {
        refreshLocalReadyIds()

        viewModelScope.launch {
            toolRouter.initialize()

            val configs = assistantRepository.loadAllLlmConfigs()
            val activeConfig = assistantRepository.loadLlmConfig()
            val initialActiveKey = assistantRepository.activeProviderKeyFlow.first()

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
                mcpServerManager.connections
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

                val allMcpTools = mcpServerManager.mcpTools.value
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
            apiProvider.evictInstance(instanceId)
            tokenManager.removeInstance(instanceId)
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
            try {
                val instance = tokenManager.getInstanceById(instanceId)
                if (instance == null) {
                    updateReady {
                        copy(
                            instanceEditSaving = false,
                            instanceEditError = "Instance not found"
                        )
                    }
                    return@launch
                }
                val apiVersion = try {
                    ApiVersion.valueOf(instance.apiVersion)
                } catch (_: Exception) {
                    ApiVersion.CONTROLLER_V2
                }
                tokenManager.saveInstance(
                    baseUrl = instance.baseUrl,
                    token = token ?: instance.token,
                    alias = alias,
                    apiVersion = apiVersion,
                    trustSelfSigned = trustSelfSigned,
                    existingId = instanceId
                )
                if (token != null || trustSelfSigned != instance.trustSelfSigned) {
                    apiProvider.evictInstance(instanceId)
                }
                val updated = tokenManager.instances.value.find { it.id == instanceId }
                updateReady {
                    copy(
                        selectedInstanceForDetails = updated,
                        instanceEditSaving = false
                    )
                }
            } catch (e: Exception) {
                val updated = tokenManager.instances.value.find { it.id == instanceId }
                updateReady {
                    copy(
                        selectedInstanceForDetails = updated,
                        instanceEditSaving = false,
                        instanceEditError = e.message ?: "Failed to save changes"
                    )
                }
            }
        }
    }

    fun refreshInstanceInfo(instanceId: String) {
        viewModelScope.launch {
            val instance = tokenManager.instances.value.find { it.id == instanceId } ?: return@launch
            updateReady { copy(discoveryRefreshing = true, discoveryError = null) }
            try {
                val apiVersion = try {
                    ApiVersion.valueOf(instance.apiVersion)
                } catch (_: Exception) {
                    ApiVersion.CONTROLLER_V2
                }
                val info = instanceDiscovery.discover(
                    instance.baseUrl, instance.token, apiVersion, instance.trustSelfSigned
                )
                tokenManager.updateInstanceInfo(instanceId, info)
                val updated = tokenManager.instances.value.find { it.id == instanceId }
                updateReady { copy(selectedInstanceForDetails = updated) }
            } catch (e: Exception) {
                updateReady { copy(discoveryError = e.message ?: "Discovery failed") }
            } finally {
                updateReady { copy(discoveryRefreshing = false) }
            }
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
            val client = buildLlmClient()
            try {
                val fetcher = ModelFetcher(client, json)
                when (val result = fetcher.fetchModels(baseUrl, apiKey)) {
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
            } finally {
                client.close()
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

    fun deleteLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelRepository.delete(modelId)
            refreshLocalReadyIds()
        }
    }

    fun selectLocalModel(modelId: String) {
        viewModelScope.launch {
            val config = LlmProviderConfig.OnDevice(modelId = modelId)
            val current = assistantRepository.loadAllLlmConfigs().toMutableMap()
            current[KnownProvider.LOCAL.name] = config
            assistantRepository.saveAllLlmConfigs(current)
            assistantRepository.switchActiveProvider(KnownProvider.LOCAL.name)
        }
    }

    fun localModelPerformance(modelId: String): DevicePerformance {
        val model = localModelCatalog.find { it.id == modelId }
        val contextTokens = model?.defaultContextTokens ?: 4_096
        return localModelRepository.devicePerformance(modelId, contextTokens)
    }

    private fun refreshLocalReadyIds() {
        _localReadyIds.value = localModelCatalog
            .map { it.id }
            .filter { localModelRepository.isReady(it) }
            .toSet()
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
            mcpServerManager.reconnectServer(label)
        }
    }

    fun refreshAllTools() {
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val instance = tokenManager.activeInstance.value ?: return@launch
                updateReady { copy(isRefreshingTools = true) }
                mcpServerManager.connectAllWithCache(instance, forceRefresh = true)
                mcpServerManager.buildManifest(instance)?.let {
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

    private fun buildLlmClient(): HttpClient {
        val instance = tokenManager.activeInstance.value
        return createPlatformHttpClient(trustSelfSigned = instance?.trustSelfSigned == true) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    private inline fun updateReady(crossinline transform: SettingsUiState.Ready.() -> SettingsUiState.Ready) {
        _uiState.update { current ->
            if (current is SettingsUiState.Ready) current.transform()
            else current
        }
    }
}

private fun <T> Set<T>.toggled(item: T): Set<T> =
    if (item in this) this - item else this + item
