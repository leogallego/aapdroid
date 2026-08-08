package io.github.leogallego.ansiblejane.assistant.data

import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import io.github.leogallego.ansiblejane.assistant.engine.DebugLog
import io.github.leogallego.ansiblejane.assistant.engine.Role
import io.github.leogallego.ansiblejane.data.ITokenManager
import io.github.leogallego.ansiblejane.platform.DataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class AssistantRepository(
    dataStoreFactory: DataStoreFactory,
    private val tokenManager: ITokenManager
) : IAssistantRepository {

    private val assistantDataStore = dataStoreFactory.createPreferencesDataStore("assistant_config")
    private val messages = mutableListOf<ChatMessage>()
    private val json = Json { ignoreUnknownKeys = true }
    private val _onHistoryCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val onHistoryCleared: SharedFlow<Unit> = _onHistoryCleared.asSharedFlow()
    private val _sessionTokens = MutableStateFlow(0)
    override val sessionTokensFlow: StateFlow<Int> = _sessionTokens.asStateFlow()

    private companion object {
        private const val TAG = "AssistantRepository"
        val KEY_LLM_CONFIGS = stringPreferencesKey("llm_configs")
        val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val KEY_MODEL_CONTEXT_TOKENS = stringPreferencesKey("model_context_tokens")
        val KEY_DISABLED_TOOLS = stringPreferencesKey("disabled_tools")
        val KEY_ENABLED_OVERRIDES = stringPreferencesKey("enabled_overrides")
        const val MAX_MESSAGES = 100
        private val STRING_INT_MAP = MapSerializer(String.serializer(), Int.serializer())
    }

    override fun addMessage(message: ChatMessage) {
        messages.add(message)
        message.tokenUsage?.let { usage ->
            _sessionTokens.update { it + usage.totalTokens }
        }
        if (messages.size > MAX_MESSAGES) {
            messages.removeAt(0)
        }
    }

    override fun getHistory(): List<ChatMessage> = messages.toList()

    override fun removeLastAssistantMessage() {
        val index = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index >= 0) messages.removeAt(index)
    }

    override fun removeLastUserMessage() {
        val index = messages.indexOfLast { it.role == Role.USER }
        if (index >= 0) messages.removeAt(index)
    }

    override fun clearHistory() {
        messages.clear()
        _sessionTokens.value = 0
        _onHistoryCleared.tryEmit(Unit)
    }

    override suspend fun saveLlmConfig(config: LlmProviderConfig) {
        val providerKey = when (config) {
            is LlmProviderConfig.OpenAiCompatible -> KnownProvider.fromUrl(config.url).name
            is LlmProviderConfig.OnDevice -> KnownProvider.LOCAL.name
        }

        val apiKey = when (config) {
            is LlmProviderConfig.OpenAiCompatible -> config.apiKey
            is LlmProviderConfig.OnDevice -> null
        }
        if (!apiKey.isNullOrBlank()) {
            tokenManager.saveLlmApiKey(providerKey, apiKey)
        }
        val strippedConfig = stripApiKey(config)

        val allConfigs = loadAllLlmConfigsRaw().toMutableMap()
        allConfigs[providerKey] = strippedConfig
        val mapJson = json.encodeToString(
            MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
            allConfigs
        )
        assistantDataStore.edit { prefs ->
            prefs[KEY_LLM_CONFIGS] = mapJson
            prefs[KEY_ACTIVE_PROVIDER] = providerKey
        }
    }

    override suspend fun loadLlmConfig(): LlmProviderConfig? {
        val prefs = assistantDataStore.data.first()
        val activeProvider = prefs[KEY_ACTIVE_PROVIDER] ?: return null
        val config = loadAllLlmConfigsRaw()[activeProvider] ?: return null
        return mergeApiKey(activeProvider, config)
    }

    override suspend fun saveAllLlmConfigs(configs: Map<String, LlmProviderConfig>) {
        for ((key, config) in configs) {
            val apiKey = when (config) {
                is LlmProviderConfig.OpenAiCompatible -> config.apiKey
                is LlmProviderConfig.OnDevice -> null
            }
            if (!apiKey.isNullOrBlank()) {
                tokenManager.saveLlmApiKey(key, apiKey)
            }
        }
        val stripped = configs.mapValues { (_, config) -> stripApiKey(config) }
        val mapJson = json.encodeToString(
            MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
            stripped
        )
        assistantDataStore.edit { prefs ->
            prefs[KEY_LLM_CONFIGS] = mapJson
        }
    }

    override suspend fun loadAllLlmConfigs(): Map<String, LlmProviderConfig> {
        val raw = loadAllLlmConfigsRaw()
        val keys = tokenManager.loadAllLlmApiKeys()
        return raw.mapValues { (providerKey, config) ->
            val apiKey = keys[providerKey]
            if (apiKey != null) mergeApiKeyValue(config, apiKey) else config
        }
    }

    private suspend fun loadAllLlmConfigsRaw(): Map<String, LlmProviderConfig> {
        val prefs = assistantDataStore.data.first()
        val mapJson = prefs[KEY_LLM_CONFIGS] ?: return emptyMap()
        return try {
            json.decodeFromString(
                MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
                mapJson
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "Failed to deserialize LLM configs: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun mergeApiKey(
        providerKey: String,
        config: LlmProviderConfig
    ): LlmProviderConfig {
        val apiKey = tokenManager.loadLlmApiKey(providerKey)
        return if (apiKey != null) mergeApiKeyValue(config, apiKey) else config
    }

    private fun mergeApiKeyValue(
        config: LlmProviderConfig,
        apiKey: String
    ): LlmProviderConfig =
        when (config) {
            is LlmProviderConfig.OpenAiCompatible -> config.copy(apiKey = apiKey)
            is LlmProviderConfig.OnDevice -> config
        }

    private fun stripApiKey(config: LlmProviderConfig): LlmProviderConfig =
        when (config) {
            is LlmProviderConfig.OpenAiCompatible -> config.copy(apiKey = null)
            is LlmProviderConfig.OnDevice -> config
        }

    override val activeConfigFlow: Flow<LlmProviderConfig?> =
        assistantDataStore.data.map { prefs ->
            val key = prefs[KEY_ACTIVE_PROVIDER] ?: return@map null
            val mapJson = prefs[KEY_LLM_CONFIGS] ?: return@map null
            try {
                val configs = json.decodeFromString(
                    MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
                    mapJson
                )
                val config = configs[key] ?: return@map null
                val apiKey = tokenManager.loadLlmApiKey(key)
                DebugLog.d(TAG, "ACTIVE_FLOW: provider=$key, apiKeyPresent=${apiKey != null}")
                if (apiKey != null) mergeApiKeyValue(config, apiKey) else config
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "Failed to deserialize active config from flow: ${e.message}")
                null
            }
        }.distinctUntilChanged()

    override val savedConfigsFlow: Flow<Map<String, LlmProviderConfig>> =
        assistantDataStore.data.map { prefs ->
            val mapJson = prefs[KEY_LLM_CONFIGS] ?: return@map emptyMap()
            try {
                val configs = json.decodeFromString(
                    MapSerializer(String.serializer(), LlmProviderConfig.serializer()),
                    mapJson
                )
                val keys = tokenManager.loadAllLlmApiKeys()
                configs.mapValues { (providerKey, config) ->
                    val apiKey = keys[providerKey]
                    if (apiKey != null) mergeApiKeyValue(config, apiKey) else config
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "Failed to deserialize saved configs from flow: ${e.message}")
                emptyMap()
            }
        }.distinctUntilChanged()

    override val activeProviderKeyFlow: Flow<String?> =
        assistantDataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_PROVIDER]
        }.distinctUntilChanged()

    override suspend fun switchActiveProvider(providerKey: String) {
        assistantDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROVIDER] = providerKey
        }
    }

    override suspend fun getModelContextTokens(modelId: String): Int? =
        loadModelContextTokensMap()[modelId]

    override suspend fun setModelContextTokens(modelId: String, contextTokens: Int) {
        val current = loadModelContextTokensMap().toMutableMap()
        current[modelId] = contextTokens
        val mapJson = json.encodeToString(STRING_INT_MAP, current)
        assistantDataStore.edit { prefs ->
            prefs[KEY_MODEL_CONTEXT_TOKENS] = mapJson
        }
    }

    override val modelContextTokensFlow: Flow<Map<String, Int>> =
        assistantDataStore.data.map { prefs ->
            val mapJson = prefs[KEY_MODEL_CONTEXT_TOKENS] ?: return@map emptyMap()
            try {
                json.decodeFromString(STRING_INT_MAP, mapJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "Failed to deserialize model context tokens: ${e.message}")
                emptyMap()
            }
        }.distinctUntilChanged()

    private suspend fun loadModelContextTokensMap(): Map<String, Int> {
        val prefs = assistantDataStore.data.first()
        val mapJson = prefs[KEY_MODEL_CONTEXT_TOKENS] ?: return emptyMap()
        return try {
            json.decodeFromString(STRING_INT_MAP, mapJson)
        } catch (e: Exception) {
            DebugLog.w(TAG, "Failed to deserialize model context tokens: ${e.message}")
            emptyMap()
        }
    }

    override suspend fun getDisabledTools(): Set<String> {
        val prefs = assistantDataStore.data.first()
        val encoded = prefs[KEY_DISABLED_TOOLS] ?: return emptySet()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.SetSerializer(String.serializer()),
                encoded
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "Failed to deserialize disabled tools: ${e.message}")
            emptySet()
        }
    }

    override suspend fun getEnabledOverrides(): Set<String> {
        val prefs = assistantDataStore.data.first()
        val encoded = prefs[KEY_ENABLED_OVERRIDES] ?: return emptySet()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.SetSerializer(String.serializer()),
                encoded
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "Failed to deserialize enabled overrides: ${e.message}")
            emptySet()
        }
    }

    override suspend fun saveToolState(disabled: Set<String>, enabledOverrides: Set<String>) {
        val setSerializer = kotlinx.serialization.builtins.SetSerializer(String.serializer())
        assistantDataStore.edit { prefs ->
            prefs[KEY_DISABLED_TOOLS] = json.encodeToString(setSerializer, disabled)
            prefs[KEY_ENABLED_OVERRIDES] = json.encodeToString(setSerializer, enabledOverrides)
        }
    }
}
