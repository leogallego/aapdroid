package io.github.leogallego.ansiblejane.assistant.data

import io.github.leogallego.ansiblejane.assistant.engine.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface IAssistantRepository {
    fun addMessage(message: ChatMessage)
    fun getHistory(): List<ChatMessage>
    fun removeLastAssistantMessage()
    fun removeLastUserMessage()
    fun clearHistory()
    val onHistoryCleared: SharedFlow<Unit>
    val sessionTokensFlow: StateFlow<Int>
    suspend fun saveLlmConfig(config: LlmProviderConfig)
    suspend fun loadLlmConfig(): LlmProviderConfig?
    suspend fun saveAllLlmConfigs(configs: Map<String, LlmProviderConfig>)
    suspend fun loadAllLlmConfigs(): Map<String, LlmProviderConfig>
    val activeConfigFlow: Flow<LlmProviderConfig?>
    val savedConfigsFlow: Flow<Map<String, LlmProviderConfig>>
    val activeProviderKeyFlow: Flow<String?>
    suspend fun switchActiveProvider(providerKey: String)
    /** Per-model on-device context preference; `null` if unset (use catalog default). */
    suspend fun getModelContextTokens(modelId: String): Int?
    suspend fun setModelContextTokens(modelId: String, contextTokens: Int)
    val modelContextTokensFlow: Flow<Map<String, Int>>
    suspend fun getDisabledTools(): Set<String>
    suspend fun getEnabledOverrides(): Set<String>
    suspend fun saveToolState(disabled: Set<String>, enabledOverrides: Set<String>)
}
