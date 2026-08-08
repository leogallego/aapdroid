package io.github.leogallego.ansiblejane.assistant.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.leogallego.ansiblejane.assistant.data.IAssistantRepository
import io.github.leogallego.ansiblejane.assistant.data.LlmProviderConfig
import io.github.leogallego.ansiblejane.assistant.data.TokenSavingMode
import io.github.leogallego.ansiblejane.assistant.engine.ChatEngine
import io.github.leogallego.ansiblejane.assistant.engine.ChatEvent
import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapability
import io.github.leogallego.ansiblejane.assistant.engine.ModelCapabilityResolver
import io.github.leogallego.ansiblejane.assistant.engine.ResponseSource
import io.github.leogallego.ansiblejane.assistant.engine.Role
import io.github.leogallego.ansiblejane.assistant.engine.TokenUsage
import io.github.leogallego.ansiblejane.assistant.engine.ToolExecutor
import io.github.leogallego.ansiblejane.assistant.engine.AapRole
import io.github.leogallego.ansiblejane.assistant.engine.ToolRouter
import io.github.leogallego.ansiblejane.assistant.engine.ToolUsage
import io.github.leogallego.ansiblejane.assistant.engine.toAapRole
import io.github.leogallego.ansiblejane.assistant.llm.GeminiLlmProvider
import io.github.leogallego.ansiblejane.assistant.llm.KoogLlmProvider
import io.github.leogallego.ansiblejane.assistant.llm.LlmProvider
import io.github.leogallego.ansiblejane.assistant.llm.LocalLlmProviderFactory
import io.github.leogallego.ansiblejane.assistant.local.ILocalModelRepository
import io.github.leogallego.ansiblejane.assistant.tools.CachedMcpTool
import io.github.leogallego.ansiblejane.assistant.tools.LocalTool
import io.github.leogallego.ansiblejane.assistant.tools.Tool
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.data.IToolManifestRepository
import io.github.leogallego.ansiblejane.model.ToolManifest
import io.github.leogallego.ansiblejane.data.IMcpConnectionRepository
import io.github.leogallego.ansiblejane.assistant.tools.McpToolInvoker
import io.github.leogallego.ansiblejane.assistant.engine.DebugLog as Log

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistantViewModel(
    private val mcpConnectionRepository: IMcpConnectionRepository,
    private val mcpToolInvoker: McpToolInvoker,
    private val repository: IAssistantRepository,
    private val tokenManager: ITokenManager,
    private val manifestRepository: IToolManifestRepository,
    private val toolRouter: ToolRouter,
    private val localModelRepository: ILocalModelRepository,
    private val localTools: List<LocalTool> = emptyList()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    val activeInstance get() = tokenManager.activeInstance.value

    private var generateJob: Job? = null
    private var backgroundConnectJob: Job? = null
    private var cachedProvider: LlmProvider? = null
    private var cachedProviderKey: String? = null

    private val _llmConfig = MutableStateFlow<LlmProviderConfig?>(null)
    val llmConfig: StateFlow<LlmProviderConfig?> = _llmConfig.asStateFlow()

    init {
        Log.d(TAG, "INIT: ${localTools.size} local tools: ${localTools.map { it.spec.name }}")

        viewModelScope.launch {
            repository.activeConfigFlow.collect { config ->
                val oldKey = cachedProviderKey
                _llmConfig.update { config }
                if (config != null && oldKey != null) {
                    val configIdentity = providerCacheIdentity(config)
                    val cachedIdentity = when (config) {
                        // Strip trailing trustSelfSigned segment from OpenAI cache keys.
                        is LlmProviderConfig.OpenAiCompatible -> oldKey.substringBeforeLast("|")
                        is LlmProviderConfig.OnDevice -> oldKey
                    }
                    if (configIdentity != cachedIdentity) {
                        cachedProvider?.close()
                        cachedProvider = null
                        cachedProviderKey = null
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.onHistoryCleared.collect {
                updateState { copy(messages = persistentListOf()) }
            }
        }

        viewModelScope.launch {
            tokenManager.activeInstance
                .distinctUntilChangedBy { Triple(it?.id, it?.mcpEnabled, it?.mcpServerUrls) }
                .collect { instance ->
                    backgroundConnectJob?.cancel()
                    backgroundConnectJob = null

                    if (instance != null) {
                        _uiState.update { AssistantUiState.Loading }
                        mcpConnectionRepository.disconnectAll()

                        val manifest = manifestRepository.loadManifest(instance.id)
                        if (manifest != null) {
                            val configLabels = instance.mcpServerUrls
                                ?.filter { it.enabled }
                                ?.map { it.label }
                                ?.toSet() ?: emptySet()
                            val cachedTools = buildCachedTools(manifest)
                                .filter { it.serverLabel in configLabels }
                            mcpConnectionRepository.setCachedTools(cachedTools)
                            Log.d(TAG, "CACHE: loaded ${cachedTools.size} cached tools for ${instance.id}")
                        }

                        _uiState.update {
                            AssistantUiState.Active(
                                messages = repository.getHistory().toImmutableList(),
                                connections = mcpConnectionRepository.connections.value
                            )
                        }

                        backgroundConnectJob = viewModelScope.launch {
                            try {
                                if (manifest != null) {
                                    mcpConnectionRepository.connectAllWithCache(instance, manifest)
                                } else {
                                    mcpConnectionRepository.connectAll(instance)
                                }
                                mcpConnectionRepository.buildManifest(instance)?.let {
                                    manifestRepository.saveManifest(instance.id, it)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.d(TAG, "CACHE: background connect failed: ${e.message}")
                            }
                        }
                    } else {
                        mcpConnectionRepository.disconnectAll()
                        _uiState.update { AssistantUiState.Idle }
                    }
                }
        }

        viewModelScope.launch {
            mcpConnectionRepository.connections.collect { connections ->
                _uiState.update { current ->
                    if (current is AssistantUiState.Active) current.copy(connections = connections)
                    else current
                }
            }
        }

        viewModelScope.launch {
            repository.sessionTokensFlow.collect { tokens ->
                _uiState.update { current ->
                    if (current is AssistantUiState.Active) current.copy(sessionTokens = tokens)
                    else current
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val config = _llmConfig.value
        if (config == null) {
            _uiState.update { current ->
                if (current is AssistantUiState.Active) {
                    current.copy(
                        messages = (current.messages + ChatMessage(
                            role = Role.ASSISTANT,
                            content = "Please configure an LLM provider in settings first.",
                            source = ResponseSource.LLM
                        )).toImmutableList()
                    )
                } else current
            }
            return
        }

        val userMessage = ChatMessage(role = Role.USER, content = text)
        repository.addMessage(userMessage)

        updateState { copy(
            messages = repository.getHistory().toImmutableList(),
            isGenerating = true
        ) }

        val trustSelfSigned = tokenManager.activeInstance.value?.trustSelfSigned == true
        val provider = getOrCreateProvider(config, trustSelfSigned)

        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            toolRouter.initialize()

            mcpConnectionRepository.refreshConnections()
            val mcpTools = mcpConnectionRepository.mcpTools.value
            val activeInstance = tokenManager.activeInstance.value
            val serverConfigs = activeInstance?.mcpServerUrls ?: emptyList()
            // No instance or role not yet fetched → AUDITOR (fail-closed)
            val aapRole = activeInstance?.toAapRole() ?: AapRole.AUDITOR
            toolRouter.registerMcpTools(mcpTools)

            Log.d(TAG, "ROUTE: query=\"$text\", ${localTools.size} local, ${mcpTools.size} mcp, role=$aapRole")
            // #453 / #264: capability from active provider/model; Simple raises TokenSavingMode ceiling
            val capability = resolveCapabilityForConfig(config)
            val userMode = config.tokenSavingMode
            val mode = ModelCapabilityResolver.effectiveTokenSavingMode(capability, userMode)
            val queryResult = toolRouter.getToolsForQuery(
                query = text,
                serverConfigs = serverConfigs,
                aapRole = aapRole,
                tokenSavingMode = mode,
                capability = capability,
            )
            Log.d(
                TAG,
                "ROUTE: categoryMatched=${queryResult.categoryMatched}, " +
                    "${queryResult.tools.size} tools selected, capability=$capability, " +
                    "mode=$userMode→$mode"
            )

            // ToolRouter owns meta-search injection; empty means greetings / no match — do not re-inject
            if (queryResult.tools.isEmpty()) {
                val noCategory = !queryResult.categoryMatched
                Log.d(TAG, "ROUTE: no tools path — categoryMatched=${queryResult.categoryMatched}")
                val hasMcp = mcpConnectionRepository.mcpTools.value.isNotEmpty()
                val content = if (noCategory) {
                    "I can help you query your AAP instance. Try asking about:\n\n" +
                        "- **Inventory** — hosts, groups, inventories\n" +
                        "- **Jobs** — job templates, workflows, schedules\n" +
                        "- **Users** — users, teams, organizations, roles\n" +
                        "- **Credentials** — credentials, secrets\n" +
                        "- **Monitoring** — system health, instance status\n" +
                        "- **Configuration** — projects, settings, notifications\n\n" +
                        "You can also ask \"what tools do you have?\" to see all available tools."
                } else if (!hasMcp) {
                    "I don't have the right tools for that query. This may require an MCP server connection.\n\n" +
                        "I can help with:\n" +
                        "- **Inventory** — hosts, groups, inventories\n" +
                        "- **Jobs** — job templates, workflows, schedules\n" +
                        "- **Monitoring** — system health, instance status\n" +
                        "- **Credentials** — credentials, secrets\n" +
                        "- **Configuration** — projects, execution environments"
                } else {
                    "I don't have the right tools for that query. Try asking about:\n\n" +
                        "- **Inventory** — hosts, groups, inventories\n" +
                        "- **Jobs** — job templates, workflows, schedules\n" +
                        "- **Users** — users, teams, organizations, roles\n" +
                        "- **Credentials** — credentials, secrets\n" +
                        "- **Monitoring** — system health, instance status\n" +
                        "- **Configuration** — projects, settings, notifications"
                }
                val guidanceMsg = ChatMessage(role = Role.ASSISTANT, content = content, source = ResponseSource.LLM)
                repository.addMessage(guidanceMsg)
                updateState { copy(messages = repository.getHistory().toImmutableList(), isGenerating = false) }
                return@launch
            }

            // #453 Simple: MCP already excluded by ToolRouter; keep budget at 0 as defense in depth
            val mcpLimit = when {
                capability == ModelCapability.Simple -> 0
                mode == TokenSavingMode.STANDARD -> 10
                mode == TokenSavingMode.TOKEN_SAVER -> 5
                else -> 3 // TOOLS_ONLY
            }
            val matchedLocal = queryResult.tools.filterIsInstance<LocalTool>()
            val matchedMcp = queryResult.tools.filter { it !is LocalTool }.take(mcpLimit)
            val budgetedTools = matchedLocal + matchedMcp
            Log.d(TAG, "BUDGET: ${budgetedTools.size} tools [${budgetedTools.map { it.spec.name }}]")
            val toolSpecs = budgetedTools.map { it.spec }
            val toolExecutor = ToolExecutor(budgetedTools)
            val engine = ChatEngine(provider, toolExecutor)
            val maxTokens: Int? = null
            val contextChars = when (config) {
                is LlmProviderConfig.OnDevice -> resolveContextCharsForConfig(config)
                is LlmProviderConfig.OpenAiCompatible -> when (mode) {
                    TokenSavingMode.STANDARD -> 16_000
                    TokenSavingMode.TOKEN_SAVER -> 8_000
                    TokenSavingMode.TOOLS_ONLY -> 4_000
                }
            }

            val textBuilder = StringBuilder()
            val usedSources = mutableSetOf<String>()
            val usedTools = linkedMapOf<String, ToolUsage>()
            var pendingTokenUsage: TokenUsage? = null
            val localNames = matchedLocal.map { it.spec.name }.toSet()
            val toolByName: Map<String, Tool> = budgetedTools.associateBy { it.spec.name }

            updateState { copy(streamingText = "Thinking...", streamingTool = null) }

            engine.processMessage(
                text, repository.getHistory(), toolSpecs, maxTokens, contextChars,
                onConfirmationRequired = { toolName, description, _ ->
                    val deferred = CompletableDeferred<Boolean>()
                    val pending = PendingConfirmation(
                        toolName = toolName,
                        description = description,
                        continuation = deferred
                    )
                    updateState { copy(pendingConfirmation = pending) }
                    val result = deferred.await()
                    updateState { copy(pendingConfirmation = null) }
                    result
                },
                tokenSavingMode = mode
            ).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            textBuilder.append(event.text)
                            updateState { copy(streamingText = "Generating response...", streamingTool = null) }
                        }
                        is ChatEvent.ToolExecuting -> {
                            val toolSource = if (event.toolName in localNames) "local" else "mcp"
                            usedSources.add(toolSource)
                            val usage = ToolUsage(
                                name = event.toolName,
                                isDestructive = toolByName[event.toolName]?.isDestructive == true,
                            )
                            usedTools[event.toolName] = usage
                            updateState {
                                copy(
                                    streamingText = "Querying [$toolSource]: ${event.toolName}...",
                                    streamingTool = usage,
                                )
                            }
                        }
                        is ChatEvent.ToolResult -> {
                            updateState { copy(streamingText = "Processing results...", streamingTool = null) }
                            textBuilder.clear()
                        }
                        is ChatEvent.ConfirmationRequired -> {
                            updateState { copy(streamingText = "Waiting for confirmation...", streamingTool = null) }
                        }
                        is ChatEvent.AssistantMessage -> {
                            val responseSource = when {
                                usedSources.isEmpty() -> ResponseSource.LLM
                                usedSources.size > 1 -> ResponseSource.MIXED
                                "local" in usedSources -> ResponseSource.LOCAL
                                else -> ResponseSource.MCP
                            }
                            val finalMsg = ChatMessage(
                                role = Role.ASSISTANT,
                                content = event.fullText,
                                source = responseSource,
                                toolsUsed = usedTools.values.toList(),
                                tokenUsage = pendingTokenUsage
                            )
                            repository.addMessage(finalMsg)
                            updateState {
                                copy(
                                    messages = repository.getHistory().toImmutableList(),
                                    isGenerating = false,
                                    streamingText = null,
                                    streamingTool = null,
                                )
                            }
                        }
                        is ChatEvent.Error -> {
                            val errorMsg = ChatMessage(
                                role = Role.ASSISTANT,
                                content = "Error: ${event.message}"
                            )
                            repository.addMessage(errorMsg)
                            updateState {
                                copy(
                                    messages = repository.getHistory().toImmutableList(),
                                    isGenerating = false,
                                    streamingText = null,
                                    streamingTool = null,
                                )
                            }
                        }
                        is ChatEvent.TokenUsageReport -> {
                            pendingTokenUsage = event.usage
                        }
                    }
                }
        }
    }

    fun confirmAction(approved: Boolean) {
        val current = _uiState.value
        if (current is AssistantUiState.Active) {
            current.pendingConfirmation?.continuation?.complete(approved)
        }
    }

    fun stopGeneration() {
        generateJob?.cancel()
        updateState { copy(isGenerating = false, streamingText = null, streamingTool = null, pendingConfirmation = null) }
    }

    fun regenerateLastMessage() {
        val history = repository.getHistory()
        val lastUserMsg = history.lastOrNull { it.role == Role.USER } ?: return
        repository.removeLastAssistantMessage()
        repository.removeLastUserMessage()
        updateState { copy(messages = repository.getHistory().toImmutableList()) }
        sendMessage(lastUserMsg.content)
    }

    fun clearHistory() {
        repository.clearHistory()
        updateState { copy(messages = persistentListOf()) }
    }

    private fun getOrCreateProvider(
        config: LlmProviderConfig,
        trustSelfSigned: Boolean
    ): LlmProvider {
        when (config) {
            is LlmProviderConfig.OnDevice -> {
                val key = providerCacheIdentity(config)
                Log.d(TAG, "PROVIDER: onDevice modelId=${config.modelId}")
                cachedProvider?.let { if (cachedProviderKey == key) return it }
                cachedProvider?.close()
                return LocalLlmProviderFactory.create(config, localModelRepository).also {
                    cachedProvider = it
                    cachedProviderKey = key
                }
            }
            is LlmProviderConfig.OpenAiCompatible -> {
                Log.d(TAG, "PROVIDER: apiKeyPresent=${config.apiKey != null}, model=${config.model}")
                val key = "${providerCacheIdentity(config)}|$trustSelfSigned"
                cachedProvider?.let { if (cachedProviderKey == key) return it }
                cachedProvider?.close()
                val isGemini = config.url.contains("generativelanguage.googleapis.com")
                val provider: LlmProvider = if (isGemini) {
                    GeminiLlmProvider(apiKey = config.apiKey ?: "", modelId = config.model)
                } else {
                    KoogLlmProvider(config, trustSelfSigned)
                }
                return provider.also {
                    cachedProvider = it
                    cachedProviderKey = key
                }
            }
        }
    }

    private fun providerCacheIdentity(config: LlmProviderConfig): String = when (config) {
        is LlmProviderConfig.OnDevice ->
            "local|${config.modelId}|${resolveContextCharsForConfig(config)}"
        is LlmProviderConfig.OpenAiCompatible ->
            "${config.url}|${config.model}|${config.apiKey}"
    }

    private fun buildCachedTools(manifest: ToolManifest): List<CachedMcpTool> {
        return manifest.servers.flatMap { serverCache ->
            serverCache.tools.map { toolDef ->
                CachedMcpTool(
                    mcpToolDef = toolDef,
                    serverLabel = serverCache.label,
                    toolset = serverCache.toolset,
                    readOnly = serverCache.readOnly,
                    toolInvoker = mcpToolInvoker
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        backgroundConnectJob?.cancel()
        cachedProvider?.close()
        cachedProvider = null
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.Default) {
                mcpConnectionRepository.disconnectAll()
            }
        }
    }

    private inline fun updateState(crossinline transform: AssistantUiState.Active.() -> AssistantUiState.Active) {
        _uiState.update { current ->
            if (current is AssistantUiState.Active) current.transform()
            else current
        }
    }

    companion object {
        private const val TAG = "AssistantVM"
    }
}
